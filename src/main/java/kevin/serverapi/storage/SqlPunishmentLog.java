package kevin.serverapi.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 處罰紀錄的持久化儲存。
 *
 * 這裡保存的是「歷史」而非「當前狀態」：封鎖被解除、禁言到期之後，
 * 紀錄仍然保留，只是標上 liftedAt／expires，因此官網能列出所有曾受處罰的玩家。
 *
 * 資料來源：
 *   kick        —— 監聽 PlayerKickEvent（Minecraft 與 CMI 皆不保存此歷史）
 *   ban / ipban —— 定期與封鎖名單對帳，偵測新增與解除
 *   mute / jail —— 定期與 CMI 狀態對帳（線上玩家）
 */
public final class SqlPunishmentLog {

    /**
     * @param target   對象識別：玩家為 UUID、IP 封鎖為位址字串
     * @param at       處罰發生時間
     * @param expires  到期時間；null 表示永久
     * @param liftedAt 手動解除時間；null 表示未解除
     */
    public record Entry(String type, String target, String name, String reason,
                        String issuedBy, String cause, long at,
                        Long expires, Long liftedAt, String liftedBy) {

        /** 便利建構子：一次性事件（踢出、警告）。 */
        public static Entry event(String type, String target, String name, String reason,
                                  String issuedBy, String cause, long at) {
            return new Entry(type, target, name, reason, issuedBy, cause, at, null, null, null);
        }

        /** 此處罰目前是否仍生效。 */
        public boolean active(long now) {
            if (liftedAt != null) return false;
            return expires == null || expires > now;
        }
    }

    private final SqlDatabase db;
    private final String table;
    private final String serverId;

    public SqlPunishmentLog(SqlDatabase db, String table, String serverId) {
        this.db = db;
        this.table = table;
        this.serverId = (serverId == null || serverId.isBlank()) ? "global" : serverId;
    }

