package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.CachedApiHandler;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /api/worlds        — 所有世界列表（含細節）
 * /api/worlds/{name} — 單一世界細節
 *
 * 資料由主執行緒定期快照（見 {@link CachedApiHandler}），請求只讀快取。
 *
 * 注意：實體數量屬「易變資料」，快照間隔較長（預設 10 分鐘）會導致數值過期，
 * 故不在此提供；請改用更新頻率較高的 /api/entities。
 */
public class WorldsHandler extends CachedApiHandler {

    public WorldsHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    /** 快照：世界列表物件 + 依名稱索引。 */
    private record WorldCache(Json.JsonObject list, Map<String, Json.JsonObject> byName) {}

    @Override
    protected Object compute() {
        Map<String, Json.JsonObject> byName = new LinkedHashMap<>();
        Json.JsonArray arr = Json.arr();
        for (World w : Bukkit.getWorlds()) {
            Json.JsonObject o = worldJson(w);
            byName.put(w.getName(), o);
            arr.add(o);
        }
        Json.JsonObject list = Json.obj().put("count", byName.size()).put("worlds", arr);
        return new WorldCache(list, byName);
    }

    @Override
    protected Object select(HttpExchange exchange, Object cache) {
        WorldCache wc = (WorldCache) cache;

        // 以實際匹配到的 context 路徑取餘，才能同時支援 /api/worlds 與 /api/v1/worlds
        String remainder = remainderOf(exchange);

        if (remainder.isEmpty()) {
            return wc.list();
        }

        String name = URLDecoder.decode(remainder, StandardCharsets.UTF_8);
        Json.JsonObject world = wc.byName().get(name);
        if (world == null) {
            throw new NotFoundException(plugin.lang().get("api.error.world-not-found", name));
        }
        return world;
    }

    // World#getPVP() 於 1.21.9 標記 @Deprecated（但非 for-removal），
    // 且目前無替代 API 可取得每世界 PvP 狀態，故在此局部抑制。
    @SuppressWarnings("deprecation")
    private Json.JsonObject worldJson(World w) {
        Location spawn = w.getSpawnLocation();
        WorldBorder border = w.getWorldBorder();

        Json.JsonObject spawnObj = Json.obj()
                .put("x", spawn.getBlockX())
                .put("y", spawn.getBlockY())
                .put("z", spawn.getBlockZ());

        Json.JsonObject borderObj = Json.obj()
                .put("size", border.getSize())
                .put("centerX", border.getCenter().getX())
                .put("centerZ", border.getCenter().getZ())
                .put("damageAmount", border.getDamageAmount())
                .put("damageBuffer", border.getDamageBuffer())
                .put("warningDistance", border.getWarningDistance())
                .put("warningTimeTicks", border.getWarningTimeTicks());

        Json.JsonObject weather = Json.obj()
                .put("hasStorm", w.hasStorm())
                .put("thundering", w.isThundering())
                .put("weatherDuration", w.getWeatherDuration())
                .put("thunderDuration", w.getThunderDuration())
                .put("clear", w.isClearWeather());

        return Json.obj()
                .put("name", w.getName())
                .put("uid", w.getUID().toString())
                .put("environment", w.getEnvironment().name())
                .put("seed", w.getSeed())
                .put("difficulty", w.getDifficulty().name())
                .put("time", w.getTime())
                .put("fullTime", w.getFullTime())
                .put("dayCount", w.getFullTime() / 24000L)
                .put("weather", weather)
                .put("pvp", w.getPVP())
                .put("allowAnimals", w.getAllowAnimals())
                .put("allowMonsters", w.getAllowMonsters())
                .put("minHeight", w.getMinHeight())
                .put("maxHeight", w.getMaxHeight())
                .put("seaLevel", w.getSeaLevel())
                .put("viewDistance", w.getViewDistance())
                .put("loadedChunks", w.getLoadedChunks().length)
                .put("spawnLocation", spawnObj)
                .put("worldBorder", borderObj);
    }
}
