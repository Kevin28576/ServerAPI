package kevin.serverapi.integration;

import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * 以反射整合 CMI，補全玩家的暱稱／顏色／遊玩時長／AFK 狀態。
 * 未安裝 CMI 或個別方法不存在時，對應欄位為 null（各欄位獨立降級）。
 */
public final class CmiBridge {

    private CmiBridge() {}

    /**
     * @param nickname     CMI 暱稱（可能含顏色代碼）
     * @param messageColor 聊天顏色（/chatcolor 指令設定值）
     * @param playtime     累計遊玩時長（CMI 回傳之原始數值）
     * @param afk          是否 AFK
     */
    public record CmiProfile(String nickname, String messageColor, Long playtime, Boolean afk) {}

    public static boolean isAvailable() {
        var p = Bukkit.getPluginManager().getPlugin("CMI");
        return p != null && p.isEnabled();
    }

    /** 一次取得 CMI 使用者資料；各欄位獨立以反射擷取，缺一不影響其餘。 */
    public static CmiProfile profile(UUID uuid) {
        if (!isAvailable()) return null;
        try {
            Class<?> cmi = Class.forName("com.Zrips.CMI.CMI");
            Object instance = cmi.getMethod("getInstance").invoke(null);
            if (instance == null) return null;
            Object playerManager = cmi.getMethod("getPlayerManager").invoke(instance);
            if (playerManager == null) return null;
            Object user = playerManager.getClass().getMethod("getUser", UUID.class).invoke(playerManager, uuid);
            if (user == null) return null;

            Class<?> uc = user.getClass();
            return new CmiProfile(
                    str(uc, user, "getNickName"),
                    // /chatcolor 的值：不同 CMI 版本方法名不一，依序嘗試
                    firstNonNull(uc, user, "getChatColor", "getChatColour", "getMessageColor", "getNameColor"),
                    lng(uc, user, "getTotalPlayTime"),
                    bool(uc, user, "isAfk"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 依序嘗試多個候選方法名，回傳第一個有值者（相容不同 CMI 版本）。 */
    private static String firstNonNull(Class<?> c, Object target, String... methods) {
        for (String m : methods) {
            String v = str(c, target, m);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String str(Class<?> c, Object target, String method) {
        try {
            Object v = c.getMethod(method).invoke(target);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Long lng(Class<?> c, Object target, String method) {
        try {
            Object v = c.getMethod(method).invoke(target);
            return v instanceof Number n ? n.longValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean bool(Class<?> c, Object target, String method) {
        try {
            Object v = c.getMethod(method).invoke(target);
            return v instanceof Boolean b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
