package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import org.bukkit.OfflinePlayer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Balances per scope: the group id when the group has separate-economy, otherwise "shared".
 * Used by the optional Vault provider; has no Vault dependency itself.
 */
public final class EconomyStore {
    private final MWNPlugin plugin;
    private final ConcurrentHashMap<String, Double> cache = new ConcurrentHashMap<>();

    public EconomyStore(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public String scope(OfflinePlayer p, String world) {
        Group g = null;
        if (world != null) g = plugin.groups().byWorld(world);
        else if (p != null && p.getPlayer() != null) g = plugin.switcher().groupOf(p.getPlayer());
        return g != null && g.separateEconomy ? g.id : "shared";
    }

    private static String key(UUID u, String scope) {
        return u + ":" + scope;
    }

    public double get(UUID u, String scope) {
        Double c = cache.get(key(u, scope));
        if (c != null) return c;
        double start = plugin.getConfig().getDouble("economy.starting-balance", 0.0);
        try {
            double v = plugin.db().sync(conn -> {
                try (PreparedStatement ps = conn.prepareStatement("SELECT balance FROM mwn_balance WHERE uuid=? AND scope=?")) {
                    ps.setString(1, u.toString());
                    ps.setString(2, scope);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getDouble(1) : start;
                    }
                }
            });
            cache.put(key(u, scope), v);
            return v;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read balance", e);
            return start;
        }
    }

    public void set(UUID u, String scope, double value) {
        cache.put(key(u, scope), value);
        plugin.db().async(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("REPLACE INTO mwn_balance (uuid, scope, balance) VALUES (?,?,?)")) {
                ps.setString(1, u.toString());
                ps.setString(2, scope);
                ps.setDouble(3, value);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(t -> {
            plugin.getLogger().log(Level.WARNING, "Could not save balance", t);
            return null;
        });
    }

    public void forget(String scope) {
        cache.keySet().removeIf(k -> k.endsWith(":" + scope));
    }

    public static String format(MWNPlugin plugin, double v) {
        int digits = plugin.getConfig().getInt("economy.fractional-digits", 2);
        return String.format(java.util.Locale.ROOT, "%." + Math.max(0, digits) + "f", v);
    }

}
