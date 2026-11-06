package cz.failxos.mwn.hook;

import cz.failxos.mwn.MWNPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.ServicePriority;

/** Isolated so Vault classes load only when Vault is present and the provider is enabled. */
public final class VaultHook {
    private VaultHook() {}

    public static void register(MWNPlugin plugin) {
        plugin.getServer().getServicesManager().register(Economy.class, new VaultEconomy(plugin, plugin.economy()), plugin, ServicePriority.Highest);
    }
}
