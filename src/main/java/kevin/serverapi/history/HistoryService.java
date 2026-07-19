package kevin.serverapi.history;

import kevin.serverapi.json.Json;
import kevin.serverapi.storage.HistoryData;
import kevin.serverapi.storage.HistoryStore;
import kevin.serverapi.storage.RedisCache;

import java.util.ArrayDeque;
import kevin.serverapi.lang.Lang;

import java.util.logging.Logger;

/**
 * 歷史資料協調層：
 *  - 查詢：優先讀 Redis；未命中則讀資料庫並回填 Redis / 記憶體快取。
 *  - 更新：寫入資料庫 → 依保留時間修剪 → 重新讀取 → 覆蓋 Redis / 記憶體快取。
 *
 * 韌性設計：
 *  - 資料庫（SQL）是唯一真相來源；Redis 只是加速層。
 *  - DB 斷路器：DB 讀取失敗後 {@value #STORE_COOLDOWN_MS}ms 內不再嘗試，
 *    防止查詢請求逐一卡在連線逾時、耗盡 HTTP 執行緒池。
 *  - 取樣重播佇列：DB 暫時不可用時，取樣點先入記憶體佇列，
 *    待 DB 恢復後於下次取樣一併補寫，將資料遺失窗口降到最低。
 *
 * 所有方法皆預期在非同步執行緒或 HTTP 執行緒呼叫，「不」碰主執行緒。
 */
public final class HistoryService {

    private static final long STORE_COOLDOWN_MS = 5000;
    private static final int PENDING_LIMIT = 500;

    private final HistoryStore store;
    private final RedisCache redis;      // 可為 null
    private final String redisKey;
    /** Redis 鍵的存活秒數：每次取樣都會續期，伺服器停止後自動過期清除。 */
    private final int redisTtlSeconds;
    private final long retentionMs;
    private final Logger logger;
    private final Lang lang;

    /** 無 Redis 時的記憶體回退快取（即已算好的 history JSON 字串）。 */
    private volatile String cachedJson;

    /** DB 斷路器：此時間點之前跳過資料庫「查詢」。 */
    private volatile long storeUnavailableUntil;

    /** 服務是否已關閉（防止 close 後仍有取樣/查詢動到已關閉的連線池）。 */
    private volatile boolean closed;

    /** DB 寫入失敗時暫存的取樣點，待恢復後重播（僅記憶體，插件關閉即失效）。 */
    private record PendingSample(long ts, int online, Integer discord) {}
    private final ArrayDeque<PendingSample> pending = new ArrayDeque<>();

    public HistoryService(HistoryStore store, RedisCache redis, String redisKey,
                          int redisTtlSeconds, long retentionMs, Logger logger, Lang lang) {
        this.store = store;
        this.redis = redis;
        this.redisKey = redisKey;
        this.redisTtlSeconds = Math.max(60, redisTtlSeconds);
        this.retentionMs = retentionMs;
        this.logger = logger;
        this.lang = lang;
    }

    /** 寫入 Redis（一律帶 TTL，避免產生永不過期的孤兒鍵）。 */
    private boolean writeRedis(String json) {
        return redis != null && redis.setex(redisKey, redisTtlSeconds, json);
    }

    public void init() throws Exception {
        store.init();
        rebuildCache();
    }

    /** 取樣：新增一筆並更新快取。於非同步排程呼叫。 */
    public synchronized void sample(int online, Integer discord) {
        if (closed) return;
        long now = System.currentTimeMillis();
        try {
            drainPending();
            store.append(now, online, discord);
            store.trim(now - retentionMs);
            String json = toJson(store.read(now - retentionMs));
            cachedJson = json;
            storeUnavailableUntil = 0L;
            writeRedis(json);
        } catch (Exception e) {
            // DB 暫時不可用：入佇列等待重播，並打開查詢斷路器
            if (pending.size() < PENDING_LIMIT) {
                pending.addLast(new PendingSample(now, online, discord));
            }
            storeUnavailableUntil = System.currentTimeMillis() + STORE_COOLDOWN_MS;
            logger.warning(lang.get("console.warn.history-sample-failed", pending.size(), e));
        }
    }

