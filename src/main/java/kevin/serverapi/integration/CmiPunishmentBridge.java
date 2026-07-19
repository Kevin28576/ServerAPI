package kevin.serverapi.integration;

import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 以反射整合 CMI 的處罰資料：警告、禁言、監禁。
 * 未安裝 CMI 或個別 API 不存在時各自降級為 null／空清單。
 *
 * 注意：Minecraft 與 CMI 皆「不保存踢出(kick)歷史」，kick 為即時動作，
 * 因此本橋接無法提供踢出紀錄。
 */
public final class CmiPunishmentBridge {

    private CmiPunishmentBridge() {}

    /** @param givenAt 發出時間（epoch millis） */
    public record Warning(UUID uuid, String reason, String givenBy, Long givenAt, String category) {}

    /** @param until 到期時間；null 或 <=0 表示永久 */
    public record Mute(String reason, Long until, boolean shadow) {}

    public record Jail(String reason, Long until, String by) {}

    public static boolean isAvailable() {
        var p = Bukkit.getPluginManager().getPlugin("CMI");
        return p != null && p.isEnabled();
    }

    private static Object cmiInstance() throws Exception {
        return Class.forName("com.Zrips.CMI.CMI").getMethod("getInstance").invoke(null);
    }

    private static Object user(UUID uuid) throws Exception {
        Object cmi = cmiInstance();
        Object pm = cmi.getClass().getMethod("getPlayerManager").invoke(cmi);
        return pm.getClass().getMethod("getUser", UUID.class).invoke(pm, uuid);
    }

    /**
     * 全服警告紀錄。
     * WarningManager 內部以 HashMap&lt;UUID, List&lt;CMIPlayerWarning&gt;&gt; 保存，
     * 直接讀取該欄位即可列舉，無需逐一掃描玩家。
     */
    @SuppressWarnings("unchecked")
    public static List<Warning> allWarnings() {
        List<Warning> out = new ArrayList<>();
        if (!isAvailable()) return out;
        try {
            Object cmi = cmiInstance();
            Object manager = cmi.getClass().getMethod("getWarningManager").invoke(cmi);
            Field field = manager.getClass().getDeclaredField("warnings");
            field.setAccessible(true);
            Object raw = field.get(manager);
            if (!(raw instanceof Map<?, ?> map)) return out;

            for (Map.Entry<?, ?> e : ((Map<UUID, Object>) map).entrySet()) {
                if (!(e.getKey() instanceof UUID uuid) || !(e.getValue() instanceof List<?> list)) continue;
                for (Object w : list) {
                    Warning parsed = parseWarning(uuid, w);
                    if (parsed != null) out.add(parsed);
                }
            }
        } catch (Throwable ignored) {
            // CMI 缺席或內部結構變動 —— 靜默降級
        }
        return out;
    }

    /** 指定玩家的警告紀錄。 */
    public static List<Warning> warningsOf(UUID uuid) {
        List<Warning> out = new ArrayList<>();
        if (!isAvailable()) return out;
        try {
            Object cmi = cmiInstance();
            Object manager = cmi.getClass().getMethod("getWarningManager").invoke(cmi);
            Object list = manager.getClass().getMethod("getWarnings", UUID.class).invoke(manager, uuid);
            if (list instanceof List<?> l) {
                for (Object w : l) {
                    Warning parsed = parseWarning(uuid, w);
                    if (parsed != null) out.add(parsed);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static Warning parseWarning(UUID uuid, Object w) {
        if (w == null) return null;
        try {
            Class<?> c = w.getClass();
            String reason = str(c, w, "getReason");
            String by = str(c, w, "getGivenBy");
            Long at = lng(c, w, "getGivenAt");
            String category = null;
            try {
                Object cat = c.getMethod("getCategory").invoke(w);
                if (cat != null) category = str(cat.getClass(), cat, "getName");
            } catch (Throwable ignored) {
            }
            return new Warning(uuid, reason, by, at, category);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 目前的禁言狀態；未被禁言時回傳 null。 */
    public static Mute muteOf(UUID uuid) {
        if (!isAvailable()) return null;
        try {
            Object u = user(uuid);
            if (u == null) return null;
            Class<?> c = u.getClass();
            boolean muted = Boolean.TRUE.equals(bool(c, u, "isMuted"));
            boolean shadow = Boolean.TRUE.equals(bool(c, u, "isShadowMuted"));
            if (!muted && !shadow) return null;
            return new Mute(str(c, u, "getMutedReason"), lng(c, u, "getMutedUntil"), shadow);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 目前的監禁狀態；未被監禁時回傳 null。 */
    public static Jail jailOf(UUID uuid) {
        if (!isAvailable()) return null;
        try {
            Object u = user(uuid);
            if (u == null) return null;
            Class<?> c = u.getClass();
            if (!Boolean.TRUE.equals(bool(c, u, "isJailed"))) return null;
            String by = null;
            try {
                Object id = c.getMethod("getJailedBy").invoke(u);
                if (id != null) by = id.toString();
            } catch (Throwable ignored) {
            }
            return new Jail(str(c, u, "getJailedReason"), lng(c, u, "getJailedUntil"), by);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 警告點數（CMI 的 warning points）。 */
    public static Integer warningPointsOf(UUID uuid) {
        if (!isAvailable()) return null;
        try {
            Object u = user(uuid);
            if (u == null) return null;
            Object v = u.getClass().getMethod("getWarningPoints").invoke(u);
            return v instanceof Number n ? n.intValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Class<?> c, Object o, String m) {
        try {
            Object v = c.getMethod(m).invoke(o);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Long lng(Class<?> c, Object o, String m) {
        try {
            Object v = c.getMethod(m).invoke(o);
            return v instanceof Number n ? n.longValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean bool(Class<?> c, Object o, String m) {
        try {
            Object v = c.getMethod(m).invoke(o);
            return v instanceof Boolean b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
