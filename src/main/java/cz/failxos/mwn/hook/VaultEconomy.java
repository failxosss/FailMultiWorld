package cz.failxos.mwn.hook;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.manager.EconomyStore;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;

/** Vault economy provider with balances per virtual server (only when economy.provider is true). */
@SuppressWarnings("deprecation")
public final class VaultEconomy implements Economy {
    private final MWNPlugin plugin;
    private final EconomyStore store;

    public VaultEconomy(MWNPlugin plugin, EconomyStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    private OfflinePlayer off(String name) { return Bukkit.getOfflinePlayer(name); }

    private double bal(OfflinePlayer p, String world) {
        return store.get(p.getUniqueId(), store.scope(p, world));
    }

    private EconomyResponse withdraw(OfflinePlayer p, String world, double amount) {
        if (amount < 0) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative funds");
        String scope = store.scope(p, world);
        UUID u = p.getUniqueId();
        double b = store.get(u, scope);
        if (b < amount) return new EconomyResponse(0, b, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        store.set(u, scope, b - amount);
        return new EconomyResponse(amount, b - amount, EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse deposit(OfflinePlayer p, String world, double amount) {
        if (amount < 0) return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative funds");
        String scope = store.scope(p, world);
        UUID u = p.getUniqueId();
        double b = store.get(u, scope);
        store.set(u, scope, b + amount);
        return new EconomyResponse(amount, b + amount, EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "MWN does not support banks");
    }

    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "FailMultiWorld"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return plugin.getConfig().getInt("economy.fractional-digits", 2); }
    @Override public String format(double amount) {
        return EconomyStore.format(plugin, amount) + " " + (amount == 1.0 ? currencyNameSingular() : currencyNamePlural());
    }
    @Override public String currencyNamePlural() { return plugin.getConfig().getString("economy.currency-plural", "coins"); }
    @Override public String currencyNameSingular() { return plugin.getConfig().getString("economy.currency-singular", "coin"); }

    @Override public boolean hasAccount(String playerName) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player) { return true; }
    @Override public boolean hasAccount(String playerName, String worldName) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return true; }

    @Override public double getBalance(String playerName) { return bal(off(playerName), null); }
    @Override public double getBalance(OfflinePlayer player) { return bal(player, null); }
    @Override public double getBalance(String playerName, String world) { return bal(off(playerName), world); }
    @Override public double getBalance(OfflinePlayer player, String world) { return bal(player, world); }

    @Override public boolean has(String playerName, double amount) { return bal(off(playerName), null) >= amount; }
    @Override public boolean has(OfflinePlayer player, double amount) { return bal(player, null) >= amount; }
    @Override public boolean has(String playerName, String worldName, double amount) { return bal(off(playerName), worldName) >= amount; }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return bal(player, worldName) >= amount; }

    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return withdraw(off(playerName), null, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return withdraw(player, null, amount); }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return withdraw(off(playerName), worldName, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return withdraw(player, worldName, amount); }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) { return deposit(off(playerName), null, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return deposit(player, null, amount); }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return deposit(off(playerName), worldName, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return deposit(player, worldName, amount); }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }

    @Override public boolean createPlayerAccount(String playerName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { return true; }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return true; }
}
