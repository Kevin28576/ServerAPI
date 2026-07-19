package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.CachedApiHandler;
import kevin.serverapi.json.Json;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.TreeMap;

/**
 * /api/entities — 各世界的實體統計（依類型分類）。
 * 由主執行緒定期快照，請求只讀快取。刻意排除玩家。
 */
public class EntitiesHandler extends CachedApiHandler {

    public EntitiesHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object compute() {
        Json.JsonArray worlds = Json.arr();
        int grandTotal = 0;

        for (World w : Bukkit.getWorlds()) {
            Map<String, Integer> counts = new TreeMap<>();
            int total = 0;
            for (Entity e : w.getEntities()) {
                if (e instanceof Player) continue;
                counts.merge(e.getType().name(), 1, Integer::sum);
                total++;
            }
            grandTotal += total;

            Json.JsonObject byType = Json.obj();
            counts.forEach(byType::put);

            worlds.add(Json.obj()
                    .put("world", w.getName())
                    .put("total", total)
                    .put("byType", byType));
        }

        return Json.obj()
                .put("totalEntities", grandTotal)
                .put("worlds", worlds);
    }
}
