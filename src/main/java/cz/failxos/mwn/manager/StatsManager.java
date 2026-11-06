package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.data.Database;
import cz.failxos.mwn.model.Group;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Playtime per virtual server (minute resolution). Cached in memory, written to the database every minute. */
public final class StatsManager {
    private final MWNPlugin plugin;
    private final Map<UUID, Map<String, Long>> cache = new ConcurrentHashMap<>();
    private BukkitTask task;

    public StatsManager(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1200L, 1200L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    public void load(Player p) {
        UUID u = p.getUniqueId();
        plugin.db().async(c -> {
            Map<String, Long> m = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT group_id, seconds FROM mwn_playtime WHERE uuid=?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) m.put(rs.getString(1), rs.getLong(2));
                }
            }
            return m;
        }).whenComplete((m, t) -> {
            if (t != null) {
                plugin.getLogger().log(Level.WARNING, "Could not load playtime of " + p.getName(), t);
                return;
            }
            Map<String, Long> c = new ConcurrentHashMap<>(m);
            Map<String, Long> old = cache.put(u, c);
            if (old != null) old.forEach((k, v) -> c.merge(k, v, Long::sum));
        });
    }

    public void unload(UUID u) {
        cache.remove(u);
    }

    private void tick() {
        List<String[]> rows = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Long> m = cache.get(p.getUniqueId());
            if (m == null) continue;
            Group g = plugin.switcher().groupOf(p);
            String gid = g == null ? "none" : g.id;
            m.merge(gid, 60L, Long::sum);
            rows.add(new String[]{p.getUniqueId().toString(), gid});
        }
        if (rows.isEmpty()) return;
        plugin.db().async(c -> Database.inTx(c, tx -> {
            for (String[] r : rows) {
                int n;
                try (PreparedStatement ps = tx.prepareStatement("UPDATE mwn_playtime SET seconds = seconds + 60 WHERE uuid=? AND group_id=?")) {
                    ps.setString(1, r[0]);
                    ps.setString(2, r[1]);
                    n = ps.executeUpdate();
                }
                if (n == 0) {
                    try (PreparedStatement ps = tx.prepareStatement("INSERT INTO mwn_playtime (uuid, group_id, seconds) VALUES (?,?,60)")) {
                        ps.setString(1, r[0]);
                        ps.setString(2, r[1]);
                        ps.executeUpdate();
                    }
                }
            }
            return null;
        })).exceptionally(t -> {
            plugin.getLogger().log(Level.WARNING, "Could not save playtime", t);
            return null;
        });
    }

    public Map<String, Long> of(UUID u) {
        Map<String, Long> m = cache.get(u);
        return m == null ? Map.of() : m;
    }

    public long current(Player p) {
        Group g = plugin.switcher().groupOf(p);
        return of(p.getUniqueId()).getOrDefault(g == null ? "none" : g.id, 0L);
    }

    public long total(Player p) {
        long t = 0;
        for (long v : of(p.getUniqueId()).values()) t += v;
        return t;
    }

    public static String format(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + "h " + m + "m";
    }
}
