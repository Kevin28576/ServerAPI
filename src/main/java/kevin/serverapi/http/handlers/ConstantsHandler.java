package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.SpawnCategory;

/**
 * /api/constants — 伺服器可用的靜態列舉/常數參考資料（公開）。
 */
public class ConstantsHandler extends ApiHandler {

    public ConstantsHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object build(HttpExchange exchange) {
        return Json.obj()
                .put("gameModes", names(GameMode.values()))
                .put("difficulties", names(Difficulty.values()))
                .put("environments", names(World.Environment.values()))
                .put("weatherTypes", names(WeatherType.values()))
                .put("spawnCategories", names(SpawnCategory.values()))
                .put("entityTypeCount", EntityType.values().length)
                .put("materialCount", Material.values().length);
    }

    private static Json.JsonArray names(Enum<?>[] values) {
        Json.JsonArray arr = Json.arr();
        for (Enum<?> v : values) arr.add(v.name());
        return arr;
    }
}
