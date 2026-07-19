package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;

/**
 * /api/performance — TPS、MSPT、記憶體、執行緒與 JVM 效能資料。
 */
public class PerformanceHandler extends ApiHandler {

    public PerformanceHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object build(HttpExchange exchange) {
        double[] tps = Bukkit.getTPS();
        Json.JsonObject tpsObj = Json.obj();
        if (tps.length > 0) tpsObj.put("1m", round(tps[0]));
        if (tps.length > 1) tpsObj.put("5m", round(tps[1]));
        if (tps.length > 2) tpsObj.put("15m", round(tps[2]));

        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;

        Json.JsonObject memory = Json.obj()
                .put("usedBytes", used)
                .put("freeBytes", free)
                .put("totalBytes", total)
                .put("maxBytes", max)
                .put("usedMB", used / (1024 * 1024))
                .put("maxMB", max / (1024 * 1024));

        Json.JsonObject jvm = Json.obj()
                .put("javaVersion", System.getProperty("java.version"))
                .put("javaVendor", System.getProperty("java.vendor"))
                .put("osName", System.getProperty("os.name"))
                .put("osArch", System.getProperty("os.arch"))
                .put("availableProcessors", rt.availableProcessors())
                .put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime())
                .put("threadCount", Thread.activeCount());

        return Json.obj()
                .put("tps", tpsObj)
                .put("averageTickTimeMs", round(Bukkit.getAverageTickTime()))
                .put("memory", memory)
                .put("jvm", jvm);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
