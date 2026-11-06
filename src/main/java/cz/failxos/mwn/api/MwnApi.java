package cz.failxos.mwn.api;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import cz.failxos.mwn.util.MwnException;
import cz.failxos.mwn.util.Placeholders;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Public API. Obtain with {@link #get()} (or via the Bukkit ServicesManager).
 * Example (isolating a feature of your own plugin):
 * <pre>
 *   if (!MwnApi.get().isFeatureEnabled(player, "PlaytimeRewards")) return; // skip counting in this group
 * </pre>
 */
public final class MwnApi {
    private static MwnApi instance;
    private final MWNPlugin plugin;

    private MwnApi(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public static void init(MWNPlugin plugin) { instance = new MwnApi(plugin); }
    public static MwnApi get() { return instance; }

    public Collection<Group> getGroups() { return plugin.groups().all(); }
    public Group getGroup(String name) { return plugin.groups().get(name); }
    public Group getGroupById(String id) { return plugin.groups().byId(id); }
    public Group getPlayerGroup(Player p) { return plugin.switcher().groupOf(p); }
    public Group getGroupOfWorld(World w) { return plugin.groups().byWorld(w.getName()); }
    public int getPlayerCount(Group g) { return plugin.switcher().playersIn(g); }

    /** Creates an empty group (no worlds). */
    public Group createGroup(String name) throws MwnException { return plugin.groups().create(name); }

    public boolean assignWorld(World w, Group g) { return plugin.groups().assign(w.getName(), g); }
    public boolean unassignWorld(World w) { return plugin.groups().unassign(w.getName()); }

    /** Switch with all checks (permissions, limits, maintenance, cooldown) and teleport to the group's spawn. */
    public CompletableFuture<Boolean> switchPlayer(Player p, Group g) { return plugin.switcher().switchTo(p, g, true, false); }

    /** Switch ignoring limits / maintenance / cooldown. */
    public CompletableFuture<Boolean> forceSwitchPlayer(Player p, Group g, boolean teleport) {
        return plugin.switcher().switchTo(p, g, teleport, true);
    }

    public void sendGroupMessage(Group g, Component message) { plugin.chat().announce(g, message); }

    public boolean isFeatureEnabled(Group g, String feature) { return plugin.isolation().isEnabled(g, feature); }
    public boolean isFeatureEnabled(Player p, String feature) { return plugin.isolation().isEnabled(getPlayerGroup(p), feature); }

    /** Registers %mwn_&lt;name&gt;% (lower case) for PlaceholderAPI and MWN's own text. */
    public void registerPlaceholder(String name, Function<OfflinePlayer, String> resolver) { Placeholders.registerCustom(name, resolver); }
}
