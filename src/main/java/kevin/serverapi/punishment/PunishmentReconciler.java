package kevin.serverapi.punishment;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.ban.BanListType;
import kevin.serverapi.integration.CmiPunishmentBridge;
import kevin.serverapi.storage.SqlPunishmentLog;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kevin.serverapi.lang.Lang;

import java.util.logging.Logger;

/**
 * 把「當前處罰狀態」對帳進「持久化歷史」。
 *
 * 封鎖名單與 CMI 只保存當前狀態 —— 解封後紀錄就消失了。
 * 本類別定期比對兩者差異：
 *   出現在名單但日誌沒有 → 新處罰，寫入
 *   日誌標記生效但名單已無 → 已解除，標記 liftedAt
 * 因此官網能列出所有曾受處罰的玩家，包含早已解封者。
 *
 * 好處是不必攔截各種指令 —— 無論處罰來自原版、CMI、其他插件或主控台，都會被記錄。
 *
 * 執行緒：本方法會讀取 Bukkit 狀態，必須在主執行緒呼叫；
 * 資料庫寫入則交由呼叫端在非同步執行緒完成（見 {@link #pendingWrites()}）。
 */
public final class PunishmentReconciler {

    private final SqlPunishmentLog log;
    private final Logger logger;
    private final Lang lang;

    /** 主執行緒收集到、待非同步寫入的變更。 */
    private final List<SqlPunishmentLog.Entry> inserts = new ArrayList<>();
    private final List<Lift> lifts = new ArrayList<>();

    private record Lift(String type, String target, long at) {}

    /** 主執行緒快照下來的當前狀態，供非同步比對使用。 */
    /**
     * @param cmiAvailable 擷取當下 CMI 是否可用。不可用時 mutes／jails 必然為空，
     *                     此時不可進行對帳，否則會把所有生效中的禁言誤判為已解除。
     */
    public record Snapshot(Map<String, SqlPunishmentLog.Entry> bans,
                           Map<String, SqlPunishmentLog.Entry> ipBans,
                           Map<String, SqlPunishmentLog.Entry> mutes,
                           Map<String, SqlPunishmentLog.Entry> jails,
                           List<String> onlineUuids,
                           boolean cmiAvailable) {}

    public PunishmentReconciler(SqlPunishmentLog log, Logger logger, Lang lang) {
        this.log = log;
        this.logger = logger;
        this.lang = lang;
    }

