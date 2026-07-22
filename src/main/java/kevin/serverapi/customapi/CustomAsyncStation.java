package kevin.serverapi.customapi;

import com.sun.net.httpserver.HttpExchange;
import java.util.function.Supplier;
import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;

/**
 * 即時模式的第三方站點 handler：在 HTTP 執行緒上呼叫第三方供應者。
 *
 * 供應者若丟例外，{@link ApiHandler} 會攔下並回 500，只影響這一次請求，
 * 不會波及 ServerAPI 本身或其他站點。
 */
public final class CustomAsyncStation extends ApiHandler {

    private final Supplier<Object> supplier;

    public CustomAsyncStation(ServerAPIPlugin plugin, Supplier<Object> supplier) {
        super(plugin);
        this.supplier = supplier;
    }

    @Override
    protected Object build(HttpExchange exchange) {
        return CustomAPIManager.normalize(supplier.get());
    }
}
