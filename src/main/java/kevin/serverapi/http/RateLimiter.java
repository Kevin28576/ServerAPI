package kevin.serverapi.http;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket 速率限制。
 *
 * 每個來源 IP 一個桶：以固定速率補充代幣，每次請求消耗一枚。
 * 相較於固定時間窗，token bucket 不會在窗口交界處出現兩倍突發，
 * 又能容許正常使用時的短暫叢發（burst）。
 *
 * 另可設定全域桶，限制所有來源合計的請求量，避免大量不同 IP 一起打爆伺服器。
 *
 * 記憶體：每個 IP 僅佔幾十 bytes，且有上限與定期清理，不會無限成長。
 */
public final class RateLimiter {

    /** 追蹤的 IP 數量上限，超過時清除閒置者，避免被大量偽造來源撐爆記憶體。 */
    private static final int MAX_TRACKED_IPS = 20_000;

    private final double ratePerSecond;
    private final double burst;
    private final Bucket globalBucket;      // 可為 null（未設定全域上限）
    private final Set<String> exemptIps;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param perMinute       每個 IP 每分鐘的請求上限
     * @param burst           允許的瞬間叢發量
     * @param globalPerMinute 所有來源合計的每分鐘上限；<= 0 表示不限制
     * @param exemptIps       不受限制的來源
     */
    public RateLimiter(int perMinute, int burst, int globalPerMinute, Set<String> exemptIps) {
        this.ratePerSecond = Math.max(1, perMinute) / 60.0;
        this.burst = Math.max(1, burst);
        this.exemptIps = exemptIps == null ? Set.of() : exemptIps;
        this.globalBucket = globalPerMinute > 0
                ? new Bucket(globalPerMinute / 60.0, Math.max(burst, globalPerMinute / 10.0))
                : null;
    }

    /**
     * 檢查是否放行。
     *
     * @return 0 表示放行；否則為建議的重試等待秒數（供 Retry-After 使用）
     */
    public long check(String ip) {
        if (ip != null && exemptIps.contains(ip)) return 0;

        if (globalBucket != null) {
            long wait = globalBucket.tryConsume();
            if (wait > 0) return wait;
        }

        if (buckets.size() > MAX_TRACKED_IPS) {
            evictStale(60_000);
        }
        Bucket bucket = buckets.computeIfAbsent(ip == null ? "?" : ip,
                k -> new Bucket(ratePerSecond, burst));
        return bucket.tryConsume();
    }

    /** 清除閒置超過指定時間的桶（由定期任務呼叫）。 */
    public int evictStale(long idleMs) {
        long cutoff = System.nanoTime() - idleMs * 1_000_000L;
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> e.getValue().lastUsedNanos() < cutoff);
        return before - buckets.size();
    }

    public int trackedIps() {
        return buckets.size();
    }

    /** 單一來源的代幣桶。方法皆 synchronized —— 同一 IP 的競爭極低。 */
    private static final class Bucket {
        private final double ratePerSecond;
        private final double capacity;
        private double tokens;
        private long lastRefillNanos;

        Bucket(double ratePerSecond, double capacity) {
            this.ratePerSecond = ratePerSecond;
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized long tryConsume() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * ratePerSecond);

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return 0;
            }
            // 補滿一枚代幣所需的秒數，至少回報 1 秒
            return Math.max(1, (long) Math.ceil((1.0 - tokens) / ratePerSecond));
        }

        synchronized long lastUsedNanos() {
            return lastRefillNanos;
        }
    }
}
