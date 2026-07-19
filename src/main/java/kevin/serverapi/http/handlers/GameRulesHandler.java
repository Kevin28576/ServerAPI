package kevin.serverapi.http.handlers;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.CachedApiHandler;
import kevin.serverapi.json.Json;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Registry;
import org.bukkit.World;

/**
 * /api/gamerules — 每個世界的 game rule 設定值。
 * 由主執行緒定期快照，請求只讀快取。
 * 以 Paper 的 Registry 列舉 game rule（取代已棄用的 GameRule.values()）。
 */
public class GameRulesHandler extends CachedApiHandler {

    public GameRulesHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object compute() {
        Registry<GameRule<?>> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE);

        Json.JsonArray worlds = Json.arr();
        for (World w : Bukkit.getWorlds()) {
            Json.JsonObject rules = Json.obj();
            for (GameRule<?> rule : registry) {
                // registry 可能含實驗性／feature flag 未啟用的規則（例如 max_minecart_speed），
                // 對該世界存取會丟 IllegalArgumentException —— 逐一保護，跳過即可。
                try {
                    rules.put(rule.getKey().getKey(), w.getGameRuleValue(rule));
                } catch (IllegalArgumentException ignored) {
                    // 此世界不支援該 game rule
                }
            }
            worlds.add(Json.obj()
                    .put("world", w.getName())
                    .put("gameRules", rules));
        }
        return Json.obj().put("count", Bukkit.getWorlds().size()).put("worlds", worlds);
    }
}
