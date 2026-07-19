package kevin.serverapi.discord;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * 以反射方式向 DiscordSRV 取得主要伺服器 (Guild) 的成員數，
 * 避免對 DiscordSRV/JDA 產生編譯期硬相依。
 * DiscordSRV 未安裝或尚未連線時回傳 null。
 */
public final class DiscordBridge {

    private DiscordBridge() {}

    public static Integer getMemberCount() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (p == null || !p.isEnabled()) return null;

            Class<?> clazz = Class.forName("github.scarsz.discordsrv.DiscordSRV");
            Object instance = clazz.getMethod("getPlugin").invoke(null);
            if (instance == null) return null;

            Object guild = clazz.getMethod("getMainGuild").invoke(instance);
            if (guild == null) return null;

            Object count = guild.getClass().getMethod("getMemberCount").invoke(guild);
            return count instanceof Number n ? n.intValue() : null;
        } catch (Throwable t) {
            // DiscordSRV 缺席、API 變動或尚未就緒 —— 靜默降級
            return null;
        }
    }

    /** 取得指定 UUID 於 DiscordSRV 連結的 Discord 帳號 ID；未連結或缺席時回傳 null。 */
    public static String getLinkedDiscordId(UUID uuid) {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("DiscordSRV");
            if (p == null || !p.isEnabled()) return null;

            Class<?> clazz = Class.forName("github.scarsz.discordsrv.DiscordSRV");
            Object instance = clazz.getMethod("getPlugin").invoke(null);
            if (instance == null) return null;

            Object linkManager = clazz.getMethod("getAccountLinkManager").invoke(instance);
            if (linkManager == null) return null;

            Object id = linkManager.getClass().getMethod("getDiscordId", UUID.class).invoke(linkManager, uuid);
            return id == null ? null : id.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
