package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.discord.DiscordBridge;
import kevin.serverapi.history.HistoryService;
import kevin.serverapi.http.CachedApiHandler;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Arrays;

/**
 * /api/status — 彙總狀態。
 * {
 *   "online":    [ 線上玩家 UUID ... ],
 *   "minecraft": 線上人數,
 *   "discord":   DiscordSRV 主 Guild 成員數（未安裝/未就緒時為 null）
 * }
 *
 * 帶 ?history=true 時附上 history 物件（last / online[] / discord[]）。
 * 線上清單由主執行緒定期快照；discord 與 history 在請求時取得（非同步安全）。
 */
public class StatusHandler extends CachedApiHandler {

    private final HistoryService history; // 可為 null（未啟用）

    public StatusHandler(ServerAPIPlugin plugin, HistoryService history) {
        super(plugin);
        this.history = history;
    }

    /** 快照：線上玩家 UUID 陣列 + 人數。 */
    private record OnlineCache(Json.JsonArray online, int count) {}

    @Override
    protected Object compute() {
        Json.JsonArray online = Json.arr();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId().toString());
        }
        return new OnlineCache(online, Bukkit.getOnlinePlayers().size());
    }

    @Override
    protected Object select(HttpExchange exchange, Object cache) {
        OnlineCache oc = (OnlineCache) cache;
        Json.JsonObject result = Json.obj()
                .put("online", oc.online())
                .put("minecraft", oc.count())
                .put("discord", DiscordBridge.getMemberCount());

        if (history != null && wantsHistory(exchange)) {
            String json = history.getHistoryJson();
            result.put("history", json != null ? Json.raw(json) : null);
        }
        return result;
    }

    private static boolean wantsHistory(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        return query != null && Arrays.asList(query.split("&")).contains("history=true");
    }
}
