package kevin.serverapi.listener;

import kevin.serverapi.storage.SqlPunishmentLog;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import kevin.serverapi.lang.Lang;

import java.util.logging.Logger;

/**
 * 記錄 Minecraft 與 CMI 都不保存的處罰事件（目前為踢出）。
 *
 * 執行緒策略：事件在主執行緒觸發，此處僅寫入記憶體佇列（O(1)），
 * 實際的資料庫寫入由非同步排程批次執行，主執行緒不做任何 I/O。
 *
 * 執行者(issuedBy)：PlayerKickEvent 不含「是誰踢的」，
 * 因此另外監聽指令事件，於短時間窗內將指令發送者與踢出事件配對。
 */
public final class PunishmentLogger implements Listener {

    /** 指令與踢出事件的配對有效期。踢出通常在指令後數毫秒內發生。 */
    private static final long ISSUER_WINDOW_MS = 3000;
    private static final int BUFFER_LIMIT = 500;

    private final Queue<SqlPunishmentLog.Entry> buffer = new ConcurrentLinkedQueue<>();
    /** 目標玩家名稱（小寫）-> 最近執行踢出指令者。 */
    private final Map<String, PendingIssuer> pendingIssuers = new ConcurrentHashMap<>();

    private final SqlPunishmentLog store;
    private final Set<String> loggedCauses;
    private final boolean skipWhenBanned;
    private final Logger logger;
    private final Lang lang;

    private record PendingIssuer(String name, long at) {}

    public PunishmentLogger(SqlPunishmentLog store, Set<String> loggedCauses,
                            boolean skipWhenBanned, Logger logger, Lang lang) {
        this.store = store;
        this.loggedCauses = loggedCauses;
        this.skipWhenBanned = skipWhenBanned;
        this.logger = logger;
        this.lang = lang;
    }

    // ---- 事件 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        String cause = event.getCause().name();
        if (!loggedCauses.contains(cause)) return;   // 例如 TIMEOUT、IDLING 不算處罰

        Player player = event.getPlayer();

        // 封鎖會連帶把玩家踢出，若照實記錄會讓同一次處罰同時出現 ban 與 kick 兩筆。
        // 封鎖本身已在封鎖名單中，故此處略過。
        if (skipWhenBanned && player.isBanned()) return;

        if (buffer.size() < BUFFER_LIMIT) {
            // 踢出是一次性事件：沒有到期時間，也不會被「解除」
            buffer.add(SqlPunishmentLog.Entry.event(
                    "kick",
                    player.getUniqueId().toString(),
                    player.getName(),
                    strip(PlainTextComponentSerializer.plainText().serialize(event.reason())),
                    resolveIssuer(player),
                    cause,
                    System.currentTimeMillis()));
        }
    }

    /**
     * 找出這次踢出的執行者。
     *
     * 先以玩家名比對；比對不到時（例如指令用的是 CMI 暱稱而非玩家名），
     * 若時間窗內剛好只有一筆待配對的踢出指令，就採用它。
     * 同時多人被踢時無法可靠區分，此時寧可回傳 null 也不猜測。
     */
    private String resolveIssuer(Player player) {
        long now = System.currentTimeMillis();
        prunePending();

        PendingIssuer exact = pendingIssuers.remove(player.getName().toLowerCase(Locale.ROOT));
        if (exact != null && now - exact.at() <= ISSUER_WINDOW_MS) {
            return exact.name();
        }
        if (pendingIssuers.size() == 1) {
            var entry = pendingIssuers.entrySet().iterator().next();
            pendingIssuers.remove(entry.getKey());
            if (now - entry.getValue().at() <= ISSUER_WINDOW_MS) {
                return entry.getValue().name();
            }
        }
        return null;
    }

    /** 移除 § 顏色代碼，讓官網端可直接顯示。 */
    private static String strip(String s) {
        return s == null ? null : s.replaceAll("(?i)§[0-9A-FK-ORX]", "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        rememberIssuer(event.getMessage(), event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        rememberIssuer(event.getCommand(), "CONSOLE");
    }

    /**
     * 從指令字串判斷是否為踢出指令並取出目標玩家。
     * 支援 /kick、/minecraft:kick、/cmi kick 等常見形式。
     */
    private void rememberIssuer(String rawCommand, String sender) {
        if (rawCommand == null || rawCommand.isBlank()) return;
        String[] parts = rawCommand.startsWith("/") ? rawCommand.substring(1).trim().split("\\s+")
                : rawCommand.trim().split("\\s+");
        if (parts.length < 2) return;

        String cmd = parts[0].toLowerCase(Locale.ROOT);
        int targetIndex;
        if (cmd.endsWith("kick")) {                       // kick / minecraft:kick / cmi:kick
            targetIndex = 1;
        } else if (cmd.endsWith("cmi") && parts.length >= 3
                && parts[1].equalsIgnoreCase("kick")) {   // cmi kick <player>
            targetIndex = 2;
        } else {
            return;
        }
        if (parts.length <= targetIndex) return;

        String target = parts[targetIndex].toLowerCase(Locale.ROOT);
        PendingIssuer pending = new PendingIssuer(sender, System.currentTimeMillis());
        pendingIssuers.put(target, pending);

        // 指令參數可能是暱稱；若能解析成線上玩家，一併以其真實名稱建立索引
        Player resolved = org.bukkit.Bukkit.getPlayerExact(parts[targetIndex]);
        if (resolved != null) {
            pendingIssuers.put(resolved.getName().toLowerCase(Locale.ROOT), pending);
        }
        prunePending();
    }

    /** 清掉過期的配對紀錄，避免長期累積。 */
    private void prunePending() {
        long cutoff = System.currentTimeMillis() - ISSUER_WINDOW_MS;
        pendingIssuers.entrySet().removeIf(e -> e.getValue().at() < cutoff);
    }

    // ---- 非同步寫入 ----

    /** 將緩衝批次寫入資料庫。於非同步排程與關服收尾時呼叫。 */
    public int flush() {
        if (buffer.isEmpty() || store == null) return 0;
        List<SqlPunishmentLog.Entry> batch = new java.util.ArrayList<>();
        SqlPunishmentLog.Entry e;
        while ((e = buffer.poll()) != null) batch.add(e);
        if (batch.isEmpty()) return 0;
        try {
            store.insert(batch);
            return batch.size();
        } catch (Exception ex) {
            logger.warning(lang.get("console.warn.punishment-write-failed", ex));
            return 0;
        }
    }

    public int pendingCount() {
        return buffer.size();
    }
}
