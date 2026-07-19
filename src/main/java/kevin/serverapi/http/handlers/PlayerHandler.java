package kevin.serverapi.http.handlers;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.discord.DiscordBridge;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.integration.CmiBridge;
import kevin.serverapi.integration.ColorUtil;
import kevin.serverapi.integration.LuckPermsBridge;
import kevin.serverapi.integration.VaultEconomyBridge;
import kevin.serverapi.json.Json;
import kevin.serverapi.stats.StatCacheService;
import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;

import java.util.Locale;
import java.util.UUID;

/**
 * /api/player/{uuid} — 單一玩家資訊（以 UUID 查詢）。
 *
 * 欄位來源：
 *   uuid/name/op/banned/registry/offline → Bukkit
 *   discord                              → DiscordSRV 帳號連結
 *   rank                                 → LuckPerms 主要群組 (primary group)
 *   nickname/messagecolor/playtime/afk   → CMI（未安裝則為 null）
 *   nicknamecolor                        → 由 nickname 的色碼自動推導（hex）
 *   balance                              → Vault 經濟（相容各經濟插件）
 *   addons/pass                          → 目前為預設值（待接資料來源）
 *   stat                                 → 原版統計（Statistic.Type.UNTYPED）
 *   detailed                             → 方塊／物品／生物別統計，需 ?detailed=true
 */
public class PlayerHandler extends ApiHandler {

    private final StatCacheService statCache;   // 可為 null（未啟用）

    public PlayerHandler(ServerAPIPlugin plugin, StatCacheService statCache) {
        super(plugin);
        this.statCache = statCache;
    }

    @Override
    protected Object build(HttpExchange exchange) {
        String remainder = remainderOf(exchange);

        if (remainder.isEmpty()) {
            throw new NotFoundException(plugin.lang().get("api.error.player-uuid-required"));
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(remainder);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(plugin.lang().get("api.error.invalid-uuid", remainder));
        }

        OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
        boolean online = p.isOnline();

        long firstPlayed = p.getFirstPlayed();
        long lastSeen = p.getLastSeen();

        long lpTimeout = Math.max(100L, plugin.getConfig().getLong("integrations.luckperms.timeout-ms", 1000L));
        CmiBridge.CmiProfile cmi = CmiBridge.profile(uuid);

        return Json.obj()
                .put("uuid", uuid.toString())
                .put("name", p.getName())
                .put("nickname", cmi != null ? cmi.nickname() : null)
                // messagecolor：CMI /chatcolor 的設定值；nicknamecolor：由暱稱色碼自動推導
                .put("messagecolor", cmi != null ? ColorUtil.normalize(cmi.messageColor()) : null)
                .put("nicknamecolor", cmi != null ? ColorUtil.firstColor(cmi.nickname()) : null)
                .put("rank", LuckPermsBridge.getPrimaryGroup(uuid, lpTimeout))
                .put("registry", firstPlayed > 0 ? firstPlayed : null)
                .put("offline", online ? null : (lastSeen > 0 ? lastSeen : null))
                .put("playtime", cmi != null ? cmi.playtime() : null)
                .put("afk", cmi != null ? cmi.afk() : null)
                .put("balance", VaultEconomyBridge.getBalance(p))
                .put("addons", false)                     // 待接資料來源
                .put("pass", false)                       // 待接資料來源
                .put("discord", DiscordBridge.getLinkedDiscordId(uuid))
                .put("op", p.isOp())
                .put("banned", p.isBanned())
                .put("stat", untypedStats(p))
                // detailed 需逐一查詢上千種方塊/物品/生物，成本高，故預設不展開；
                // 以 ?detailed=true 明確要求時才計算。
                .put("detailed", wantsDetailed(exchange) ? detailed(p, uuid, online) : Json.obj());
    }

    /**
     * 線上玩家的統計在記憶體中，直接計算即可（約 4ms）；
     * 離線玩家需重讀統計檔（實測約 425ms），故走 Redis／資料庫快取。
     */
    private Object detailed(OfflinePlayer p, UUID uuid, boolean online) {
        if (online || statCache == null || !statCache.isActive()) {
            return detailedStats(p);
        }
        String json = statCache.get(uuid, () -> detailedStats(p).toJson());
        return json != null ? Json.raw(json) : Json.obj();
    }

    private static boolean wantsDetailed(HttpExchange exchange) {
        String q = exchange.getRequestURI().getQuery();
        return q != null && java.util.Arrays.asList(q.split("&")).contains("detailed=true");
    }

    /** 無需限定條件的統計（Statistic.Type.UNTYPED），例如 deaths、jump、play_one_minute。 */
    private static Json.JsonObject untypedStats(OfflinePlayer p) {
        Json.JsonObject out = Json.obj();
        for (Statistic s : Statistic.values()) {
            if (s.getType() != Statistic.Type.UNTYPED) continue;
            try {
                out.put(key(s.name()), p.getStatistic(s));
            } catch (Exception ignored) {
                // 此伺服器版本不支援該統計
            }
        }
        return out;
    }

    /**
     * 需限定方塊／物品／生物的統計（BLOCK / ITEM / ENTITY），
     * 例如 mine_block、use_item、kill_entity。僅輸出非零項目以控制回應大小。
     */
    private static Json.JsonObject detailedStats(OfflinePlayer p) {
        Json.JsonObject out = Json.obj();
        for (Statistic s : Statistic.values()) {
            Statistic.Type type = s.getType();
            if (type == Statistic.Type.UNTYPED) continue;

            Json.JsonObject entries = Json.obj();
            if (type == Statistic.Type.ENTITY) {
                for (EntityType e : EntityType.values()) {
                    try {
                        int v = p.getStatistic(s, e);
                        if (v != 0) entries.put(key(e.name()), v);
                    } catch (Exception ignored) {
                        // 該統計不適用此生物
                    }
                }
            } else {
                for (Material m : Material.values()) {
                    if (m.name().startsWith("LEGACY_")) continue;
                    if (type == Statistic.Type.BLOCK && !m.isBlock()) continue;
                    try {
                        int v = p.getStatistic(s, m);
                        if (v != 0) entries.put(key(m.name()), v);
                    } catch (Exception ignored) {
                        // 該統計不適用此方塊／物品
                    }
                }
            }
            if (!entries.isEmpty()) out.put(key(s.name()), entries);
        }
        return out;
    }

    private static String key(String enumName) {
        return enumName.toLowerCase(Locale.ROOT);
    }
}
