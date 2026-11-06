package cz.failxos.mwn.command;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /server [group] opens the selector or switches; /servers lists all virtual servers. */
public final class ServerCommand implements TabExecutor {
    private final MWNPlugin plugin;
    private final MwnCommand mwn;

    public ServerCommand(MWNPlugin plugin, MwnCommand mwn) {
        this.plugin = plugin;
        this.mwn = mwn;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("mwn.server")) {
            plugin.msg().send(s, "no-permission");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("servers")) {
            plugin.msg().send(s, "list-header", "count", plugin.groups().count());
            for (Group g : plugin.groups().all()) s.sendMessage(mwn.entry(g));
            return true;
        }
        if (!(s instanceof Player p)) {
            plugin.msg().send(s, "players-only");
            return true;
        }
        if (a.length == 0) {
            plugin.selector().open(p);
            return true;
        }
        Group g = plugin.groups().get(a[0]);
        if (g == null) {
            plugin.msg().send(s, "unknown-group", "group", a[0]);
            return true;
        }
        plugin.switcher().switchTo(p, g, true, false);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1 && cmd.getName().equalsIgnoreCase("server")) {
            for (Group g : plugin.groups().values()) {
                if (g.key.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(g.key);
            }
        }
        return out;
    }
}
