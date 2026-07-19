package kevin.serverapi.http;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;

/**
 * 需要「主執行緒才安全」的資料（World / Entity / Player 物件）之站點基底。
 *
 * 資料由主執行緒的定期任務呼叫 {@link #refresh()} 收集並存成快取；
 * HTTP 請求只讀取快取（{@link #build}），因此請求量再大也不會碰主執行緒。
 */
public abstract class CachedApiHandler extends ApiHandler {

    private volatile Object cached;
    /** 快照實際擷取的時間；尚未擷取為 0。 */
    private volatile long cachedAt;

    protected CachedApiHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    /** 由主執行緒定期呼叫：收集需要主執行緒的資料並更新快取。 */
    public final void refresh() {
        try {
            cached = compute();
            cachedAt = System.currentTimeMillis();
        } catch (Throwable t) {
            plugin.getLogger().warning(plugin.lang().get("console.warn.snapshot-failed", getClass().getSimpleName(), t));
        }
    }

    /**
     * 除了 meta.updatedAt（本次回應時間）外，另附上 meta.cachedAt（快照擷取時間）。
     * 兩者差距即為此份資料的「陳舊程度」，呼叫端可自行判斷要採用哪一個。
     */
    @Override
    protected void decorateMeta(Json.JsonObject meta) {
        long at = cachedAt;
        meta.put("cachedAt", at > 0 ? at : null);
    }

    /** 在主執行緒上執行：收集資料並回傳可序列化節點。 */
    protected abstract Object compute();

    @Override
    protected final Object build(HttpExchange exchange) {
        Object c = cached;
        if (c == null) {
            return Json.obj().put("status", "initializing").put("message", plugin.lang().get("api.initializing"));
        }
        return select(exchange, c);
    }

    /** 預設直接回傳整份快取；需要路徑參數（如 /worlds/{name}）者可覆寫。 */
    protected Object select(HttpExchange exchange, Object cache) {
        return cache;
    }
}
