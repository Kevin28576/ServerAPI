package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * /api/whitelist — 白名單狀態與名單（屬公開設定，與封鎖名單不同）。
 */
public class WhitelistHandler extends ApiHandler {

    public WhitelistHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object build(HttpExchange exchange) {
        var whitelisted = Bukkit.getWhitelistedPlayers();
        Json.JsonArray players = Json.arr();
        for (OfflinePlayer p : whitelisted) {
            players.add(p.getUniqueId().toString());
        }
        return Json.obj()
                .put("enabled", Bukkit.hasWhitelist())
                .put("count", whitelisted.size())
                .put("players", players);
    }
}
