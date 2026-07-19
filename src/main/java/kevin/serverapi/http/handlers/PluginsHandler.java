package kevin.serverapi.http.handlers;

import io.papermc.paper.plugin.configuration.PluginMeta;
import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * /api/plugins — 已安裝插件列表與其中繼資料。
 * 使用 Paper 的 PluginMeta（取代已棄用的 PluginDescriptionFile）。
 */
public class PluginsHandler extends ApiHandler {

    public PluginsHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object build(HttpExchange exchange) {
        Json.JsonArray arr = Json.arr();
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        for (Plugin p : plugins) {
            PluginMeta meta = p.getPluginMeta();
            Json.JsonArray authors = Json.arr();
            for (String a : meta.getAuthors()) authors.add(a);

            arr.add(Json.obj()
                    .put("name", meta.getName())
                    .put("version", meta.getVersion())
                    .put("enabled", p.isEnabled())
                    .put("description", meta.getDescription())
                    .put("website", meta.getWebsite())
                    .put("apiVersion", meta.getAPIVersion())
                    .put("authors", authors)
                    .put("depend", toArray(meta.getPluginDependencies()))
                    .put("softDepend", toArray(meta.getPluginSoftDependencies())));
        }
        return Json.obj().put("count", plugins.length).put("plugins", arr);
    }

    private static Json.JsonArray toArray(Iterable<String> values) {
        Json.JsonArray arr = Json.arr();
        for (String v : values) arr.add(v);
        return arr;
    }
}
