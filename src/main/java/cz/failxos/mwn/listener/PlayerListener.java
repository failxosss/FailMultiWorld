package cz.failxos.mwn.listener;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.data.Database;
import cz.failxos.mwn.model.Group;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class PlayerListener implements Listener {
    private final MWNPlugin plugin;

    public PlayerListener(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            plugin.switcher().joinCache.put(e.getUniqueId(), plugin.db().readJoinInfo(e.getUniqueId()));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Could not preload data for " + e.getName(), ex);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent e) {
        attach(e.getPlayer());
    }

    /** Also used for players already online when the plugin starts. */
    public void attach(Player p) {
        UUID u = p.getUniqueId();
        Database.JoinInfo info = plugin.switcher().joinCache.remove(u);
        if (info == null) {
            try {
                info = plugin.db().readJoinInfo(u);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Could not read data for " + p.getName(), ex);
                info = new Database.JoinInfo(null, null, Set.of());
            }
        }
        plugin.chat().setIgnores(u, info.ignores());
        plugin.switcher().handleJoin(p, info);
        plugin.friends().load(p);
        plugin.stats().load(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.switcher().handleQuit(p);
        plugin.chat().cleanup(p.getUniqueId());
        plugin.boards().cleanup(p.getUniqueId());
        plugin.friends().unload(p);
        plugin.stats().unload(p.getUniqueId());
    }

    @EventHandler
    public void onPack(org.bukkit.event.player.PlayerResourcePackStatusEvent e) {
        var st = e.getStatus();
        if (st == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DECLINED
                || st == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            plugin.msg().send(e.getPlayer(), "pack-failed");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Group wg = plugin.groups().byWorld(p.getWorld().getName());
        Group cur = plugin.switcher().groupOf(p);
        if (wg != null && wg != cur && !plugin.switcher().isSwitching(p.getUniqueId())) {
            plugin.switcher().switchTo(p, wg, false, true);
        }
    }
}