    public void init() throws Exception {
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "server VARCHAR(64) NOT NULL DEFAULT 'global', "
                + "type VARCHAR(32) NOT NULL, "
                + "target VARCHAR(64), "
                + "name VARCHAR(32), "
                + "reason TEXT, "
                + "issued_by VARCHAR(64), "
                + "cause VARCHAR(48), "
                + "at BIGINT NOT NULL, "
                + "expires BIGINT, "
                + "lifted_at BIGINT, "
                + "lifted_by VARCHAR(64))";
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(ddl);
        }
        // 早期版本的欄位補齊（已存在則忽略）
        for (String alter : new String[]{
                "ALTER TABLE " + table + " ADD COLUMN target VARCHAR(64)",
                "ALTER TABLE " + table + " ADD COLUMN expires BIGINT",
                "ALTER TABLE " + table + " ADD COLUMN lifted_at BIGINT",
                "ALTER TABLE " + table + " ADD COLUMN lifted_by VARCHAR(64)"}) {
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate(alter);
            } catch (Exception ignored) {
                // 欄位已存在
            }
        }
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE INDEX idx_" + table + "_at ON " + table + " (server, at)");
        } catch (Exception ignored) {
            // 索引已存在（MySQL 不支援 CREATE INDEX IF NOT EXISTS）
        }
    }

    public void insert(Collection<Entry> entries) throws Exception {
        if (entries.isEmpty()) return;
        String sql = "INSERT INTO " + table
                + " (server, type, target, name, reason, issued_by, cause, at, expires, lifted_at, lifted_by)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (Entry e : entries) {
                ps.setString(1, serverId);
                ps.setString(2, e.type());
                ps.setString(3, e.target());
                ps.setString(4, e.name());
                ps.setString(5, e.reason());
                ps.setString(6, e.issuedBy());
                ps.setString(7, e.cause());
                ps.setLong(8, e.at());
                setNullableLong(ps, 9, e.expires());
                setNullableLong(ps, 10, e.liftedAt());
                ps.setString(11, e.liftedBy());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long v) throws Exception {
        if (v == null) ps.setNull(idx, Types.BIGINT);
        else ps.setLong(idx, v);
    }

    /**
     * 目前仍「生效中」的處罰（未被解除且未到期），供對帳比對用。
     *
     * 必須排除已到期者：到期不會寫入 liftedAt（其 expires 已能表達狀態），
     * 若只用 lifted_at IS NULL 判斷，過期的紀錄會永遠留在比對集合裡，
     * 導致同一對象之後的新處罰全被誤判為「已存在」而不寫入。
     */
    public Map<String, Entry> findActive(String type, long now) throws Exception {
        String sql = "SELECT type, target, name, reason, issued_by, cause, at, expires, lifted_at, lifted_by"
                + " FROM " + table + " WHERE server = ? AND type = ? AND lifted_at IS NULL"
                + " AND (expires IS NULL OR expires > ?) ORDER BY at ASC";
        Map<String, Entry> out = new LinkedHashMap<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, type);
            ps.setLong(3, now);
            for (Entry e : read(ps)) {
                if (e.target() != null) out.put(e.target(), e);
            }
        }
        return out;
    }

    /** 標記處罰已被解除（例如管理員 unban／unmute）。 */
    public void markLifted(String type, String target, long liftedAt, String liftedBy) throws Exception {
        String sql = "UPDATE " + table + " SET lifted_at = ?, lifted_by = ?"
                + " WHERE server = ? AND type = ? AND target = ? AND lifted_at IS NULL"
                + " AND (expires IS NULL OR expires > ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, liftedAt);
            ps.setString(2, liftedBy);
            ps.setString(3, serverId);
            ps.setString(4, type);
            ps.setString(5, target);
            ps.setLong(6, liftedAt);
            ps.executeUpdate();
        }
    }

    /** 最近的紀錄（由新到舊），含已解除與已到期者。 */
    public List<Entry> recent(int limit) throws Exception {
        String sql = "SELECT type, target, name, reason, issued_by, cause, at, expires, lifted_at, lifted_by"
                + " FROM " + table + " WHERE server = ? ORDER BY at DESC LIMIT ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setInt(2, Math.max(1, limit));
            return read(ps);
        }
    }

    /** 指定對象的紀錄（由新到舊）。 */
    public List<Entry> forTarget(String target, int limit) throws Exception {
        String sql = "SELECT type, target, name, reason, issued_by, cause, at, expires, lifted_at, lifted_by"
                + " FROM " + table + " WHERE server = ? AND target = ? ORDER BY at DESC LIMIT ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, target);
            ps.setInt(3, Math.max(1, limit));
            return read(ps);
        }
    }

    /** 各類型的累計筆數（供統計用，涵蓋全部歷史）。 */
    public Map<String, Integer> countByType() throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        String sql = "SELECT type, COUNT(*) AS n FROM " + table + " WHERE server = ? GROUP BY type";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString("type"), rs.getInt("n"));
            }
        }
        return out;
    }

    /** 受過處罰的不重複玩家數。 */
    public int distinctTargets() throws Exception {
        String sql = "SELECT COUNT(DISTINCT target) AS n FROM " + table + " WHERE server = ? AND target IS NOT NULL";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("n") : 0;
            }
        }
    }

    private static List<Entry> read(PreparedStatement ps) throws Exception {
        List<Entry> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long expires = rs.getLong("expires");
                boolean expiresNull = rs.wasNull();
                long lifted = rs.getLong("lifted_at");
                boolean liftedNull = rs.wasNull();
                out.add(new Entry(rs.getString("type"), rs.getString("target"), rs.getString("name"),
                        rs.getString("reason"), rs.getString("issued_by"), rs.getString("cause"),
                        rs.getLong("at"),
                        expiresNull ? null : expires,
                        liftedNull ? null : lifted,
                        rs.getString("lifted_by")));
            }
        }
        return out;
    }

    public int purgeOlderThan(long ts) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM " + table + " WHERE server = ? AND at < ?")) {
            ps.setString(1, serverId);
            ps.setLong(2, ts);
            return ps.executeUpdate();
        }
    }
}
