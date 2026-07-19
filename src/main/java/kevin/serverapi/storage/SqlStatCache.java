package kevin.serverapi.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/**
 * 玩家統計快取的資料庫後端（無 Redis 時的落地層）。
 * 以 (server, uuid) 為主鍵存放已序列化的 JSON，附帶到期時間；不佔用伺服器記憶體。
 *
 * 多服支援：統計資料本就是各服獨立（統計檔存於各自的世界資料夾），
 * 故以 server 一併作為主鍵，多台伺服器可共用同一資料庫。
 */
public final class SqlStatCache {

    private final SqlDatabase db;
    private final String table;
    private final String serverId;

    public SqlStatCache(SqlDatabase db, String table, String serverId) {
        this.db = db;
        this.table = table;
        this.serverId = (serverId == null || serverId.isBlank()) ? "global" : serverId;
    }

    public void init() throws Exception {
        // payload 使用 TEXT：跨 SQLite / MySQL / MariaDB 皆可容納大型 JSON
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "server VARCHAR(64) NOT NULL DEFAULT 'global', "
                + "uuid VARCHAR(36) NOT NULL, "
                + "payload TEXT NOT NULL, "
                + "expires_at BIGINT NOT NULL, "
                + "PRIMARY KEY (server, uuid))";
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(ddl);
        }
        // 早期以空字串表示「未指定」的快取直接清除即可（僅為快取，會自動重建），
        // 避免與 global 的資料列產生主鍵衝突。
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM " + table + " WHERE server = ''");
        } catch (Exception ignored) {
            // 無舊資料需清除
        }
    }

    /** 讀取未過期的快取；不存在或已過期回傳 null。 */
    public String get(UUID uuid, long now) throws Exception {
        String sql = "SELECT payload FROM " + table
                + " WHERE server = ? AND uuid = ? AND expires_at > ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setString(2, uuid.toString());
            ps.setLong(3, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("payload") : null;
            }
        }
    }

    /** 寫入或覆蓋快取（以先刪後插實作 upsert，跨方言相容）。 */
    public void put(UUID uuid, String payload, long expiresAt) throws Exception {
        try (Connection c = db.getConnection()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM " + table + " WHERE server = ? AND uuid = ?")) {
                del.setString(1, serverId);
                del.setString(2, uuid.toString());
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO " + table + " (server, uuid, payload, expires_at) VALUES (?, ?, ?, ?)")) {
                ins.setString(1, serverId);
                ins.setString(2, uuid.toString());
                ins.setString(3, payload);
                ins.setLong(4, expiresAt);
                ins.executeUpdate();
            }
        }
    }

    /** 清除已過期的資料（含其他伺服器留下的），避免資料表無限成長。 */
    public int purgeExpired(long now) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE expires_at <= ?")) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        }
    }
}
