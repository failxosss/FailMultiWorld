package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Plugin isolation. Features are switches per group. Two real enforcement mechanisms are provided:
 * 1) command blocking: commands listed under isolation.features.&lt;Feature&gt;.commands are blocked in groups where the feature is off;
 * 2) the public API (MwnApi#isFeatureEnabled), which other plugins / adapters call from their own event handlers.
 * Arbitrary third-party plugins are never unloaded or patched.
 */
public final class IsolationManager implements Listener {
    private final MWNPlugin plugin;

    public IsolationManager(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled(Group g, String feature) {
        return g == null || g.feature(feature);
    }

    public Set<String> knownFeatures() {
        Set<String> s = new LinkedHashSet<>();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("isolation.features");
        if (c != null) s.addAll(c.getKeys(false));
        for (Group g : plugin.groups().values()) s.addAll(g.features.keySet());
        return s;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        Group g = plugin.switcher().groupOf(p);
        if (g == null || g.features.isEmpty()) return;
        String msg = e.getMessage();
        if (msg.length() < 2) return;
        String rawLabel = msg.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = rawLabel.indexOf(':');
        final String label = colon >= 0 ? rawLabel.substring(colon + 1) : rawLabel;
        ConfigurationSection feats = plugin.getConfig().getConfigurationSection("isolation.features");
        if (feats == null) return;
        for (String feature : feats.getKeys(false)) {
            List<String> cmds = feats.getStringList(feature + ".commands");
            if (!isEnabled(g, feature) && cmds.stream().anyMatch(c -> c.equalsIgnoreCase(label))) {
                e.setCancelled(true);
                plugin.msg().send(p, "feature-disabled", "feature", feature);
                return;
            }
        }
    }
}
