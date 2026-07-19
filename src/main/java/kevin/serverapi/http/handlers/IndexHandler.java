package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.Set;

/**
 * /api 與 /api/v1 — 站點索引。
 *
 * 未通過驗證時不會列出受保護的站點：否則索引等於直接告訴攻擊者
 * 哪些端點存在且藏有值得保護的資料，先前把它們藏成 404 也就失去意義。
 *
 * 注意：HttpServer 採最長前綴比對，未匹配的子路徑（例如 /api/nothing）
 * 都會落到本 handler，因此需明確判斷路徑並回傳 404。
 */
public class IndexHandler extends ApiHandler {

    private final Map<String, String> stations;      // 路徑 -> 說明
    private final Set<String> protectedPaths;        // 需要金鑰的站點路徑
    private final String basePath;

    public IndexHandler(ServerAPIPlugin plugin, Map<String, String> stations,
                        Set<String> protectedPaths, String basePath) {
        super(plugin);
        this.stations = stations;
        this.protectedPaths = protectedPaths;
        this.basePath = basePath;
    }

    @Override
    protected Object build(HttpExchange exchange) {
        // 僅接受 context 本身（/api 或 /api/v1），其餘視為不存在的端點
        if (!remainderOf(exchange).isEmpty()) {
            throw new NotFoundException(plugin.lang().get("api.error.not-found", exchange.getRequestURI().getPath()));
        }

        boolean authenticated = isAuthenticated(exchange);
        boolean hide = plugin.isHideProtected();
        // require-key 只有在 auth.enabled 為 true 時才真正生效。
        // 否則標記「需要金鑰」卻仍拿得到資料，會誤導呼叫端。
        boolean authOn = plugin.isAuthEnabled();

        Json.JsonArray arr = Json.arr();
        int hidden = 0;
        for (Map.Entry<String, String> e : stations.entrySet()) {
            if (!authenticated && hide && protectedPaths.contains(e.getKey())) {
                hidden++;
                continue;
            }
            arr.add(Json.obj()
                    .put("path", e.getKey())
                    .put("description", e.getValue())
                    .put("requiresKey", authOn && protectedPaths.contains(e.getKey())));
        }

        Json.JsonArray versions = Json.arr();
        versions.add(Json.obj()
                .put("version", API_VERSION)
                .put("path", basePath + "/" + API_VERSION)
                .put("current", true));

        Json.JsonObject out = Json.obj()
                .put("name", "ServerAPI")
                .put("authEnabled", authOn)
                .put("pluginVersion", plugin.getPluginMeta().getVersion())
                .put("versions", versions)
                .put("alias", basePath + " → " + basePath + "/" + API_VERSION)
                .put("stations", arr);
        // 僅在已驗證時說明有多少站點被隱藏；未驗證者不該知道還有別的東西
        if (authenticated && hidden > 0) {
            out.put("hiddenStations", hidden);
        }
        return out;
    }
}
