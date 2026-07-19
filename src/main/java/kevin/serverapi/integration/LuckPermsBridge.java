package kevin.serverapi.integration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 透過 LuckPerms API 取得玩家的主要群組（rank / primary group）。
 * 未安裝 LuckPerms 或查不到時回傳 null。
 *
 * 以「插件在場檢查 + catch Throwable」保護：LuckPerms 缺席時，
 * 其類別在方法實際執行前不會被連結，NoClassDefFoundError 也會被吞掉。
 */
public final class LuckPermsBridge {

    private LuckPermsBridge() {}

    /**
     * @param timeoutMs 離線玩家載入逾時上限（毫秒）。此呼叫發生在 HTTP 執行緒，
     *                  設定較短的上限可避免慢查詢佔滿執行緒池。
     */
    public static String getPrimaryGroup(UUID uuid, long timeoutMs) {
        try {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return null;

            LuckPerms api = LuckPermsProvider.get();
            UserManager userManager = api.getUserManager();

            // 線上玩家通常已在快取中
            User user = userManager.getUser(uuid);
            if (user == null) {
                // 離線玩家：載入（含逾時保護）
                user = userManager.loadUser(uuid).get(timeoutMs, TimeUnit.MILLISECONDS);
            }
            return user == null ? null : user.getPrimaryGroup();
        } catch (Throwable t) {
            return null;
        }
    }
}
