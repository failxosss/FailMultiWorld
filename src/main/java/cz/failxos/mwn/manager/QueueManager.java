package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

/** Optional queue for full virtual servers. Permission mwn.queue.priority moves players ahead of normal ones. */
public final class QueueManager {
    private record Entry(UUID uuid, boolean priority) {}

    public record Where(Group group, int position) {}

    private final MWNPlugin plugin;
    private final Map<String, LinkedList<Entry>> queues = new HashMap<>();
    private BukkitTask task;
    private int runs;

    public QueueManager(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("queue.enabled", true);
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::process, 40L, 40L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    public void enqueue(Player p, Group g) {
        remove(p.getUniqueId());
        LinkedList<Entry> q = queues.computeIfAbsent(g.id, k -> new LinkedList<>());
        boolean prio = p.hasPermission("mwn.queue.priority");
        int idx = q.size();
        if (prio) {
            idx = 0;
            for (Entry e : q) {
                if (!e.priority()) break;
                idx++;
            }
        }
        q.add(idx, new Entry(p.getUniqueId(), prio));
        plugin.msg().send(p, "queue-joined", "group", g.display(), "position", idx + 1);
    }

    public boolean remove(UUID u) {
        boolean removed = false;
        for (LinkedList<Entry> q : queues.values()) removed |= q.removeIf(e -> e.uuid().equals(u));
        return removed;
    }

    public Where where(UUID u) {
        for (Map.Entry<String, LinkedList<Entry>> en : queues.entrySet()) {
            int i = 1;
            for (Entry e : en.getValue()) {
                if (e.uuid().equals(u)) {
                    Group g = plugin.groups().byId(en.getKey());
                    return g == null ? null : new Where(g, i);
                }
                i++;
            }
        }
        return null;
    }

    private void process() {
        runs++;
        int every = Math.max(1, plugin.getConfig().getInt("queue.notify-seconds", 30) / 2);
        boolean notifyNow = runs % every == 0;
        for (Group g : plugin.groups().all()) {
            LinkedList<Entry> q = queues.get(g.id);
            if (q == null || q.isEmpty() || g.maintenance) continue;
            while (!q.isEmpty() && (g.maxPlayers <= 0 || plugin.switcher().occupancy(g) < g.maxPlayers)) {
                Entry e = q.poll();
                Player p = Bukkit.getPlayer(e.uuid());
                if (p == null || plugin.switcher().groupOf(p) == g) continue;
                plugin.msg().send(p, "queue-slot", "group", g.display());
                plugin.switcher().switchTo(p, g, true, false);
            }
            if (notifyNow) {
                int i = 1;
                for (Entry e : q) {
                    Player p = Bukkit.getPlayer(e.uuid());
                    if (p != null) plugin.msg().send(p, "queue-position", "group", g.display(), "position", i);
                    i++;
                }
            }
        }
    }
}