    /**
     * 於主執行緒擷取當前處罰狀態。
     * 只讀取 Bukkit／CMI，不做任何 I/O。
     */
    public Snapshot snapshot() {
        long now = System.currentTimeMillis();
        Map<String, SqlPunishmentLog.Entry> bans = new HashMap<>();
        Map<String, SqlPunishmentLog.Entry> ipBans = new HashMap<>();
        Map<String, SqlPunishmentLog.Entry> mutes = new HashMap<>();
        Map<String, SqlPunishmentLog.Entry> jails = new HashMap<>();
        List<String> online = new ArrayList<>();

        BanList<PlayerProfile> profileBans = Bukkit.getBanList(BanListType.PROFILE);
        for (BanEntry<? super PlayerProfile> e : profileBans.getEntries()) {
            if (!(e.getBanTarget() instanceof PlayerProfile p) || p.getId() == null) continue;
            bans.put(p.getId().toString(), new SqlPunishmentLog.Entry(
                    "ban", p.getId().toString(), p.getName(),
                    strip(e.getReason()), strip(e.getSource()), null,
                    createdAt(e.getCreated(), now), expiresAt(e.getExpiration()), null, null));
        }

        BanList<InetAddress> addressBans = Bukkit.getBanList(BanListType.IP);
        for (BanEntry<? super InetAddress> e : addressBans.getEntries()) {
            if (!(e.getBanTarget() instanceof InetAddress addr)) continue;
            String ip = addr.getHostAddress();
            ipBans.put(ip, new SqlPunishmentLog.Entry(
                    "ipban", ip, null, strip(e.getReason()), strip(e.getSource()), null,
                    createdAt(e.getCreated(), now), expiresAt(e.getExpiration()), null, null));
        }

        // CMI 的禁言／監禁只能查線上玩家，故一併記下線上名單，
        // 對帳時才不會把「離線」誤判為「已解除」。
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            online.add(id.toString());

            var mute = CmiPunishmentBridge.muteOf(id);
            if (mute != null) {
                mutes.put(id.toString(), new SqlPunishmentLog.Entry(
                        "mute", id.toString(), player.getName(), strip(mute.reason()), null,
                        mute.shadow() ? "shadow" : null, now, positive(mute.until()), null, null));
            }
            var jail = CmiPunishmentBridge.jailOf(id);
            if (jail != null) {
                jails.put(id.toString(), new SqlPunishmentLog.Entry(
                        "jail", id.toString(), player.getName(), strip(jail.reason()), jail.by(), null,
                        now, positive(jail.until()), null, null));
            }
        }
        return new Snapshot(bans, ipBans, mutes, jails, online, CmiPunishmentBridge.isAvailable());
    }

    /**
     * 於非同步執行緒比對快照與資料庫，收集需要寫入的變更。
     * 實際寫入由 {@link #flush()} 執行。
     */
    public void reconcile(Snapshot snapshot) {
        long now = System.currentTimeMillis();
        diff("ban", snapshot.bans(), null, now);
        diff("ipban", snapshot.ipBans(), null, now);

        // CMI 不可用時，禁言／監禁的當前狀態無從得知（一律為空），
        // 若照常對帳會把所有生效中的紀錄誤標為已解除，故整段略過。
        if (snapshot.cmiAvailable()) {
            // 僅對線上玩家有效，離線者狀態未知，不視為解除
            diff("mute", snapshot.mutes(), snapshot.onlineUuids(), now);
            diff("jail", snapshot.jails(), snapshot.onlineUuids(), now);
        }
    }

    /**
     * @param onlyConsider 僅在此清單內的對象才判斷「是否已解除」；
     *                     null 表示不限制（封鎖名單是全域的，可放心比對）
     */
    private void diff(String type, Map<String, SqlPunishmentLog.Entry> current,
                      List<String> onlyConsider, long now) {
        try {
            // 只取「仍生效」者比對；已到期的舊紀錄不該擋住新的處罰
            Map<String, SqlPunishmentLog.Entry> stored = log.findActive(type, now);

            for (Map.Entry<String, SqlPunishmentLog.Entry> e : current.entrySet()) {
                SqlPunishmentLog.Entry old = stored.get(e.getKey());
                if (old == null) {
                    inserts.add(e.getValue());          // 全新的處罰
                } else if (isNewPunishment(type, old, e.getValue())) {
                    // 同一對象在舊處罰仍生效時被重新處罰（例如再次 tempban）：
                    // 結束舊紀錄並寫入新的，否則新處罰會被誤判為既有而遺失。
                    lifts.add(new Lift(type, e.getKey(), now));
                    inserts.add(e.getValue());
                }
            }
            for (String target : stored.keySet()) {
                if (current.containsKey(target)) continue;
                if (onlyConsider != null && !onlyConsider.contains(target)) continue;  // 離線，狀態未知
                lifts.add(new Lift(type, target, now));
            }
        } catch (Exception ex) {
            logger.warning(lang.get("console.warn.punishment-reconcile-failed", type, ex));
        }
    }

    /**
     * 將收集到的變更寫入資料庫（非同步執行緒）。
     * 必須「先標記解除、後寫入新紀錄」—— 反過來的話，重新處罰的情境中
     * markLifted 會連同剛插入的新紀錄一起標記為已解除。
     */
    public int flush() {
        int changes = 0;
        try {
            for (Lift l : lifts) {
                log.markLifted(l.type(), l.target(), l.at(), null);
                changes++;
            }
            lifts.clear();
            if (!inserts.isEmpty()) {
                log.insert(inserts);
                changes += inserts.size();
                inserts.clear();
            }
        } catch (Exception e) {
            logger.warning(lang.get("console.warn.punishment-write-failed", e));
            inserts.clear();
            lifts.clear();
        }
        return changes;
    }

    public boolean pendingWrites() {
        return !inserts.isEmpty() || !lifts.isEmpty();
    }

    /**
     * 判斷這是否為「另一次」處罰，而非同一筆的重複觀察。
     *
     * 封鎖有可靠的建立時間，直接比對即可（容許 1 秒誤差）。
     * 禁言／監禁的 at 是每次快照當下的時間，無法比對，
     * 故改以到期時間是否變動來判斷。
     */
    private static boolean isNewPunishment(String type, SqlPunishmentLog.Entry stored,
                                           SqlPunishmentLog.Entry current) {
        if (type.equals("ban") || type.equals("ipban")) {
            return Math.abs(stored.at() - current.at()) > 1000;
        }
        return !java.util.Objects.equals(stored.expires(), current.expires());
    }

    /** 處罰發生時間；封鎖名單未記錄時以現在時間代替。 */
    private static long createdAt(Date d, long fallback) {
        return d == null ? fallback : d.getTime();
    }

    /** 到期時間；null 表示永久。 */
    private static Long expiresAt(Date d) {
        return d == null ? null : d.getTime();
    }

    private static Long positive(Long v) {
        return (v == null || v <= 0) ? null : v;
    }

    private static String strip(String s) {
        return s == null ? null : s.replaceAll("(?i)§[0-9A-FK-ORX]", "");
    }
}
