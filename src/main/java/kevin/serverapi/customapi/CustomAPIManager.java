package kevin.serverapi.customapi;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.lang.reflect.Array;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.api.CustomStation;
import kevin.serverapi.api.ServerApiRegistry;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.json.Json;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

/**
 * 第三方站點的協調層：接收第三方插件的註冊請求，但把「是否對外開放、如何加密」
 * 的決定權完全交給 ServerAPI 的伺服器管理員。
 *
 * <h2>安全模型（fail-closed）</h2>
 * 第一次見到某個站點時，只會在 {@code plugins/ServerAPI/custom/<name>.yml} 產生一份
 * <b>預設停用</b>的設定檔並提示伺服器管理員。第三方無法自行讓資料出現在公開 API 上，
 * 必須由伺服器管理員把 {@code enabled} 改成 true 並重載，路徑才會掛上。
 *
 * <h2>生命週期</h2>
 * <ul>
 *   <li>全新開機：ServerAPI 先啟用（HTTP 伺服器啟動），第三方隨後在自己的
 *       {@code onEnable} 註冊 → 此時伺服器已在跑，設定檔若為 enabled 就<b>即時掛上</b>。</li>
 *   <li>{@code /serverapi reload}：HTTP 伺服器整個重建，{@link #mountAll} 依記憶體中的
 *       註冊清單與最新設定檔重新掛載，因此伺服器管理員改完設定重載即生效。</li>
 * </ul>
 *
 * 註冊清單保存在記憶體，跨 ServerAPI 自身的重載仍在；第三方插件被停用時才會消失。
 */
public final class CustomAPIManager implements ServerApiRegistry {

    /** 名稱規則：小寫英數開頭，其後可含 {@code _ -}，長度上限 32。 */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    /** 保留名稱：內建站點與索引/文件，第三方不得佔用（避免辨識混淆）。 */
    private static final Set<String> RESERVED = Set.of(
            "status", "player", "server", "performance", "worlds", "gamerules",
            "entities", "spawnlimits", "punishments", "bans", "operators",
            "whitelist", "plugins", "placeholders", "network", "constants",
            "index", "docs", "custom");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ServerAPIPlugin plugin;

    /** 站點名稱 -> 註冊資料（記憶體真相，跨重載仍在）。 */
    private final Map<String, CustomStation> registrations = new ConcurrentHashMap<>();

    /** 目前實際對外提供的站點名稱。 */
    private final Set<String> served = ConcurrentHashMap.newKeySet();

    /** 快照站點的定期任務，供重載或取消註冊時清掉。 */
    private final Map<String, BukkitTask> refreshTasks = new ConcurrentHashMap<>();

    // 由 mountAll 於每次 startHttp 更新；即時註冊會沿用最近一次的參照。
    private volatile HttpServer server;
    private volatile String basePath = "/api";
    private volatile Map<String, String> registry = new LinkedHashMap<>();
    private volatile Set<String> protectedPaths = ConcurrentHashMap.newKeySet();

    public CustomAPIManager(ServerAPIPlugin plugin) {
        this.plugin = plugin;
    }

    // ───────────────────────────── 對外介面 ─────────────────────────────

    @Override
    public void register(CustomStation station) {
        String name = station.name();
        if (name == null || !NAME.matcher(name).matches()) {
            plugin.getLogger().warning(plugin.lang().get("console.custom.rejected-name", String.valueOf(name),
                    station.owner().getName()));
            throw new IllegalArgumentException("站點名稱不合法：" + name);
        }
        if (RESERVED.contains(name)) {
            plugin.getLogger().warning(plugin.lang().get("console.custom.rejected-reserved", name,
                    station.owner().getName()));
            throw new IllegalArgumentException("站點名稱與內建站點衝突：" + name);
        }
        CustomStation prev = registrations.get(name);
        if (prev != null && !prev.owner().getName().equals(station.owner().getName())) {
            plugin.getLogger().warning(plugin.lang().get("console.custom.rejected-taken", name,
                    prev.owner().getName(), station.owner().getName()));
            throw new IllegalArgumentException("站點名稱已被 " + prev.owner().getName() + " 註冊：" + name);
        }

        registrations.put(name, station);
        boolean fresh = ensureConfig(station);   // 首次會建立預設停用的設定檔
        applyMount(name);                         // 伺服器已在跑就即時套用

        String path = basePath + "/" + ApiHandler.API_VERSION + "/custom/" + name;
        if (fresh) {
            plugin.getLogger().info(plugin.lang().get("console.custom.registered-new",
                    station.owner().getName(), name, configFile(name).getPath()));
        } else {
            plugin.getLogger().info(plugin.lang().get("console.custom.registered",
                    station.owner().getName(), name));
        }
        if (served.contains(name)) {
            plugin.getLogger().info(plugin.lang().get("console.custom.serving", name, path));
        } else {
            plugin.getLogger().info(plugin.lang().get("console.custom.disabled", name, configFile(name).getName()));
        }
    }

