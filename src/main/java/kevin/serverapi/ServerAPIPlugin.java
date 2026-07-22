package kevin.serverapi;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import kevin.serverapi.customapi.CustomAPIManager;
import kevin.serverapi.http.handlers.BansHandler;
import kevin.serverapi.http.handlers.ConstantsHandler;
import kevin.serverapi.http.handlers.DocsHandler;
import kevin.serverapi.http.handlers.EntitiesHandler;
import kevin.serverapi.http.handlers.GameRulesHandler;
import kevin.serverapi.http.handlers.IndexHandler;
import kevin.serverapi.http.handlers.OperatorsHandler;
import kevin.serverapi.http.handlers.PerformanceHandler;
import kevin.serverapi.http.handlers.PlaceholderHandler;
import kevin.serverapi.http.handlers.PlayerHandler;
import kevin.serverapi.http.handlers.PluginsHandler;
import kevin.serverapi.http.handlers.PunishmentsHandler;
import kevin.serverapi.http.handlers.ServerInfoHandler;
import kevin.serverapi.http.handlers.SpawnLimitsHandler;
import kevin.serverapi.http.handlers.StatusHandler;
import kevin.serverapi.http.handlers.WhitelistHandler;
import kevin.serverapi.http.handlers.WorldsHandler;
import kevin.serverapi.discord.DiscordBridge;
import kevin.serverapi.history.HistoryService;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.lang.Lang;
import kevin.serverapi.http.CachedApiHandler;
import kevin.serverapi.http.ClientIpResolver;
import kevin.serverapi.http.RateLimiter;
import kevin.serverapi.http.handlers.NetworkHandler;
import kevin.serverapi.network.NetworkService;
import kevin.serverapi.punishment.PunishmentReconciler;
import kevin.serverapi.stats.StatCacheService;
import kevin.serverapi.storage.RedisCache;
import kevin.serverapi.storage.RedisKeys;
import kevin.serverapi.storage.SqlDatabase;
import kevin.serverapi.listener.PunishmentLogger;
import kevin.serverapi.storage.SqlHistoryStore;
import kevin.serverapi.storage.SqlPunishmentLog;
import kevin.serverapi.storage.SqlStatCache;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimpleBarChart;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerAPIPlugin extends JavaPlugin {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String SEP = "─".repeat(74);

    /** 教學文檔。主控台與指令都會附上，讓服主不必回頭找連結。 */
    private static final String DOCS_URL = "https://cloudxact.com/wiki/serverapi/";
    private static final String GITHUB_URL = "https://github.com/Kevin28576/ServerAPI";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/paper-serverapi";
    private static final String DISCORD_URL = "https://discord.gg/JyQPpVrhsk";

    private static ServerAPIPlugin INSTANCE;

    /**
     * bStats 專案編號，於 bstats.org 註冊插件後取得。填錯會把資料送到別人的圖表上，
     * 因此無效時直接跳過統計。
     *
     * 刻意不加 final：宣告成 static final 的字面常數時，{@code id <= 0} 會成為
     * 編譯期常數，javac 會把 setupMetrics() 後半段當成不可達程式碼整段消除，
     * 連 bStats 的類別參照都不會留下，shade 的重新命名也就無從套用。
     */
    private static int BSTATS_PLUGIN_ID = 32748;

    private HttpServer httpServer;
    private ExecutorService executor;
    /** 每個快照站點各自的定期任務（各有獨立間隔），外加 onlineCount 更新任務。 */
    private final List<BukkitTask> refreshTasks = new ArrayList<>();
    private BukkitTask historyTask;
    private CustomAPIManager customAPIManager;
    private final AtomicBoolean reloading = new AtomicBoolean(false);

    private volatile HistoryService historyService;   // 可為 null（未啟用）；跨執行緒讀取
    private volatile int onlineCount;
    private String historyInfo;
    /** API 根路徑（供 register 組出版本化與別名路徑）。 */
    private String basePath = "/api";
    /** 需要金鑰的站點路徑，供索引隱藏之用。 */
    private final Set<String> protectedPaths = new java.util.HashSet<>();
    private long enabledAt;
    private int stationCount;
    /** 已啟用的站點名稱，供 bStats 統計哪些端點實際被使用。 */
    private volatile Set<String> enabledStationNames = Set.of();
    /**
     * 累計請求數。bStats 每次取樣後歸零，圖表呈現的是該區間的請求量 ——
     * 這是唯一能區分「裝了」與「真的在用」的指標。
     */
    private final java.util.concurrent.atomic.AtomicLong requestCount =
            new java.util.concurrent.atomic.AtomicLong();

    /** 共用儲存層：資料庫連線池與 Redis 由插件統一管理生命週期。 */
    private SqlDatabase database;
    /** volatile：非同步的舊版鍵清理與心跳任務會讀取此欄位。 */
    private volatile RedisCache redis;
    private volatile StatCacheService statCache;
    private String statCacheInfo;

    /** 處罰日誌：記錄 Minecraft／CMI 不保存的事件（踢出）。 */
    private SqlPunishmentLog punishmentLog;
    private volatile PunishmentLogger punishmentLogger;
    private volatile PunishmentReconciler reconciler;

    /** 多服支援：鍵名規則、本服識別、跨服彙整。 */
    private RedisKeys redisKeys;
    private String serverId = "";
    private volatile NetworkService network;
    /** 主執行緒定期更新的線上玩家 UUID 快照（供心跳非同步使用）。 */
    private volatile List<String> onlineUuids = List.of();

    private boolean authEnabled;
    private boolean allowQueryKey;
    private boolean hideProtected;
    /** HTML 展示頁開關；關閉後對應查詢參數視為不存在，JSON 端點不受影響。 */
    private boolean punishmentsHtml;
    private boolean placeholdersList;
    private volatile RateLimiter rateLimiter;
    private boolean rateLimitExemptAuthenticated;
    private volatile ClientIpResolver clientIpResolver;
    private String apiKey;
    private boolean debug;
    private final Lang lang = new Lang(this);

    @Override
    public void onEnable() {
        enabledAt = System.currentTimeMillis();
        saveDefaultConfig();
        setupLanguage();
        registerCommands();
        startHttp();
        setupMetrics();
        INSTANCE = this;
        customAPIManager = new CustomAPIManager();
    }

    /**
     * bStats 匿名統計。伺服器管理員可在 plugins/bStats/config.yml 全域關閉。
     * 統計失敗不影響插件運作，因此整段包在 try 裡。
     */
    private void setupMetrics() {
        if (BSTATS_PLUGIN_ID <= 0) return;
        try {
            Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);

            // ── 每台伺服器只有一個值：用圓餅圖看佔比 ──
            metrics.addCustomChart(new SimplePie("storage_type",
                    () -> getConfig().getString("storage.type", "sqlite")));
            metrics.addCustomChart(new SimplePie("language", () -> lang.code()));

            // 這裡刻意不統計任何防護措施的開關（驗證、限流、真實來源 IP）。
            // bStats 頁面是公開的：彙總過後雖然找不出「哪一台」沒設防，
            // 但「這個插件多少比例的使用者不設防」本身就是攻擊者評估
            // 值不值得針對本插件寫掃描器的依據，代價高於這點情報的價值。
            // 想知道服主有沒有開驗證，看啟動警告的回報就夠了。

            // ── 一台伺服器可同時符合多項：用長條圖看「各項各有多少服採用」 ──
            //
            // 這裡刻意不用進階圓餅圖。圓餅圖的前提是各部分構成一個整體，
            // 但這些項目彼此獨立、且一台伺服器會同時符合多項 ——
            // 16 個端點畫成餅圖就是 16 片幾乎等大的扇形，看不出差異，
            // 「status 佔所有端點的 6.4%」這句話本身也沒有意義。
            // 想知道的是「每項各有多少伺服器採用」，那是排名比較，長條圖才讀得出來。
            metrics.addCustomChart(new SimpleBarChart("enabled_stations", () -> {
                Map<String, Integer> map = new LinkedHashMap<>();
                for (String name : enabledStationNames) map.put(name, 1);
                return map;
            }));
            metrics.addCustomChart(new SimpleBarChart("integrations", () -> {
                Map<String, Integer> map = new LinkedHashMap<>();
                for (String name : new String[]{"LuckPerms", "CMI", "Vault",
                        "DiscordSRV", "PlaceholderAPI"}) {
                    var p = Bukkit.getPluginManager().getPlugin(name);
                    if (p != null && p.isEnabled()) map.put(name, 1);
                }
                if (map.isEmpty()) map.put("None", 1);
                return map;
            }));
            metrics.addCustomChart(new SimpleBarChart("optional_features", () -> {
                Map<String, Integer> map = new LinkedHashMap<>();
                // 同上：限流與真實來源 IP 屬於防護措施，一併不統計
                if (redis != null) map.put("Redis", 1);
                if (network != null && network.isEnabled()) map.put("Multi-server", 1);
                if (getConfig().getBoolean("web.docs", false)) map.put("Docs page", 1);
                if (punishmentsHtml) map.put("Punishments page", 1);
                if (placeholdersList) map.put("PlaceholderAPI page", 1);
                if (getConfig().getBoolean("punishment-log.enabled", true)) {
                    map.put("Punishment log", 1);
                }
                if (map.isEmpty()) map.put("None", 1);
                return map;
            }));

            // ── 唯一隨時間變化的量：折線圖 ──
            // 取樣後歸零，圖上呈現的是該區間的請求數。安裝量看預設圖表就好，
            // 這張回答的是完全不同的問題：裝了之後到底有沒有人在打。
            metrics.addCustomChart(new SingleLineChart("api_requests",
                    () -> (int) Math.min(Integer.MAX_VALUE, requestCount.getAndSet(0))));
        } catch (Throwable t) {
            getLogger().warning(lang.get("console.warn.metrics-failed", t));
        }
    }

    @Override
    public void onDisable() {
        printDisableBanner();
    }

    // ---- HTTP 生命週期 ----

    private void startHttp() {
        long t0 = System.currentTimeMillis();
        FileConfiguration cfg = getConfig();
        String bind = cfg.getString("http.bind-address", "0.0.0.0");
        int port = cfg.getInt("http.port", 8080);
        basePath = normalizeBase(cfg.getString("http.base-path", "/api"));
        int threads = Math.max(1, cfg.getInt("http.threads", 8));

        this.authEnabled = cfg.getBoolean("auth.enabled", false);
        this.apiKey = cfg.getString("auth.api-key", "");
        this.allowQueryKey = cfg.getBoolean("auth.allow-query-key", true);
        this.hideProtected = cfg.getBoolean("auth.hide-protected", true);
        this.punishmentsHtml = cfg.getBoolean("web.punishments", false);
        this.placeholdersList = cfg.getBoolean("web.placeholders", false);
        setupRateLimiter(cfg);
        this.debug = cfg.getBoolean("debug", false);

        Map<String, String> enabledStations = new LinkedHashMap<>();
        protectedPaths.clear();
        refreshTasks.clear();

        try {
            httpServer = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (IOException e) {
            printStartupFailure(bind, port, e);
            return;
        }

        // 共用儲存層：歷史資料服務 + 玩家統計快取（SQLite/MySQL/MariaDB + 選用 Redis）
        setupStorage(cfg);

        // 多服彙整：以 Redis 心跳互相發現（需總開關 + Redis）
        boolean networkEnabled = cfg.getBoolean("network.enabled", false);
        network = new NetworkService(redis, redisKeys,
                Math.max(2, cfg.getInt("network.heartbeat-seconds", 10)) * 3, networkEnabled);

        if (networkEnabled && NetworkService.GLOBAL.equals(serverId)) {
            getLogger().warning(lang.get("console.warn.network-global"));
        }
        if (networkEnabled && redis == null) {
            getLogger().warning(lang.get("console.warn.network-no-redis"));
        }

        // 各快照站點的獨立更新間隔（tick，20 tick = 1 秒）
        long statusTicks = Math.max(1L, cfg.getLong("cache.status-ticks", 100L));       // 5 秒
        long worldsTicks = Math.max(1L, cfg.getLong("cache.worlds-ticks", 12000L));     // 10 分鐘
        long gameruleTicks = Math.max(1L, cfg.getLong("cache.gamerules-ticks", 12000L)); // 10 分鐘
        long entitiesTicks = Math.max(1L, cfg.getLong("cache.entities-ticks", 400L));   // 20 秒
        long spawnTicks = Math.max(1L, cfg.getLong("cache.spawnlimits-ticks", 12000L)); // 10 分鐘

        // 各分類站點（同一埠、不同路徑）—— 快照站點各自排程，直算站點即時處理
        if (cfg.getBoolean("stations.status", true)) {
            registerCached("/status", new StatusHandler(this, historyService), enabledStations, lang.get("station.status"), statusTicks);
        }
        if (cfg.getBoolean("stations.server", true)) {
            register("/server", new ServerInfoHandler(this), enabledStations, lang.get("station.server"));
        }
        if (cfg.getBoolean("stations.performance", true)) {
            register("/performance", new PerformanceHandler(this), enabledStations, lang.get("station.performance"));
        }
        if (cfg.getBoolean("stations.worlds", true)) {
            registerCached("/worlds", new WorldsHandler(this), enabledStations, lang.get("station.worlds"), worldsTicks);
        }
        if (cfg.getBoolean("stations.gamerules", true)) {
            registerCached("/gamerules", new GameRulesHandler(this), enabledStations, lang.get("station.gamerules"), gameruleTicks);
        }
        if (cfg.getBoolean("stations.entities", true)) {
            registerCached("/entities", new EntitiesHandler(this), enabledStations, lang.get("station.entities"), entitiesTicks);
        }
        if (cfg.getBoolean("stations.spawnlimits", true)) {
            registerCached("/spawnlimits", new SpawnLimitsHandler(this), enabledStations, lang.get("station.spawnlimits"), spawnTicks);
        }
        if (cfg.getBoolean("stations.plugins", true)) {
            register("/plugins", new PluginsHandler(this), enabledStations, lang.get("station.plugins"));
        }
        if (cfg.getBoolean("stations.whitelist", true)) {
            register("/whitelist", new WhitelistHandler(this), enabledStations, lang.get("station.whitelist"));
        }
        if (cfg.getBoolean("stations.player", true)) {
            register("/player", new PlayerHandler(this, statCache), enabledStations, lang.get("station.player"));
        }
        if (cfg.getBoolean("stations.network", true)) {
            register("/network", new NetworkHandler(this, network), enabledStations, lang.get("station.network"));
        }
        if (cfg.getBoolean("stations.placeholders", true)) {
            register("/placeholders", new PlaceholderHandler(this), enabledStations, lang.get("station.placeholders"));
        }
        if (cfg.getBoolean("stations.punishments", true)) {
            register("/punishments", new PunishmentsHandler(this, punishmentLog), enabledStations,
                    lang.get("station.punishments"));
        }
        if (cfg.getBoolean("stations.bans", true)) {
            register("/bans", new BansHandler(this), enabledStations, lang.get("station.bans"));
        }
        if (cfg.getBoolean("stations.operators", true)) {
            register("/operators", new OperatorsHandler(this), enabledStations, lang.get("station.operators"));
        }
        if (cfg.getBoolean("stations.constants", true)) {
            register("/constants", new ConstantsHandler(this), enabledStations, lang.get("station.constants"));
        }

        // API 文件：列出實際註冊的端點，可直接在頁面上試用。
        // 預設關閉 —— 文件會揭露所有端點與參數，公開環境不該無條件開放。
        if (cfg.getBoolean("web.docs", false)) {
            DocsHandler docs = new DocsHandler(this, enabledStations, protectedPaths, basePath);
            docs.configureAuth("docs", cfg.getBoolean("auth.require-key.docs", false), Set.of());
            httpServer.createContext(basePath + "/" + ApiHandler.API_VERSION + "/docs", docs);
            httpServer.createContext(basePath + "/docs", docs);
        }

        // 站點索引：版本化路徑與別名各掛一份（預設公開，可由 auth.require-key.index 調整）
        IndexHandler index = new IndexHandler(this, enabledStations, protectedPaths, basePath);
        index.configureAuth("index",
                cfg.getBoolean("auth.require-key.index", false),
                Set.copyOf(cfg.getStringList("auth.protected-fields.index")));
        if (cfg.getBoolean("auth.require-key.index", false)) {
            protectedPaths.add(basePath + "/" + ApiHandler.API_VERSION);
        }
        httpServer.createContext(basePath + "/" + ApiHandler.API_VERSION, index);
        httpServer.createContext(basePath, index);

        warnOnAuthConfig(enabledStations);

        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "ServerAPI-HTTP");
            t.setDaemon(true);
            return t;
        });
        httpServer.setExecutor(executor);
        httpServer.start();

        // 線上快照：供歷史取樣與跨服心跳使用（每 5 秒於主執行緒更新 volatile）
        refreshTasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
            List<String> uuids = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                uuids.add(online.getUniqueId().toString());
            }
            onlineUuids = List.copyOf(uuids);
            onlineCount = uuids.size();
        }, 1L, 100L));

        // 跨服心跳：非同步發布本服狀態至 Redis，供 /api/network 彙整
        if (network != null && network.isActive()) {
            long heartbeatTicks = Math.max(2L, cfg.getLong("network.heartbeat-seconds", 10L)) * 20L;
            refreshTasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                NetworkService net = network;
                if (net != null) net.publish(onlineUuids, DiscordBridge.getMemberCount());
            }, 40L, heartbeatTicks));
        }

        // 歷史取樣：非同步排程，所有 DB/Redis I/O 都不碰主執行緒
        if (historyService != null) {
            long intervalMinutes = Math.max(1L, cfg.getLong("history.update-interval-minutes", 10L));
            long periodTicks = intervalMinutes * 60L * 20L;
            historyTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                // 先取局部參考，避免與重載（將欄位設 null）之間的 TOCTOU 競態
                HistoryService svc = historyService;
                if (svc != null) {
                    svc.sample(onlineCount, DiscordBridge.getMemberCount());
                }
            }, 200L, periodTicks);
        }

        // 處罰對帳：主執行緒擷取當前狀態 → 非同步比對資料庫並寫入差異。
        // 封鎖名單與 CMI 只有「當前狀態」，靠這個才能把解封／解除也記成歷史。
        if (reconciler != null) {
            long reconcileTicks = Math.max(100L, cfg.getLong("punishment-log.reconcile-seconds", 30L) * 20L);
            refreshTasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> {
                PunishmentReconciler rec = reconciler;
                if (rec == null) return;
                PunishmentReconciler.Snapshot snap = rec.snapshot();   // 主執行緒，只讀狀態
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    rec.reconcile(snap);                                // 非同步，查資料庫比對
                    rec.flush();
                });
            }, 100L, reconcileTicks));
        }

        // 處罰日誌：非同步批次寫入（事件只入記憶體佇列，主執行緒不做 I/O）
        if (punishmentLogger != null) {
            long flushTicks = Math.max(20L, cfg.getLong("punishment-log.flush-seconds", 5L) * 20L);
            refreshTasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                PunishmentLogger pl = punishmentLogger;
                if (pl != null) pl.flush();
            }, flushTicks, flushTicks));

            // 定期清除逾期日誌。retention-days <= 0 表示永久保存，此時完全不排程清理任務。
            long retentionDays = cfg.getLong("punishment-log.retention-days", 90L);
            if (retentionDays > 0) {
                refreshTasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                    SqlPunishmentLog store = punishmentLog;
                    if (store == null) return;
                    try {
                        store.purgeOlderThan(System.currentTimeMillis() - retentionDays * 86_400_000L);
                    } catch (Exception e) {
                        getLogger().warning(lang.get("console.warn.punishment-write-failed", e));
                    }
                }, 12000L, 72000L));
            }
        }

        // 清理閒置的速率限制桶（非同步，每 5 分鐘）
        if (rateLimiter != null) {
            refreshTasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                RateLimiter rl = rateLimiter;
                if (rl != null) rl.evictStale(300_000);
            }, 6000L, 6000L));
        }

        // 定期清理資料庫中已過期的統計快取（非同步，每 10 分鐘）
        if (statCache != null) {
            refreshTasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                StatCacheService sc = statCache;
                if (sc != null) sc.purgeExpired();
            }, 12000L, 12000L));
        }

        stationCount = enabledStations.size();
        // 只留站點名，不含路徑前綴，圖表標籤才讀得懂
        enabledStationNames = enabledStations.keySet().stream()
                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        printEnableBanner(bind, port, basePath, enabledStations.size(), System.currentTimeMillis() - t0);
    }

    /**
     * 建立共用儲存層：資料庫連線池 + Redis，再據此組出歷史服務與統計快取。
     * 兩個服務共用同一連線池，避免 SQLite 多池互鎖。
     */
    private void setupStorage(FileConfiguration cfg) {
        historyService = null;
        statCache = null;
        historyInfo = null;
        statCacheInfo = null;

        boolean historyEnabled = cfg.getBoolean("history.enabled", true);
        boolean statCacheEnabled = cfg.getBoolean("player-stat-cache.enabled", true);
        if (!historyEnabled && !statCacheEnabled) return;

        String prefix = cfg.getString("storage.table-prefix", "serverapi_");
        String type = cfg.getString("storage.type", "sqlite").toLowerCase();

        // 多服識別：鍵名採 {namespace}:{cluster}:{server}:{type} 三冒號寫法。
        // server-id 為 global（或留空）時視為「未指定」，對應鍵名中的空段，
        // 因此單機預設仍是 serverapi:::history；設定專屬 ID 後才分流。
        // 兩種表示法刻意分開：
        //   serverId  —— 資料庫欄位與顯示用，未指定時為可讀的 "global"
        //   keySegment —— Redis 鍵名用，未指定時留空以維持三冒號格式
        String configured = cfg.getString("network.server-id", NetworkService.GLOBAL);
        boolean global = isGlobal(configured);
        serverId = global ? NetworkService.GLOBAL : configured.trim();
        String keySegment = global ? "" : serverId;
        redisKeys = new RedisKeys(
                cfg.getString("redis.namespace", "serverapi"),
                cfg.getString("network.cluster-id", ""),
                keySegment);

        // Redis（歷史／統計快取／跨服心跳共用；未設定則為 null）
        boolean redisEnabled = cfg.getBoolean("redis.enabled", false);
        if (redisEnabled) {
            redis = new RedisCache(
                    cfg.getString("redis.host", "localhost"),
                    cfg.getInt("redis.port", 6379),
                    cfg.getString("redis.password", ""),
                    cfg.getInt("redis.database", 0));
        }

        // 資料庫連線池
        try {
            SqlDatabase.Settings settings;
            switch (type) {
                case "mysql" -> settings = sqlSettings(cfg,
                        "jdbc:mysql://%s:%d/%s?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true",
                        "com.mysql.cj.jdbc.Driver");
                case "mariadb" -> settings = sqlSettings(cfg,
                        "jdbc:mariadb://%s:%d/%s", "org.mariadb.jdbc.Driver");
                default -> {
                    File dbFile = new File(getDataFolder(), cfg.getString("storage.sqlite.file", "history.db"));
                    getDataFolder().mkdirs();
                    settings = new SqlDatabase.Settings(
                            "jdbc:sqlite:" + dbFile.getAbsolutePath(), "org.sqlite.JDBC", null, null, 1);
                    type = "sqlite";
                }
            }
            database = new SqlDatabase(settings);
            database.init();
        } catch (Throwable t) {
            getLogger().severe(lang.get("console.warn.storage-failed", t));
            database = null;
        }

        // Redis 鍵的 TTL 設為取樣間隔的 3 倍：伺服器運作中每次取樣都會續期，
        // 一旦停止（或 server-id 改名）便自動過期，不會留下永不過期的孤兒鍵。
        long intervalMinutes = Math.max(1L, cfg.getLong("history.update-interval-minutes", 10L));
        int redisTtl = (int) Math.min(Integer.MAX_VALUE, intervalMinutes * 60L * 3L);

        // 舊版（未帶 TTL）遺留鍵的自我修復
        scheduleLegacyKeyCleanup(redisTtl);

        // 歷史資料服務
        if (historyEnabled && database != null) {
            try {
                long retentionMs = Math.max(1L, cfg.getLong("history.retention-hours", 24L)) * 3600_000L;
                HistoryService service = new HistoryService(
                        new SqlHistoryStore(database, prefix + "history", serverId),
                        redis, redisKeys.key("history"), redisTtl, retentionMs, getLogger(), lang);
                service.init();
                historyService = service;
                historyInfo = type + (redisEnabled ? " + Redis" : "");
            } catch (Throwable t) {
                getLogger().severe(lang.get("console.warn.history-service-failed", t));
                historyService = null;
            }
        }

        // 處罰日誌：記錄 Minecraft／CMI 不保存的事件（踢出）
        if (cfg.getBoolean("punishment-log.enabled", true) && database != null) {
            try {
                punishmentLog = new SqlPunishmentLog(database, prefix + "punishment_log", serverId);
                punishmentLog.init();

                Set<String> causes = Set.copyOf(cfg.getStringList("punishment-log.kick-causes"));
                if (causes.isEmpty()) causes = Set.of("KICK_COMMAND", "PLUGIN");
                punishmentLogger = new PunishmentLogger(punishmentLog, causes,
                        cfg.getBoolean("punishment-log.skip-kick-when-banned", true), getLogger(), lang);
                Bukkit.getPluginManager().registerEvents(punishmentLogger, this);
                reconciler = new PunishmentReconciler(punishmentLog, getLogger(), lang);
            } catch (Throwable t) {
                getLogger().severe(lang.get("console.warn.punishment-log-failed", t));
                punishmentLog = null;
                punishmentLogger = null;
                reconciler = null;
            }
        }

        // 玩家統計快取（離線玩家的 detailed 查詢；資料存 Redis／資料庫，不佔記憶體）
        if (statCacheEnabled && (database != null || redis != null)) {
            try {
                long ttlMs = Math.max(1L, cfg.getLong("player-stat-cache.ttl-seconds", 60L)) * 1000L;
                SqlStatCache store = database != null
                        ? new SqlStatCache(database, prefix + "player_stat", serverId) : null;
                StatCacheService service = new StatCacheService(store, redis, redisKeys, ttlMs, getLogger(), lang);
                service.init();
                statCache = service;
                statCacheInfo = (redis != null ? "Redis" : type) + " (TTL " + (ttlMs / 1000) + "s)";
            } catch (Throwable t) {
                getLogger().severe(lang.get("console.warn.stat-cache-failed", t));
                statCache = null;
            }
        }
    }

    /**
     * 自我修復：為舊版遺留、未設定過期時間的 Redis 鍵補上 TTL。
     *
     * 判斷依據：本版所有鍵一律帶 TTL，因此「本 namespace 內、TTL 為 -1（無過期時間）」
     * 必定是舊版寫入的殘留（例如 server-id 改名後留下的 history 鍵）。
     * 採補 TTL 而非刪除，較為保守；資料真實來源在 SQL，過期後查詢會自動重建。
     *
     * 於非同步執行緒執行，不增加啟動時間；冪等，重複啟動無副作用。
     */
    private void scheduleLegacyKeyCleanup(int ttlSeconds) {
        if (redis == null || redisKeys == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            RedisCache r = redis;
            if (r == null) return;
            int repaired = 0;
            for (String key : r.scanKeys(redisKeys.namespacePattern())) {
                if (r.ttl(key) == -1L && r.expire(key, ttlSeconds)) {
                    repaired++;
                }
            }
            if (repaired > 0) {
                getLogger().info(lang.get("console.warn.legacy-keys-fixed", repaired));
            }
        });
    }

    /** server-id 是否為「未指定」（空字串或 global，語意同 LuckPerms）。 */
    private static boolean isGlobal(String serverId) {
        return serverId == null || serverId.isBlank() || serverId.trim().equalsIgnoreCase(NetworkService.GLOBAL);
    }

    private SqlDatabase.Settings sqlSettings(FileConfiguration cfg, String urlFormat, String driver) {
        String url = String.format(urlFormat,
                cfg.getString("storage.sql.host", "localhost"),
                cfg.getInt("storage.sql.port", 3306),
                cfg.getString("storage.sql.database", "serverapi"));
        return new SqlDatabase.Settings(url, driver,
                cfg.getString("storage.sql.username", "root"),
                cfg.getString("storage.sql.password", ""),
                Math.max(1, cfg.getInt("storage.sql.pool-size", 5)));
    }

    /**
     * 註冊站點。同一 handler 會掛在兩個路徑：
     *   /api/v1/{sub} —— 正式的版本化路徑（索引列出的即為此路徑）
     *   /api/{sub}    —— 別名，永遠指向最新版本
     * handler 以實際匹配到的 context 路徑解析子路徑，因此兩邊皆可正確運作。
     */
    private void register(String sub, HttpHandler handler, Map<String, String> registry, String desc) {
        String station = sub.startsWith("/") ? sub.substring(1) : sub;

        // 兩層權限設定：
        //   auth.require-key.{station}      —— 此端點是否需要 X-API-Key（預設 true，fail-closed）
        //   auth.protected-fields.{station} —— 未驗證時要隱藏的欄位
        String versioned = basePath + "/" + ApiHandler.API_VERSION + sub;
        if (handler instanceof ApiHandler api) {
            boolean requiresKey = getConfig().getBoolean("auth.require-key." + station, true);
            Set<String> protectedFields = Set.copyOf(getConfig().getStringList("auth.protected-fields." + station));
            api.configureAuth(station, requiresKey, protectedFields);
            if (requiresKey) protectedPaths.add(versioned);
        }

        httpServer.createContext(versioned, handler);
        httpServer.createContext(basePath + sub, handler);
        registry.put(versioned, desc);
    }

    /** 註冊快照站點，並以其專屬間隔在主執行緒排程更新。 */
    private void registerCached(String sub, CachedApiHandler handler, Map<String, String> registry,
                                String desc, long intervalTicks) {
        register(sub, handler, registry, desc);
        // 首次於 1 tick 後在主執行緒執行，之後每 intervalTicks 更新一次
        refreshTasks.add(Bukkit.getScheduler().runTaskTimer(this, handler::refresh, 1L, intervalTicks));
    }

    /** 取消所有排程任務（快照 + 歷史取樣）。 */
    private int cancelTasks() {
        int count = refreshTasks.size();
        for (BukkitTask task : refreshTasks) {
            task.cancel();
        }
        refreshTasks.clear();
        if (historyTask != null) {
            historyTask.cancel();
            historyTask = null;
            count++;
        }
        return count;
    }

    /** 停止 HTTP 伺服器與其執行緒池（給進行中的請求 1 秒排空）。 */
    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(1);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** 關閉共用儲存層（資料庫連線池與 Redis）。 */
    private void closeStorage() {
        statCache = null;
        network = null;
        punishmentLogger = null;
        reconciler = null;
        punishmentLog = null;
        if (historyService != null) {
            historyService.close();
            historyService = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
        if (redis != null) {
            redis.close();
            redis = null;
        }
    }

    /** 重載用：安靜地停止全部元件。 */
    private void stopHttp() {
        cancelTasks();
        stopHttpServer();
        closeStorage();
    }

    private static String normalizeBase(String base) {
        if (base == null || base.isBlank()) return "/api";
        String b = base.trim();
        if (!b.startsWith("/")) b = "/" + b;
        while (b.length() > 1 && b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    // ---- 供 handler 使用 ----

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    /** 是否允許以網址參數 ?key= 帶入金鑰（除了 X-API-Key 標頭之外）。 */
    public boolean isQueryKeyAllowed() {
        return allowQueryKey;
    }

    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public boolean isRateLimitExemptAuthenticated() {
        return rateLimitExemptAuthenticated;
    }

    public ClientIpResolver getClientIpResolver() {
        return clientIpResolver;
    }

    /** 建立速率限制器。停用時為 null，請求路徑上完全不做檢查。 */
    private void setupRateLimiter(FileConfiguration cfg) {
        clientIpResolver = new ClientIpResolver(
                cfg.getBoolean("real-ip.enabled", false),
                cfg.getStringList("real-ip.headers"),
                cfg.getStringList("real-ip.trusted-proxies"),
                getLogger(), lang);
        rateLimitExemptAuthenticated = cfg.getBoolean("rate-limit.exempt-authenticated", true);
        if (!cfg.getBoolean("rate-limit.enabled", true)) {
            rateLimiter = null;
            return;
        }
        rateLimiter = new RateLimiter(
                cfg.getInt("rate-limit.per-ip-per-minute", 120),
                cfg.getInt("rate-limit.burst", 30),
                cfg.getInt("rate-limit.global-per-minute", 0),
                Set.copyOf(cfg.getStringList("rate-limit.exempt-ips")));
    }

    /**
     * 受保護的端點對未驗證者是否偽裝成不存在（回 404 而非 401）。
     * 避免回應本身洩漏「此端點存在且藏有值得保護的資料」。
     */
    public boolean isHideProtected() {
        return hideProtected;
    }

    /** /punishments?view=html 展示頁是否開放。 */
    public boolean isPunishmentsHtmlEnabled() {
        return punishmentsHtml;
    }

    /** /placeholders?list=true 預覽頁是否開放。 */
    public boolean isPlaceholdersListEnabled() {
        return placeholdersList;
    }

    /** 供 ApiHandler 於每次請求時累加。 */
    public void countRequest() {
        requestCount.incrementAndGet();
    }

    public boolean isDebug() {
        return debug;
    }

    /** 本服識別（未指定時為 global），供回應的 meta 區塊使用。 */
    public String getServerId() {
        return serverId;
    }

    /**
     * 在主執行緒執行並等待結果（含逾時上限）。
     * 僅供 PlaceholderAPI 解析使用——多數 expansion 會存取 Bukkit API，
     * 無法在非同步執行緒安全解析。其餘站點一律不得使用本方法。
     */
    public <T> T callSync(Callable<T> task, long timeoutMs) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }
        return Bukkit.getScheduler().callSyncMethod(this, task).get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    // ---- 主控台橫幅 ----

    private void console(String legacy) {
        Bukkit.getConsoleSender().sendMessage(LEGACY.deserialize(legacy));
    }

    private void printEnableBanner(String bind, int port, String basePath, int stations, long tookMs) {
        String ver = getPluginMeta().getVersion();

        console("&b" + SEP);
        printBanner();
        console("&b" + SEP);
        console(lang.get("console.integrating"));
        for (String name : new String[]{"CMI", "DiscordSRV", "LuckPerms", "PlaceholderAPI", "Vault"}) {
            printIntegration(name);
        }
        console(lang.get("console.stations-loaded", stations, tookMs));
        console(lang.get("console.listening", bind, port, basePath));
        console(lang.get("console.history-storage", historyInfo != null ? "&f" + historyInfo : lang.get("console.shutdown.history-disabled")));
        console(lang.get("console.stat-cache", statCacheInfo != null ? "&f" + statCacheInfo : lang.get("console.shutdown.history-disabled")));
        String serverLabel = NetworkService.GLOBAL.equals(serverId)
                ? lang.get("console.single-server") : "&f" + serverId;
        String clusterLabel = redisKeys.cluster().isEmpty() ? "" : " &7@ cluster &f" + redisKeys.cluster();
        String netState = network == null || !network.isEnabled() ? lang.get("console.network-off")
                : network.isActive() ? lang.get("console.network-on") : lang.get("console.network-no-redis");
        console(lang.get("console.server-identity", serverLabel + clusterLabel + netState));
        console(lang.get("console.language", lang.code()));
        console(lang.get("console.debug-mode", debug ? lang.get("console.enabled") : lang.get("console.not-enabled")));
        console(lang.get("console.docs", "&f" + DOCS_URL));
        console(lang.get("console.enabled-success", ver));
        console("&b" + SEP);
    }

    private void printBanner() {
        console("                                                                          \n" +
                " ____                                          ______  ____    ______     \n" +
                "/\\  _`\\                                       /\\  _  \\/\\  _`\\ /\\__  _\\    \n" +
                "\\ \\,\\L\\_\\     __   _ __   __  __     __   _ __\\ \\ \\L\\ \\ \\ \\L\\ \\/_/\\ \\/    \n" +
                " \\/_\\__ \\   /'__`\\/\\`'__\\/\\ \\/\\ \\  /'__`\\/\\`'__\\ \\  __ \\ \\ ,__/  \\ \\ \\    \n" +
                "   /\\ \\L\\ \\/\\  __/\\ \\ \\/ \\ \\ \\_/ |/\\  __/\\ \\ \\/ \\ \\ \\/\\ \\ \\ \\/    \\_\\ \\__ \n" +
                "   \\ `\\____\\ \\____\\\\ \\_\\  \\ \\___/ \\ \\____\\\\ \\_\\  \\ \\_\\ \\_\\ \\_\\    /\\_____\\\n" +
                "    \\/_____/\\/____/ \\/_/   \\/__/   \\/____/ \\/_/   \\/_/\\/_/\\/_/    \\/_____/\n" +
                "                                                                          \n" +
                "                                                                          ");
    }

    /**
     * HTTP 伺服器啟動失敗時的診斷輸出。
     * 直接丟出 Java 原始例外訊息（例如 "Address already in use: bind"）對管理員無用，
     * 此處判斷實際原因並提供可執行的解決步驟。
     */
    private void printStartupFailure(String bind, int port, IOException e) {
        String reason;
        String[] fixes;
        String message = e.getMessage() == null ? "" : e.getMessage();

        if (e instanceof java.net.BindException && message.toLowerCase().contains("in use")) {
            reason = lang.get("console.startup-failed.port-in-use", port);
            fixes = new String[]{
                    lang.get("console.startup-failed.port-fix-1", port + 1),
                    lang.get("console.startup-failed.port-fix-2"),
                    "  &8Windows: &fnetstat -ano | findstr :" + port,
                    "  &8Linux  : &flsof -i :" + port,
                    lang.get("console.startup-failed.port-fix-3")
            };
        } else if (e instanceof java.net.BindException) {
            reason = lang.get("console.startup-failed.bind-failed", bind);
            fixes = new String[]{
                    lang.get("console.startup-failed.bind-fix-1"),
                    lang.get("console.startup-failed.bind-fix-2")
            };
        } else if (port < 1024) {
            reason = lang.get("console.startup-failed.privileged-port", port);
            fixes = new String[]{
                    lang.get("console.startup-failed.privileged-fix-1"),
                    lang.get("console.startup-failed.privileged-fix-2")
            };
        } else {
            reason = lang.get("console.startup-failed.generic");
            fixes = new String[]{
                    lang.get("console.startup-failed.generic-fix-1", message),
                    lang.get("console.startup-failed.generic-fix-2")
            };
        }

        console("&c" + SEP);
        console(lang.get("console.startup-failed.title"));
        console("");
        console(lang.get("console.startup-failed.problem", reason));
        console(lang.get("console.startup-failed.address", bind, port));
        console("");
        console(lang.get("console.startup-failed.solutions"));
        // 以縮排開頭的是上一步的續行（指令範例），不佔編號，
        // 否則步驟會跳號——用陣列索引當編號就會印出 1、2、5。
        int step = 0;
        for (String fix : fixes) {
            String prefix = fix.startsWith("  ") ? "   " : "   &f" + (++step) + ".&7 ";
            console(prefix + "&7" + fix);
        }
        console("");
        console(lang.get("console.startup-failed.footer"));
        console("&c" + SEP);
    }

    /** 印出單一軟依賴插件的串接狀態。 */
    private void printIntegration(String pluginName) {
        var target = Bukkit.getPluginManager().getPlugin(pluginName);
        boolean hooked = target != null && target.isEnabled();
        console(hooked ? lang.get("console.hook-found", pluginName)
                : lang.get("console.hook-missing", pluginName));
    }

    /**
     * 關機流程：依序停止服務並「回報每一步」，同時在關服前做最後的資料保全。
     * 注意：此時 Bukkit 排程器已停止，所有收尾動作皆同步執行。
     */
    private void printDisableBanner() {
        long t0 = System.currentTimeMillis();
        console("&b" + SEP);
        printBanner();
        console("&b" + SEP);

        // 1) 停止排程：避免收尾期間仍有快照/取樣寫入
        int cancelled = cancelTasks();
        console(step(lang.get("console.shutdown.tasks"), lang.get("console.shutdown.tasks-result", cancelled)));

        // 2) 停止對外服務：先關閉才不會在收尾期間接受新請求
        stopHttpServer();
        console(step(lang.get("console.shutdown.http"), lang.get("console.shutdown.http-result", stationCount)));

        // 3) 關服前最後保全：補寫待重播資料 + 寫入最後一筆取樣 + 同步 Redis
        HistoryService svc = historyService;
        if (svc == null) {
            console(step(lang.get("console.shutdown.final-sample"), lang.get("console.shutdown.history-disabled")));
        } else {
            int pending = svc.pendingCount();
            if (pending > 0) {
                console(step(lang.get("console.shutdown.pending-queue"), lang.get("console.shutdown.pending-result", pending)));
            }
            HistoryService.FlushResult r = svc.flush(Bukkit.getOnlinePlayers().size(), DiscordBridge.getMemberCount());
            if (r.sampled()) {
                console(step(lang.get("console.shutdown.final-sample"), lang.get("console.shutdown.final-result", Bukkit.getOnlinePlayers().size())));
                if (r.replayed() > 0) {
                    console(step(lang.get("console.shutdown.replay"), lang.get("console.shutdown.replay-result", r.replayed())));
                }
                console(step(lang.get("console.shutdown.redis-sync"), r.redisSynced() ? lang.get("console.shutdown.redis-done") : lang.get("console.shutdown.redis-skip")));
            } else {
                console(step(lang.get("console.shutdown.final-sample"), lang.get("console.shutdown.failed", r.error())));
            }
            svc.close();
            historyService = null;
        }

        // 4) 處罰日誌：把尚未寫入的事件補寫完（排程已停止，此處同步執行）
        PunishmentLogger pl = punishmentLogger;
        if (pl != null) {
            int pendingLogs = pl.pendingCount();
            int written = pl.flush();
            if (pendingLogs > 0 || written > 0) {
                console(step(lang.get("console.shutdown.punishment-log"), lang.get("console.shutdown.punishment-result", written)));
            }
        }

        // 5) 關閉共用儲存層
        if (statCacheInfo != null) {
            console(step(lang.get("console.shutdown.stat-cache"), lang.get("console.shutdown.stat-cache-result", statCacheInfo)));
        }
        if (database != null || redis != null) {
            closeStorage();
            console(step(lang.get("console.shutdown.storage"), lang.get("console.shutdown.storage-result", historyInfo != null ? historyInfo : "-")));
        }

        console("&b" + SEP);
        console(lang.get("console.shutdown.summary", uptime(), System.currentTimeMillis() - t0));
        console("&b" + SEP);
    }

    /** 點線對齊的欄位寬度，取兩種語言最長標籤再留一點餘裕。 */
    private static final int STEP_WIDTH = 26;

    /** 對齊的步驟輸出：「項目 …… 結果」。 */
    private static String step(String label, String result) {
        int pad = Math.max(2, STEP_WIDTH - displayWidth(label));
        return "&b" + label + " &8" + ".".repeat(pad) + " " + result;
    }

    /**
     * 主控台等寬字型下的顯示寬度，中日韓字元佔兩欄。
     *
     * 不能用 length()：「關閉 HTTP 伺服器」有 11 個字元卻佔 16 欄，
     * 一律當成中文乘二又會讓純英文標籤算出負數，兩種語言都會歪掉。
     */
    private static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            width += isFullWidth(cp) ? 2 : 1;
        }
        return width;
    }

    private static boolean isFullWidth(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)     // 韓文字母
                || (cp >= 0x2E80 && cp <= 0xA4CF) // CJK 部首、假名、漢字
                || (cp >= 0xAC00 && cp <= 0xD7A3) // 韓文音節
                || (cp >= 0xF900 && cp <= 0xFAFF) // CJK 相容漢字
                || (cp >= 0xFE30 && cp <= 0xFE6F) // CJK 標點
                || (cp >= 0xFF00 && cp <= 0xFF60) // 全形字元
                || (cp >= 0xFFE0 && cp <= 0xFFE6);
    }

    /** 運行時間；單位字樣走語言檔，否則英文模式會印出「Uptime 3分 8秒」。 */
    private String uptime() {
        long ms = System.currentTimeMillis() - enabledAt;
        long s = ms / 1000, m = s / 60, h = m / 60, d = h / 24;
        if (d > 0) return lang.get("console.uptime-days", d, h % 24, m % 60);
        if (h > 0) return lang.get("console.uptime-hours", h, m % 60, s % 60);
        if (m > 0) return lang.get("console.uptime-minutes", m, s % 60);
        return lang.get("console.uptime-seconds", s);
    }

    // ---- Debug 通知（資料被請求時）----

    /** debug 開啟時：寫入主控台並推送給有 serverapi.notice 權限的玩家。 */
    public void debugNotify(String message) {
        if (!debug) return;
        Runnable task = () -> {
            Component line = LEGACY.deserialize("&8[&bServerAPI &9DEBUG&8] &7" + message);
            Bukkit.getConsoleSender().sendMessage(line);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("serverapi.notice")) {
                    p.sendMessage(line);
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    // ---- 指令（Paper 現代指令 API）----

    public Lang lang() {
        return lang;
    }

    /**
     * 載入語言。config 未指定時依主機語系自動判斷並寫回設定，
     * 讓管理員之後能直接在 config 看到並修改。
     */
    private void setupLanguage() {
        String configured = getConfig().getString("language", "auto");
        String resolved;
        if (configured == null || configured.isBlank() || configured.equalsIgnoreCase("auto")) {
            resolved = Lang.detectFromSystem();
            getConfig().set("language", resolved);
            saveConfig();
        } else {
            resolved = configured;
        }
        lang.load(resolved);
    }

    /**
     * 切換語言：備份現有設定檔，再以新語言的範本重建（保留所有既有值）。
     * 備份保留原檔，管理員隨時可還原。
     *
     * @return 備份檔名
     */
    private String switchLanguage(String target) throws Exception {
        File configFile = new File(getDataFolder(), "config.yml");
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        String backupName = "config.backup-" + stamp + ".yml";

        if (configFile.exists()) {
            java.nio.file.Files.copy(configFile.toPath(),
                    new File(getDataFolder(), backupName).toPath());
        }

        // 讀出使用者目前的所有設定值
        FileConfiguration current = getConfig();
        java.util.Map<String, Object> values = new LinkedHashMap<>();
        for (String key : current.getKeys(true)) {
            if (current.isConfigurationSection(key)) continue;
            values.put(key, current.get(key));
        }

        // 以目標語言的範本覆寫，再把舊值套回去 —— 註解變成新語言，設定值原封不動
        try (java.io.InputStream in = getResource("config-" + target + ".yml")) {
            if (in == null) throw new IllegalStateException("config-" + target + ".yml not bundled");
            java.nio.file.Files.copy(in, configFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        reloadConfig();
        FileConfiguration fresh = getConfig();
        for (var e : values.entrySet()) {
            if ("language".equals(e.getKey())) continue;   // 語言由下方統一設定
            if (fresh.contains(e.getKey())) fresh.set(e.getKey(), e.getValue());
        }
        fresh.set("language", target);
        saveConfig();

        lang.load(target);
        return backupName;
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var root = Commands.literal("serverapi")
                    .requires(src -> src.getSender().hasPermission("serverapi.admin"))
                    .then(Commands.literal("reload").executes(ctx -> {
                        doReload(ctx.getSource().getSender());
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("info").executes(ctx -> {
                        doInfo(ctx.getSource().getSender());
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("lang")
                            .executes(ctx -> {
                                doLangInfo(ctx.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.argument("code", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    // 補全選項取自實際存在的語言檔（jar 內建 + lang/ 目錄），
                                    // 不是寫死的清單 —— 服主自己加的語言檔也要能補到。
                                    .suggests((ctx, builder) -> {
                                        String typed = builder.getRemaining().toLowerCase();
                                        for (String code : lang.available()) {
                                            if (code.toLowerCase().startsWith(typed)) builder.suggest(code);
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        doLangSwitch(ctx.getSource().getSender(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "code"));
                                        return Command.SINGLE_SUCCESS;
                                    })))
                    .executes(ctx -> {
                        doInfo(ctx.getSource().getSender());
                        return Command.SINGLE_SUCCESS;
                    })
                    .build();
            event.registrar().register(root, lang.get("command.description"));
        });
    }

    /** 聊天欄的分隔線。純裝飾不含文字，因此不進語言檔。 */
    private static final String CHAT_RULE =
            "&7&m──&f&l&m───────────────────────────────&7&m──";

    private void doInfo(CommandSender sender) {
        String state = httpServer != null
                ? lang.get("command.running") : lang.get("command.stopped");
        send(sender, "");
        send(sender, lang.get("command.header", getPluginMeta().getVersion(), state));
        send(sender, CHAT_RULE);
        send(sender, "");
        // 逐行列出子指令並附說明。原本擠成 <reload|info|lang> 一行，
        // 看得到有哪些指令，卻看不出各自做什麼。
        send(sender, lang.get("command.usage-info"));
        send(sender, lang.get("command.usage-reload"));
        send(sender, lang.get("command.usage-lang"));
        send(sender, "");
        send(sender, CHAT_RULE);
        sendLinks(sender);
        send(sender, "");
    }

    private void doLangInfo(CommandSender sender) {
        send(sender, lang.get("command.lang-current", lang.code()));
        send(sender, lang.get("command.lang-available", String.join(", ", lang.available())));
        send(sender, lang.get("command.lang-usage"));
    }

    private void doLangSwitch(CommandSender sender, String code) {
        if (!lang.exists(code)) {
            send(sender, lang.get("command.lang-unknown", code));
            send(sender, lang.get("command.lang-available", String.join(", ", lang.available())));
            return;
        }
        if (code.equals(lang.code())) {
            send(sender, lang.get("command.lang-same", code));
            return;
        }
        if (!reloading.compareAndSet(false, true)) {
            send(sender, lang.get("command.reload-busy"));
            return;
        }
        try {
            String backup = switchLanguage(code);
            send(sender, lang.get("command.lang-changed", code, backup));
            // 站點描述在註冊時就已寫入，必須重啟 HTTP 才會換成新語言。
            // 重啟會重建資料庫連線池，故放到非同步執行緒，避免凍結主執行緒。
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    stopHttp();
                    startHttp();
                } finally {
                    reloading.set(false);
                }
            });
            return;
        } catch (Exception e) {
            reloading.set(false);
            send(sender, lang.get("command.lang-failed", e.getMessage()));
            getLogger().warning(lang.get("console.warn.lang-switch-failed", e));
        }
    }

    /** 隨模板附上的預設金鑰；任何看過說明的人都知道這串。 */
    private static final String DEFAULT_API_KEY = "change-me";

    /**
     * 設定健檢：兩種情況都會讓服主以為資料受保護，實際上完全公開。
     * 公開伺服器踩到這兩個坑的代價最高，所以在啟動時就講明白。
     */
    private void warnOnAuthConfig(Map<String, String> enabledStations) {
        if (!authEnabled) {
            // require-key 標了一堆 true，但總開關沒開，這些站點照樣人人可讀
            long marked = enabledStations.keySet().stream().filter(protectedPaths::contains).count();
            if (marked > 0) {
                getLogger().warning(lang.get("console.warn.auth-off-but-marked", marked));
            }
            return;
        }
        if (apiKey == null || apiKey.isBlank() || DEFAULT_API_KEY.equals(apiKey)) {
            getLogger().warning(lang.get("console.warn.auth-default-key"));
        } else if (apiKey.length() < 16) {
            getLogger().warning(lang.get("console.warn.auth-weak-key", apiKey.length()));
        }
    }

    /** 以 & 色碼傳送訊息給指令發送者。 */
    private void send(CommandSender sender, String message) {
        sender.sendMessage(LEGACY.deserialize(message));
    }

    /**
     * 文檔／GitHub／Discord 三個連結，併成一列可點的短標籤。
     *
     * 網址放進 hover 而不直接印出：聊天欄寬度有限，三串網址攤開會佔掉整個畫面。
     * 主控台點不了標籤，所以那邊改為逐行印出完整網址讓人複製。
     */
    private void sendLinks(CommandSender sender) {
        if (!(sender instanceof Player)) {
            send(sender, lang.get("console.docs", "&f" + DOCS_URL));
            send(sender, lang.get("console.modrinth", "&f" + MODRINTH_URL));
            send(sender, lang.get("console.github", "&f" + GITHUB_URL));
            send(sender, lang.get("console.discord", "&f" + DISCORD_URL));
            return;
        }
        Component line = link("command.link-docs", "command.docs-hover", DOCS_URL)
                .append(Component.text(" "))
                .append(link("command.link-modrinth", "command.modrinth-hover", MODRINTH_URL))
                .append(Component.text(" "))
                .append(link("command.link-github", "command.github-hover", GITHUB_URL))
                .append(Component.text(" "))
                .append(link("command.link-discord", "command.discord-hover", DISCORD_URL));
        sender.sendMessage(line);
    }

    /** 單一可點標籤：hover 顯示說明與完整網址，點擊開啟。 */
    private Component link(String labelKey, String hoverKey, String url) {
        Component hover = LEGACY.deserialize(lang.get(hoverKey))
                .append(Component.newline())
                .append(LEGACY.deserialize("&7" + url));
        return LEGACY.deserialize(lang.get(labelKey))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(hover));
    }

    private void doReload(CommandSender sender) {
        // 非同步執行：重載會重建資料庫連線池（遠端 MySQL 可能耗時數秒），
        // 不可在主執行緒進行，否則會凍結整個伺服器。
        if (!reloading.compareAndSet(false, true)) {
            send(sender, lang.get("command.reload-busy"));
            return;
        }
        send(sender, lang.get("command.reloading"));
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                stopHttp();
                reloadConfig();
                setupLanguage();
                startHttp();
                send(sender, lang.get("command.reloaded"));
            } finally {
                reloading.set(false);
            }
        });
    }

    public static ServerAPIPlugin getInstance() {
        return INSTANCE;
    }

    public CustomAPIManager getCustomAPIManager() {
        return this.customAPIManager;
    }
}
