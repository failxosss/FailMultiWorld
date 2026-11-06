package cz.failxos.mwn.hook;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.util.Placeholders;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** PlaceholderAPI expansion: %mwn_group%, %mwn_group_players_survival%, %mwn_feature_shop%, ... */
public final class MwnExpansion extends PlaceholderExpansion {
    private final MWNPlugin plugin;

    public MwnExpansion(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "mwn"; }
    @Override public String getAuthor() { return "Failxos"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return Placeholders.resolve(plugin, player, params);
    }
}
