package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;

/**
 * /api/server — 伺服器基本公開資訊。
 * 依需求不含：最大玩家數、在線玩家、封鎖玩家、MOTD。
 */
public class ServerInfoHandler extends ApiHandler {

    public ServerInfoHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object build(HttpExchange exchange) {
        String ip = Bukkit.getIp();
        return Json.obj()
                .put("brand", Bukkit.getName())
                .put("maxPlayers", Bukkit.getMaxPlayers())
                // motd() 回傳 Component（getMotd() 已棄用）；序列化為純文字供網頁直接使用
                .put("motd", PlainTextComponentSerializer.plainText().serialize(Bukkit.motd()))
                .put("version", Bukkit.getVersion())
                .put("bukkitVersion", Bukkit.getBukkitVersion())
                .put("minecraftVersion", Bukkit.getMinecraftVersion())
                .put("port", Bukkit.getPort())
                .put("ip", ip == null || ip.isEmpty() ? null : ip)
                .put("onlineMode", Bukkit.getOnlineMode())
                .put("hardcore", Bukkit.isHardcore())
                .put("allowFlight", Bukkit.getAllowFlight())
                .put("allowNether", Bukkit.getAllowNether())
                .put("allowEnd", Bukkit.getAllowEnd())
                .put("defaultGameMode", Bukkit.getDefaultGameMode().name())
                .put("viewDistance", Bukkit.getViewDistance())
                .put("simulationDistance", Bukkit.getSimulationDistance())
                .put("spawnRadius", Bukkit.getSpawnRadius())
                .put("worldCount", Bukkit.getWorlds().size())
                .put("pluginCount", Bukkit.getPluginManager().getPlugins().length);
    }
}
