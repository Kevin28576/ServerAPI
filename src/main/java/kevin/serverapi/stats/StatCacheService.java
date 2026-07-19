package kevin.serverapi.stats;

import kevin.serverapi.storage.RedisCache;
import kevin.serverapi.storage.RedisKeys;
import kevin.serverapi.storage.SqlStatCache;

import java.util.UUID;
import java.util.function.Supplier;
import kevin.serverapi.lang.Lang;

import java.util.logging.Logger;

/**
 * 離線玩家 detailed 統計的持久化快取。
 *
 * 動機：離線玩家的 getStatistic() 會重讀統計檔，實測約 425ms／次，
 * 而線上玩家僅約 4ms。此處快取「昂貴的離線查詢」結果。
 *
 * 設計：資料存於 Redis（自動過期）或資料庫，**不佔用伺服器記憶體**。
 *  - 讀取：優先 Redis → 未命中則查資料庫 → 仍無則現算並寫回兩者
 *  - Redis 以 TTL 自動過期；資料庫則記錄 expires_at 並定期清理
 */
public final class StatCacheService {

    private static final String TYPE = "player_stat";

    private final SqlStatCache store;    // 可為 null（資料庫未啟用）
    private final RedisCache redis;      // 可為 null（未設定 Redis）
    private final RedisKeys keys;
    private final long ttlMs;
    private final Logger logger;
    private final Lang lang;

    public StatCacheService(SqlStatCache store, RedisCache redis, RedisKeys keys, long ttlMs, Logger logger, Lang lang) {
        this.store = store;
        this.redis = redis;
        this.keys = keys;
        this.ttlMs = ttlMs;
        this.logger = logger;
        this.lang = lang;
    }

    /** {namespace}:{cluster}:{server}:player_stat:{uuid} */
    private String redisKey(UUID uuid) {
        return keys.key(TYPE, uuid.toString());
    }

    public void init() throws Exception {
        if (store != null) store.init();
    }

    /**
     * 取得快取的統計 JSON；未命中則以 producer 現算並寫入快取。
     *
     * @param producer 昂貴的實際計算（僅在未命中時執行）
     * @return 統計 JSON 字串
     */
    public String get(UUID uuid, Supplier<String> producer) {
        long now = System.currentTimeMillis();

        // 1) Redis 優先
        if (redis != null) {
            String hit = redis.get(redisKey(uuid));
            if (hit != null) return hit;
        }

        // 2) 資料庫
        if (store != null) {
            try {
                String hit = store.get(uuid, now);
                if (hit != null) {
                    // 回填 Redis（例如 Redis 剛重啟）
                    if (redis != null) redis.setex(redisKey(uuid), (int) (ttlMs / 1000), hit);
                    return hit;
                }
            } catch (Exception e) {
                logger.warning(lang.get("console.warn.stat-cache-read-failed", e));
            }
        }

        // 3) 未命中：現算並寫入兩層
        String fresh = producer.get();
        if (fresh == null) return null;
        put(uuid, fresh, now);
        return fresh;
    }

    private void put(UUID uuid, String payload, long now) {
        if (redis != null) {
            redis.setex(redisKey(uuid), (int) (ttlMs / 1000), payload);
        }
        if (store != null) {
            try {
                store.put(uuid, payload, now + ttlMs);
            } catch (Exception e) {
                logger.warning(lang.get("console.warn.stat-cache-write-failed", e));
            }
        }
    }

    /** 定期清理資料庫中已過期的快取列。 */
    public void purgeExpired() {
        if (store == null) return;
        try {
            store.purgeExpired(System.currentTimeMillis());
        } catch (Exception e) {
            logger.warning(lang.get("console.warn.stat-cache-purge-failed", e));
        }
    }

    /** 是否有任何可用的快取層。 */
    public boolean isActive() {
        return store != null || redis != null;
    }
}
