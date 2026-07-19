package kevin.serverapi.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 共用的資料庫連線池（SQLite / MySQL / MariaDB）。
 * 歷史資料與玩家統計快取共用同一個池，避免 SQLite 多重連線池互相鎖檔。
 */
public final class SqlDatabase implements AutoCloseable {

    /** 連線設定（不含資料表名稱，各 store 自行指定）。 */
    public record Settings(String jdbcUrl, String driver, String user, String password, int poolSize) {}

    private final Settings settings;
    private HikariDataSource dataSource;

    public SqlDatabase(Settings settings) {
        this.settings = settings;
    }

    public void init() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(settings.jdbcUrl());
        cfg.setDriverClassName(settings.driver());
        if (settings.user() != null) cfg.setUsername(settings.user());
        if (settings.password() != null) cfg.setPassword(settings.password());
        cfg.setMaximumPoolSize(Math.max(1, settings.poolSize()));
        cfg.setPoolName("ServerAPI-DB");
        // 5 秒：DB 掛掉時的最壞阻塞上限（各服務另有斷路器保護）
        cfg.setConnectionTimeout(5_000);
        this.dataSource = new HikariDataSource(cfg);
    }

    public Connection getConnection() throws SQLException {
        // 這代表 init() 沒被呼叫，屬於程式邏輯錯誤而非服主能處理的狀況，
        // 因此維持英文技術訊息；它會被包進已本地化的 storage-failed 一起輸出。
        if (dataSource == null) throw new SQLException("SqlDatabase.init() was not called");
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
