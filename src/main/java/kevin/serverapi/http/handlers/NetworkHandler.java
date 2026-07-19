package kevin.serverapi.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.network.NetworkService;

/**
 * /api/network — 跨伺服器彙整（多服支援）。
 *
 * 各伺服器以心跳把狀態寫入共用 Redis，本端點掃描並彙整同一 cluster 下的所有伺服器。
 * 未設定 Redis 時回傳 503（跨服功能需要共用的 Redis）。
 */
public class NetworkHandler extends ApiHandler {

    private final NetworkService network;

    public NetworkHandler(ServerAPIPlugin plugin, NetworkService network) {
        super(plugin);
        this.network = network;
    }

    @Override
    protected Object build(HttpExchange exchange) {
        if (network == null || !network.isEnabled()) {
            throw new ApiException(503, plugin.lang().get("api.error.network-disabled"));
        }
        if (!network.hasRedis()) {
            throw new ApiException(503, plugin.lang().get("api.error.network-no-redis"));
        }
        return network.aggregate();
    }
}
