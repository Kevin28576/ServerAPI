package kevin.serverapi.storage;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 快取包裝。查詢優先讀此處；更新後由此覆蓋。
 * 任何失敗皆靜默降級（回傳 null / 略過），不影響主要流程。
 */
public final class RedisCache implements AutoCloseable {

    private static final long COOLDOWN_MS = 5000;

    private final JedisPool pool;

    /** 斷路器：偵測到斷線後，此時間點前直接略過 Redis，避免每次查詢卡在連線逾時。 */
    private volatile long unavailableUntil = 0L;

    public RedisCache(String host, int port, String password, int database) {
        String pass = (password == null || password.isEmpty()) ? null : password;
        this.pool = new JedisPool(new HostAndPort(host, port),
                DefaultJedisClientConfig.builder()
                        .password(pass)
                        .database(database)
                        .connectionTimeoutMillis(2000)
                        .socketTimeoutMillis(2000)
                        .build());
    }

    /** 讀取指定鍵。 */
    public String get(String k) {
        if (skip()) return null;
        try (Jedis jedis = pool.getResource()) {
            String v = jedis.get(k);
            markUp();
            return v;
        } catch (Exception e) {
            markDown();
            return null;
        }
    }

    /**
     * 寫入指定鍵並設定存活秒數。
     * 所有鍵一律帶 TTL：伺服器汰除或改名後，殘留的鍵會自動消失，不會累積孤兒鍵。
     *
     * @return 是否確實寫入（斷線或降級時為 false）
     */
    public boolean setex(String k, int ttlSeconds, String value) {
        if (skip()) return false;
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(k, ttlSeconds, value);
            markUp();
            return true;
        } catch (Exception e) {
            markDown();
            return false;
        }
    }

    /**
     * 取得鍵的剩餘存活秒數。
     *
     * @return 秒數；{@code -1} 表示鍵存在但未設定過期時間；{@code -2} 表示鍵不存在。
     *         連線失敗時回傳 {@code -2}（視同不存在，呼叫端不會誤動作）。
     */
    public long ttl(String k) {
        if (skip()) return -2L;
        try (Jedis jedis = pool.getResource()) {
            long t = jedis.ttl(k);
            markUp();
            return t;
        } catch (Exception e) {
            markDown();
            return -2L;
        }
    }

    /** 為既有鍵補上存活秒數（不變更其值）。 */
    public boolean expire(String k, int seconds) {
        if (skip()) return false;
        try (Jedis jedis = pool.getResource()) {
            long r = jedis.expire(k, seconds);
            markUp();
            return r == 1L;
        } catch (Exception e) {
            markDown();
            return false;
        }
    }

    /**
     * 以 SCAN 逐批取得符合樣式的鍵（不使用 KEYS，避免阻塞 Redis）。
     * 用於跨伺服器彙整與舊版鍵清理。
     */
    public List<String> scanKeys(String pattern) {
        if (skip()) return List.of();
        List<String> out = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(pattern).count(100);
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                out.addAll(result.getResult());
                cursor = result.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
            markUp();
        } catch (Exception e) {
            markDown();
        }
        return out;
    }

    private boolean skip() {
        return System.currentTimeMillis() < unavailableUntil;
    }

    private void markDown() {
        unavailableUntil = System.currentTimeMillis() + COOLDOWN_MS;
    }

    private void markUp() {
        unavailableUntil = 0L;
    }

    @Override
    public void close() {
        try {
            pool.close();
        } catch (Exception ignored) {
        }
    }
}
