package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.CachedApiHandler;
import kevin.serverapi.json.Json;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.SpawnCategory;

/**
 * /api/spawnlimits — 各世界的生怪上限與生怪間隔（依 SpawnCategory）。
 * 由主執行緒定期快照，請求只讀快取。
 */
public class SpawnLimitsHandler extends CachedApiHandler {

    public SpawnLimitsHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object compute() {
        Json.JsonArray worlds = Json.arr();
        for (World w : Bukkit.getWorlds()) {
            Json.JsonObject limits = Json.obj();
            Json.JsonObject ticks = Json.obj();
            for (SpawnCategory cat : SpawnCategory.values()) {
                if (cat == SpawnCategory.MISC) continue;
                limits.put(cat.name(), w.getSpawnLimit(cat));
                ticks.put(cat.name(), w.getTicksPerSpawns(cat));
            }
            worlds.add(Json.obj()
                    .put("world", w.getName())
                    .put("spawnLimits", limits)
                    .put("ticksPerSpawns", ticks));
        }
        return Json.obj()
                .put("count", Bukkit.getWorlds().size())
                .put("worlds", worlds);
    }
}
