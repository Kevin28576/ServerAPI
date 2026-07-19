package kevin.serverapi.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 以 JDBC 實作的歷史儲存，支援 SQLite / MySQL / MariaDB。
 * 連線池由共用的 {@link SqlDatabase} 提供。
 *
 * 多服支援：資料表含 server 欄位，多台伺服器可共用同一個資料庫；
 * 讀寫皆以本服的 serverId 過濾，互不干擾。
 */
public final class SqlHistoryStore implements HistoryStore {

    private final SqlDatabase db;
    private final String table;
    private final String serverId;

    public SqlHistoryStore(SqlDatabase db, String table, String serverId) {
        this.db = db;
        this.table = table;
        this.serverId = (serverId == null || serverId.isBlank()) ? "global" : serverId;
    }

    @Override
    public void init() throws Exception {
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "ts BIGINT NOT NULL, "
                + "server VARCHAR(64) NOT NULL DEFAULT 'global', "
                + "online INT NOT NULL, "
                + "discord INT NULL, "
                + "PRIMARY KEY (ts, server))";
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(ddl);
        }
        migrateAddServerColumn();
    }

    /**
     * 舊版資料表的相容處理：
     *  1. 補上 server 欄位（已存在則略過）
     *  2. 將早期以空字串表示「未指定」的資料轉為可讀的 global
     */
    private void migrateAddServerColumn() {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN server VARCHAR(64) NOT NULL DEFAULT 'global'");
        } catch (Exception ignored) {
            // 欄位已存在（新建的資料表即屬此情形）
        }
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE " + table + " SET server = 'global' WHERE server = ''");
        } catch (Exception ignored) {
            // 無舊資料需轉換
        }
    }

    @Override
    public void append(long ts, int online, Integer discord) throws Exception {
        String sql = "INSERT INTO " + table + " (ts, server, online, discord) VALUES (?, ?, ?, ?)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, ts);
            ps.setString(2, serverId);
            ps.setInt(3, online);
            if (discord == null) ps.setNull(4, Types.INTEGER);
            else ps.setInt(4, discord);
            ps.executeUpdate();
        }
    }

    @Override
    public void trim(long olderThanTs) throws Exception {
        String sql = "DELETE FROM " + table + " WHERE ts < ? AND server = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, olderThanTs);
            ps.setString(2, serverId);
            ps.executeUpdate();
        }
    }

    @Override
    public HistoryData read(long sinceTs) throws Exception {
        String sql = "SELECT ts, online, discord FROM " + table
                + " WHERE ts >= ? AND server = ? ORDER BY ts ASC";
        List<Integer> online = new ArrayList<>();
        List<Integer> discord = new ArrayList<>();
        long last = 0;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, sinceTs);
            ps.setString(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    last = rs.getLong("ts");
                    online.add(rs.getInt("online"));
                    int d = rs.getInt("discord");
                    discord.add(rs.wasNull() ? null : d);
                }
            }
        }
        return new HistoryData(last, online, discord);
    }

    /** 連線池由 {@link SqlDatabase} 統一關閉，此處不需動作。 */
    @Override
    public void close() {
    }
}
