package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import cz.failxos.mwn.util.Placeholders;
import cz.failxos.mwn.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-group sidebar scoreboard, tab header/footer/name and tab visibility. */
public final class BoardManager {
    private static final int MAX_LINES = 15;
    private final MWNPlugin plugin;
    private BukkitTask task;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, String> signatures = new HashMap<>();

    public BoardManager(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        int ticks = Math.max(5, plugin.getConfig().getInt("scoreboard.refresh-ticks", 20));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
        }, 20L, ticks);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    public void cleanup(UUID u) {
        boards.remove(u);
        signatures.remove(u);
    }

    public void refresh(Player p) {
        Group g = plugin.switcher().groupOf(p);
        if (plugin.getConfig().getBoolean("scoreboard.enabled", true) && (g == null || g.scoreboardEnabled)) {
            updateBoard(p, g);
        } else if (boards.remove(p.getUniqueId()) != null) {
            signatures.remove(p.getUniqueId());
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        if (plugin.getConfig().getBoolean("tablist.enabled", true) && (g == null || g.tablistEnabled)) updateTab(p);
    }

    private void updateBoard(Player p, Group g) {
        List<String> lines = g != null && !g.boardLines.isEmpty() ? g.boardLines : plugin.getConfig().getStringList("scoreboard.lines");
        String title = g != null && g.boardTitle != null ? g.boardTitle : plugin.getConfig().getString("scoreboard.title", "MWN");
        int n = Math.min(lines.size(), MAX_LINES);
        UUID u = p.getUniqueId();
        String sig = (g == null ? "-" : g.id) + ":" + n;
        Scoreboard sb = boards.get(u);
        if (sb == null || !sig.equals(signatures.get(u)) || p.getScoreboard() != sb) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = sb.registerNewObjective("mwn", Criteria.DUMMY, Component.text("MWN"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            for (int i = 0; i < n; i++) {
                String entry = "\u00a7" + Integer.toHexString(i) + "\u00a7r";
                Team t = sb.registerNewTeam("l" + i);
                t.addEntry(entry);
                obj.getScore(entry).setScore(n - i);
            }
            boards.put(u, sb);
            signatures.put(u, sig);
            p.setScoreboard(sb);
        }
        Objective obj = sb.getObjective("mwn");
        if (obj == null) return;
        obj.displayName(Text.parse(Placeholders.apply(plugin, p, title)));
        for (int i = 0; i < n; i++) {
            Team t = sb.getTeam("l" + i);
            if (t != null) t.prefix(Text.parse(Placeholders.apply(plugin, p, lines.get(i))));
        }
    }

    private void updateTab(Player p) {
        String header = String.join("<newline>", plugin.getConfig().getStringList("tablist.header"));
        String footer = String.join("<newline>", plugin.getConfig().getStringList("tablist.footer"));
        p.sendPlayerListHeaderAndFooter(Text.parse(Placeholders.apply(plugin, p, header)), Text.parse(Placeholders.apply(plugin, p, footer)));
        String fmt = plugin.getConfig().getString("tablist.name-format", "");
        if (fmt != null && !fmt.isEmpty()) p.playerListName(Text.parse(Placeholders.apply(plugin, p, fmt)));
    }

    /** Hides players of other virtual servers from this player (and vice versa) so tab lists stay separate. */
    public void applyVisibility(Player p) {
        if (!plugin.getConfig().getBoolean("tablist.hide-other-groups", true)) return;
        Group g = plugin.switcher().groupOf(p);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p) continue;
            if (plugin.switcher().groupOf(o) == g) {
                p.showPlayer(plugin, o);
                o.showPlayer(plugin, p);
            } else {
                p.hidePlayer(plugin, o);
                o.hidePlayer(plugin, p);
            }
        }
    }

    public void showAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p) p.showPlayer(plugin, o);
        }
    }
}
