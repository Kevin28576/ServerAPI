package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * /api/operators — 伺服器管理員 (OP) 名單（公開設定，與封鎖名單不同）。
 */
public class OperatorsHandler extends ApiHandler {

    public OperatorsHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object build(HttpExchange exchange) {
        var ops = Bukkit.getOperators();
        Json.JsonArray arr = Json.arr();
        for (OfflinePlayer p : ops) {
            arr.add(p.getUniqueId().toString());
        }
        return Json.obj()
                .put("count", ops.size())
                .put("operators", arr);
    }
}
