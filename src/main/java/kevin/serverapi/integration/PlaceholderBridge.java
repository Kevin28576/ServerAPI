package kevin.serverapi.integration;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 以反射整合 PlaceholderAPI，避免編譯期硬相依。
 * 未安裝時 {@link #isAvailable()} 回傳 false，其餘方法安全降級。
 */
public final class PlaceholderBridge {

    private PlaceholderBridge() {}

    /** 單一 expansion 的中繼資料（供預覽頁使用）。 */
    public record ExpansionInfo(String identifier, String author, String version, List<String> placeholders) {}

    public static boolean isAvailable() {
        Plugin p = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        return p != null && p.isEnabled();
    }

    /**
     * 解析文字中的 placeholder。
     * 注意：部分 expansion 會存取 Bukkit API，故呼叫端應在主執行緒執行。
     */
    public static String resolve(OfflinePlayer player, String text) {
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method m = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            Object result = m.invoke(null, player, text);
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 列出所有已註冊的 expansion（供 ?list=true 預覽頁）。 */
    public static List<ExpansionInfo> expansions() {
        List<ExpansionInfo> out = new ArrayList<>();
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPIPlugin");
            Object instance = papi.getMethod("getInstance").invoke(null);
            if (instance == null) return out;
            Object manager = papi.getMethod("getLocalExpansionManager").invoke(instance);
            if (manager == null) return out;
            Object expansions = manager.getClass().getMethod("getExpansions").invoke(manager);
            if (!(expansions instanceof Collection<?> collection)) return out;

            for (Object exp : collection) {
                if (exp == null) continue;
                Class<?> ec = exp.getClass();
                List<String> placeholders = new ArrayList<>();
                try {
                    Object pl = ec.getMethod("getPlaceholders").invoke(exp);
                    if (pl instanceof Collection<?> pc) {
                        for (Object o : pc) if (o != null) placeholders.add(o.toString());
                    }
                } catch (Throwable ignored) {
                    // 該 expansion 未宣告範例 placeholder
                }
                out.add(new ExpansionInfo(
                        str(ec, exp, "getIdentifier"),
                        str(ec, exp, "getAuthor"),
                        str(ec, exp, "getVersion"),
                        placeholders));
            }
        } catch (Throwable ignored) {
            // PlaceholderAPI 缺席或內部 API 變動 —— 靜默降級
        }
        return out;
    }

    private static String str(Class<?> c, Object target, String method) {
        try {
            Object v = c.getMethod(method).invoke(target);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
