package kevin.serverapi.customapi;

import java.util.function.Supplier;
import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.CachedApiHandler;

/**
 * 快照模式的第三方站點 handler：由主執行緒定期呼叫第三方供應者，
 * HTTP 請求只讀快取。適合需要存取 Bukkit API 的資料。
 *
 * 供應者若丟例外，{@link CachedApiHandler#refresh()} 會攔下並記錄，
 * 續用上一份快取，不會中斷服務。
 */
public final class CustomCachedStation extends CachedApiHandler {

    private final Supplier<Object> supplier;

    public CustomCachedStation(ServerAPIPlugin plugin, Supplier<Object> supplier) {
        super(plugin);
        this.supplier = supplier;
    }

    @Override
    protected Object compute() {
        return CustomAPIManager.normalize(supplier.get());
    }
}