    /** 補寫先前失敗的取樣點（呼叫端需持有本物件監視器）。 */
    private void drainPending() throws Exception {
        while (!pending.isEmpty()) {
            PendingSample p = pending.peekFirst();
            store.append(p.ts(), p.online(), p.discord());
            pending.pollFirst();
        }
    }

    /** 目前待補寫的取樣筆數（DB 曾寫入失敗時累積）。 */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /**
     * 關服前的最後保全：補寫重播佇列 + 寫入當下取樣 + 同步 Redis。
     * 於主執行緒同步呼叫（關服時排程器已停止），確保資料不遺失。
     */
    public synchronized FlushResult flush(int online, Integer discord) {
        if (closed) return new FlushResult(false, 0, false, "closed");
        int queued = pending.size();
        long now = System.currentTimeMillis();
        try {
            drainPending();
            store.append(now, online, discord);
            store.trim(now - retentionMs);
            String json = toJson(store.read(now - retentionMs));
            cachedJson = json;
            boolean redisOk = writeRedis(json);
            return new FlushResult(true, queued, redisOk, null);
        } catch (Exception e) {
            return new FlushResult(false, queued - pending.size(), false, e.toString());
        }
    }

    /**
     * @param sampled     是否成功寫入最後一筆取樣
     * @param replayed    補寫成功的待重播筆數
     * @param redisSynced 是否同步至 Redis
     * @param error       失敗原因（成功時為 null）
     */
    public record FlushResult(boolean sampled, int replayed, boolean redisSynced, String error) {}

    /** 取得歷史 JSON 字串（供 /api/status?history=true 嵌入）。 */
    public String getHistoryJson() {
        if (redis != null) {
            String cached = redis.get(redisKey);
            if (cached != null) return cached;

            // Redis 未命中（可能剛重啟或短暫斷線）→ 以現有資料立即回填 Redis
            String data = (cachedJson != null) ? cachedJson : loadFromStore();
            if (data != null) writeRedis(data);   // 寫入失敗（仍斷線）會靜默略過
            return data;
        }
        String c = cachedJson;
        return (c != null) ? c : loadFromStore();
    }

    /** 於啟動時呼叫：從資料庫載入並回填 Redis（伺服器重啟後 Redis 亦由此重建）。 */
    private String rebuildCache() {
        String json = loadFromStore();
        if (json != null) writeRedis(json);
        return json;
    }

    /**
     * 從資料庫讀取並更新記憶體快取。
     * synchronized：同一時間僅一個執行緒查 DB（防快取踩踏）；
     * 斷路器開啟期間直接回傳現有快取，絕不讓大量請求排隊等 DB 逾時。
     */
    private synchronized String loadFromStore() {
        if (closed) return cachedJson;
        if (cachedJson != null) return cachedJson;   // 前一個執行緒剛重建完成
        if (System.currentTimeMillis() < storeUnavailableUntil) return cachedJson;
        try {
            String json = toJson(store.read(System.currentTimeMillis() - retentionMs));
            cachedJson = json;
            return json;
        } catch (Exception e) {
            storeUnavailableUntil = System.currentTimeMillis() + STORE_COOLDOWN_MS;
            logger.warning(lang.get("console.warn.history-read-failed", STORE_COOLDOWN_MS / 1000, e));
            return cachedJson;
        }
    }

    private static String toJson(HistoryData d) {
        Json.JsonArray online = Json.arr();
        for (Integer v : d.online()) online.add(v);
        Json.JsonArray discord = Json.arr();
        for (Integer v : d.discord()) discord.add(v);
        return Json.obj()
                .put("last", d.last())
                .put("online", online)
                .put("discord", discord)
                .toJson();
    }

    /**
     * synchronized：等待進行中的取樣完成後才關閉，避免關閉競態。
     * 注意：資料庫連線池與 Redis 為共用資源，由插件統一關閉，此處不處理。
     */
    public synchronized void close() {
        closed = true;
        try {
            store.close();
        } catch (Exception ignored) {
        }
    }
}
