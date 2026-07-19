package kevin.serverapi.integration;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * 以反射透過 Vault 取得玩家經濟餘額（相容所有實作 Vault 的經濟插件）。
 * 未安裝 Vault 或無經濟提供者時回傳 null。
 */
public final class VaultEconomyBridge {

    private VaultEconomyBridge() {}

    public static Double getBalance(OfflinePlayer player) {
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) return null;

            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp == null) return null;
            Object economy = rsp.getProvider();
            if (economy == null) return null;

            Object balance = economyClass.getMethod("getBalance", OfflinePlayer.class).invoke(economy, player);
            return balance instanceof Number n ? n.doubleValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