    @Override
    public void unregister(String name) {
        if (name == null || registrations.remove(name) == null) return;
        unmount(name);   // 保留設定檔，伺服器管理員的開關與安全設定得以留存
        plugin.getLogger().info(plugin.lang().get("console.custom.unregistered", name));
    }

    @Override
    public boolean isServed(String name) {
        return served.contains(name);
    }

    // ─────────────────────────── 掛載 / 卸載 ───────────────────────────

    /**
     * 於 startHttp 末段呼叫：把記憶體中的所有註冊，依最新設定重新掛到（可能是新的）伺服器上。
     * 由主執行緒呼叫。
     */
    public void mountAll(HttpServer server, String basePath, Map<String, String> registry,
                         Set<String> protectedPaths) {
        // 舊伺服器已隨重載丟棄，其 context 一併消失；這裡只需清掉自己的排程與狀態
        for (BukkitTask t : refreshTasks.values()) t.cancel();
        refreshTasks.clear();
        served.clear();

        this.server = server;
        this.basePath = basePath;
        this.registry = registry;
        this.protectedPaths = protectedPaths;

        for (String name : registrations.keySet()) {
            applyMount(name);
        }
    }

    /** 讀設定檔，enabled 才把站點掛上。可於主執行緒重複呼叫（會先卸再掛）。 */
    private void applyMount(String name) {
        HttpServer srv = server;
        CustomStation station = registrations.get(name);
        if (srv == null || station == null) return;

        unmount(name);   // 冪等：重載或設定變更時先移除舊的

        YamlConfiguration cfg = loadConfig(name);
        if (!cfg.getBoolean("enabled", false)) return;   // fail-closed：預設不對外

        boolean requiresKey = cfg.getBoolean("require-key", true);
        Set<String> protectedFields = Set.copyOf(cfg.getStringList("protected-fields"));

        ApiHandler handler;
        if (station.mode() == CustomStation.Mode.CACHED) {
            CustomCachedStation cached = new CustomCachedStation(plugin, station.supplier());
            long seconds = Math.max(1L, cfg.getLong("snapshot-seconds", station.suggestedSeconds()));
            long ticks = seconds * 20L;
            // 首次 1 tick 後於主執行緒快照，之後每 ticks 更新
            refreshTasks.put(name,
                    Bukkit.getScheduler().runTaskTimer(plugin, cached::refresh, 1L, ticks));
            handler = cached;
        } else {
            handler = new CustomAsyncStation(plugin, station.supplier());
        }
        handler.configureAuth(name, requiresKey, protectedFields);

        String sub = "/custom/" + name;
        String versioned = basePath + "/" + ApiHandler.API_VERSION + sub;
        srv.createContext(versioned, (HttpHandler) handler);
        srv.createContext(basePath + sub, handler);
        if (requiresKey) protectedPaths.add(versioned);
        registry.put(versioned, describe(station));
        served.add(name);
    }

    /** 移除站點的 context 與排程（若有）；設定檔不動。 */
    private void unmount(String name) {
        HttpServer srv = server;
        BukkitTask task = refreshTasks.remove(name);
        if (task != null) task.cancel();
        served.remove(name);
        if (srv == null) return;
        String sub = "/custom/" + name;
        String versioned = basePath + "/" + ApiHandler.API_VERSION + sub;
        try {
            srv.removeContext(versioned);
            srv.removeContext(basePath + sub);
        } catch (IllegalArgumentException ignored) {
            // context 不存在（從未掛上），視為已卸載
        }
        protectedPaths.remove(versioned);
        registry.remove(versioned);
    }

    private String describe(CustomStation station) {
        String d = station.description();
        return (d == null || d.isBlank())
                ? plugin.lang().get("console.custom.index-desc", station.owner().getName())
                : d;
    }

    // ─────────────────────────── 設定檔管理 ───────────────────────────

    private File customDir() {
        return new File(plugin.getDataFolder(), "custom");
    }

    private File configFile(String name) {
        return new File(customDir(), name + ".yml");
    }

    /**
     * 確保設定檔存在。首次建立時寫入 fail-closed 預設並附上說明註解。
     *
     * @return 是否為本次新建（供控制台區分「首次偵測」與「重新註冊」）
     */
    private boolean ensureConfig(CustomStation station) {
        File file = configFile(station.name());
        boolean fresh = !file.exists();

        YamlConfiguration cfg = fresh ? new YamlConfiguration() : loadConfig(station.name());

        // 唯讀資訊：每次註冊都重寫，讓伺服器管理員看到最新來源與路徑
        cfg.set("plugin", station.owner().getName());
        cfg.set("path", basePath + "/" + ApiHandler.API_VERSION + "/custom/" + station.name());
        cfg.set("mode", station.mode().name().toLowerCase());
        setComment(cfg, "mode", plugin.lang().get("custom.file.mode"));
        boolean cached = station.mode() == CustomStation.Mode.CACHED;
        if (fresh) {
            cfg.set("registered-at", ZonedDateTime.now().format(STAMP));
            // 伺服器管理員可調整的安全與開關，一律從最保守的值開始
            cfg.set("enabled", false);
            cfg.set("require-key", true);
            cfg.set("protected-fields", List.of());
            // snapshot-seconds 只對 cached 模式有意義；async 站點不寫這欄，免得誤導
            if (cached) {
                cfg.set("snapshot-seconds", station.suggestedSeconds());
            }

            // 註解跟著插件語言走；header 是多行字串，以換行拆成逐行
            cfg.options().setHeader(List.of(plugin.lang().get("custom.file.header").split("\n")));
            setComment(cfg, "enabled", plugin.lang().get("custom.file.enabled"));
            setComment(cfg, "require-key", plugin.lang().get("custom.file.require-key"));
            setComment(cfg, "protected-fields", plugin.lang().get("custom.file.protected-fields"));
            if (cached) {
                setComment(cfg, "snapshot-seconds", plugin.lang().get("custom.file.snapshot-seconds"));
            }
        }

        try {
            customDir().mkdirs();
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning(plugin.lang().get("console.custom.config-failed",
                    file.getPath(), String.valueOf(e)));
        }
        return fresh;
    }

    private YamlConfiguration loadConfig(String name) {
        File file = configFile(name);
        if (!file.exists()) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(file);
    }

    /** 相容處理：舊版 API 若無 setComments 也不致噴錯。 */
    private void setComment(YamlConfiguration cfg, String path, String comment) {
        try {
            cfg.setComments(path, List.of(comment));
        } catch (Throwable ignored) {
        }
    }

    // ─────────────────────── 供應者輸出正規化 ───────────────────────

    /**
     * 把第三方回傳的純 Java 轉成 {@link Json} 認得的節點。
     * 支援 Map、List、任意陣列（含基本型別陣列）、數字、布林、字串與 null；
     * 其餘型別退回 {@code toString()}，確保永遠能序列化、不會讓站點壞掉。
     */
    public static Object normalize(Object v) {
        if (v == null || v instanceof Json.JsonValue
                || v instanceof Number || v instanceof Boolean || v instanceof CharSequence) {
            return v;
        }
        if (v instanceof Map<?, ?> map) {
            Json.JsonObject o = Json.obj();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                o.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return o;
        }
        if (v instanceof Iterable<?> it) {
            Json.JsonArray a = Json.arr();
            for (Object x : it) a.add(normalize(x));
            return a;
        }
        if (v.getClass().isArray()) {
            Json.JsonArray a = Json.arr();
            int n = Array.getLength(v);
            for (int i = 0; i < n; i++) a.add(normalize(Array.get(v, i)));
            return a;
        }
        return v.toString();
    }
}
