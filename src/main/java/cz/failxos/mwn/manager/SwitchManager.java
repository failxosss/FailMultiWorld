package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.api.GroupSwitchEvent;
import cz.failxos.mwn.data.Database;
import cz.failxos.mwn.data.Snapshot;
import cz.failxos.mwn.model.Group;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Tracks which virtual server each player is in and performs safe, transactional switches. */
public final class SwitchManager {
    private record SaveEntry(UUID uuid, Group group, Map<String, String> snap) {}

    private final MWNPlugin plugin;
    private final Map<UUID, String> current = new ConcurrentHashMap<>();
    private final Set<UUID> switching = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastSwitch = new ConcurrentHashMap<>();
    private final Map<UUID, String> switchTarget = new ConcurrentHashMap<>();
    public final Map<UUID, Database.JoinInfo> joinCache = new ConcurrentHashMap<>();
    private BukkitTask autosave;

    public SwitchManager(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public Group groupOf(Player p) {
        String id = current.get(p.getUniqueId());
        return id == null ? null : plugin.groups().byId(id);
    }

    public int playersIn(Group g) {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (groupOf(p) == g) n++;
        return n;
    }

    /** Players in the group plus players currently switching into it (so a slot is never given out twice). */
    public int occupancy(Group g) {
        int n = playersIn(g);
        for (String id : switchTarget.values()) if (id.equals(g.id)) n++;
        return n;
    }

    public int activeTransfers() { return switching.size(); }
    public boolean isSwitching(UUID u) { return switching.contains(u); }

    public void startAutosave() {
        int minutes = Math.max(1, plugin.getConfig().getInt("autosave-minutes", 5));
        long ticks = minutes * 60L * 20L;
        autosave = Bukkit.getScheduler().runTaskTimer(plugin, this::autosaveNow, ticks, ticks);
    }

    public void stopAutosave() {
        if (autosave != null) autosave.cancel();
    }

    private List<SaveEntry> collect() {
        List<SaveEntry> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (switching.contains(p.getUniqueId())) continue;
            Group g = groupOf(p);
            list.add(new SaveEntry(p.getUniqueId(), g, Snapshot.capture(p, g)));
        }
        return list;
    }

    private static Void saveBatch(java.sql.Connection c, List<SaveEntry> list) throws Exception {
        return Database.inTx(c, tx -> {
            for (SaveEntry e : list) {
                for (Map.Entry<String, String> d : e.snap().entrySet()) {
                    Database.saveData(tx, e.uuid(), d.getKey(), Snapshot.profile(e.group(), d.getKey()), d.getValue());
                }
            }
            return null;
        });
    }

    private void autosaveNow() {
        List<SaveEntry> list = collect();
        if (list.isEmpty()) return;
        plugin.db().async(c -> saveBatch(c, list)).exceptionally(t -> {
            plugin.getLogger().log(Level.WARNING, "Autosave failed", t);
            return null;
        });
    }

    /** Synchronous save used on shutdown. */
    public void saveAllNow() {
        List<SaveEntry> list = collect();
        if (list.isEmpty()) return;
        try {
            plugin.db().sync(c -> saveBatch(c, list));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save player data on shutdown", e);
        }
    }

    // ---------- join / quit ----------

    public void handleJoin(Player p, Database.JoinInfo info) {
        UUID u = p.getUniqueId();
        Group tracked = null;
        boolean known = false;
        if (info != null) {
            if (info.pendingFrom() != null) {
                // unfinished transfer (quit / crash): the old group's state was saved first, so fall back to it
                tracked = plugin.groups().byId(info.pendingFrom());
                known = true;
                plugin.db().async(c -> {
                    Database.clearPending(c, u);
                    return null;
                });
            } else if (info.groupId() != null) {
                tracked = plugin.groups().byId(info.groupId());
                known = true;
            }
        }
        Group worldGroup = plugin.groups().byWorld(p.getWorld().getName());
        if (tracked == null && worldGroup != null) tracked = worldGroup;
        if (tracked != null) {
            current.put(u, tracked.id);
            final String id = tracked.id;
            plugin.db().async(c -> {
                Database.setPlayerGroup(c, u, id);
                return null;
            });
        }
        if (tracked != null && worldGroup != null && worldGroup != tracked) {
            switchTo(p, worldGroup, false, true);
        } else if (!known && tracked == null) {
            Group lobby = plugin.groups().get(plugin.getConfig().getString("lobby-group", ""));
            if (lobby != null) switchTo(p, lobby, true, true);
        }
        plugin.boards().refresh(p);
        plugin.boards().applyVisibility(p);
    }

    public void handleQuit(Player p) {
        UUID u = p.getUniqueId();
        if (!switching.contains(u)) {
            Group g = groupOf(p);
            Map<String, String> snap = Snapshot.capture(p, g);
            plugin.db().async(c -> saveBatch(c, List.of(new SaveEntry(u, g, snap)))).exceptionally(t -> {
                plugin.getLogger().log(Level.WARNING, "Could not save data of " + p.getName(), t);
                return null;
            });
        }
        current.remove(u);
        lastSwitch.remove(u);
        plugin.queue().remove(u);
    }

    // ---------- switching ----------

    private boolean check(Player p, Group to) {
        if (plugin.getConfig().getBoolean("require-group-permission", false) && !p.hasPermission("mwn.group." + to.key)
                && !p.hasPermission("mwn.group.*")) {
            plugin.msg().send(p, "no-group-permission", "group", to.display());
            return false;
        }
        if (to.maintenance && !p.hasPermission("mwn.bypass.maintenance")) {
            plugin.msg().send(p, "maintenance", "group", to.display());
            return false;
        }
        if (to.maxPlayers > 0 && occupancy(to) >= to.maxPlayers && !p.hasPermission("mwn.bypass.limit")) {
            if (plugin.queue().enabled()) plugin.queue().enqueue(p, to);
            else plugin.msg().send(p, "group-full", "group", to.display());
            return false;
        }
        int cd = plugin.getConfig().getInt("transfer.cooldown-seconds", 3);
        Long last = lastSwitch.get(p.getUniqueId());
        if (cd > 0 && last != null && !p.hasPermission("mwn.bypass.cooldown")) {
            long left = cd - (System.currentTimeMillis() - last) / 1000L;
            if (left > 0) {
                plugin.msg().send(p, "transfer-cooldown", "seconds", left);
                return false;
            }
        }
        return true;
    }

    public CompletableFuture<Boolean> switchTo(Player p, Group to, boolean teleport, boolean force) {
        UUID u = p.getUniqueId();
        Group from = groupOf(p);
        if (from == to) {
            if (!force) plugin.msg().send(p, "already-in-group", "group", to.display());
            return CompletableFuture.completedFuture(false);
        }
        if (!force && !check(p, to)) return CompletableFuture.completedFuture(false);
        if (teleport && to.spawnLocation() == null) {
            plugin.msg().send(p, "no-worlds", "group", to.display());
            return CompletableFuture.completedFuture(false);
        }
        GroupSwitchEvent ev = new GroupSwitchEvent(p, from, to, teleport);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return CompletableFuture.completedFuture(false);
        if (!switching.add(u)) {
            if (!force) plugin.msg().send(p, "transfer-busy");
            return CompletableFuture.completedFuture(false);
        }
        switchTarget.put(u, to.id);
        plugin.debug("Switch " + p.getName() + ": " + (from == null ? "-" : from.key) + " -> " + to.key);
        Map<String, String> snap = Snapshot.capture(p, from);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        plugin.db().async(c -> transaction(c, u, from, to, snap)).whenComplete((loaded, err) -> {
            if (!plugin.isEnabled()) {
                switching.remove(u);
                switchTarget.remove(u);
                result.complete(false);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> complete(p, from, to, teleport, loaded, err, result));
        });
        return result;
    }

    private Map<String, String> transaction(java.sql.Connection c, UUID u, Group from, Group to, Map<String, String> snap) throws Exception {
        return Database.inTx(c, tx -> {
            Database.setPending(tx, u, from == null ? "" : from.id, to.id);
            Map<String, String> loaded = new HashMap<>();
            for (String cat : Snapshot.CATEGORIES) {
                String pf = Snapshot.profile(from, cat);
                String pt = Snapshot.profile(to, cat);
                if (pf.equals(pt)) continue;
                String saved = snap.get(cat);
                if (saved != null) Database.saveData(tx, u, cat, pf, saved);
                loaded.put(cat, Database.loadData(tx, u, cat, pt));
            }
            return loaded;
        });
    }

    private void complete(Player p, Group from, Group to, boolean teleport, Map<String, String> loaded,
                          Throwable err, CompletableFuture<Boolean> result) {
        UUID u = p.getUniqueId();
        try {
            if (err != null) {
                plugin.getLogger().log(Level.WARNING, "Transfer failed for " + p.getName() + " (state unchanged)", err);
                if (p.isOnline()) plugin.msg().send(p, "transfer-failed");
                result.complete(false);
                return;
            }
            if (!p.isOnline()) {
                result.complete(false);
                return;
            }
            Snapshot.apply(p, to, loaded);
            current.put(u, to.id);
            plugin.queue().remove(u);
            lastSwitch.put(u, System.currentTimeMillis());
            plugin.db().async(c -> {
                Database.setPlayerGroup(c, u, to.id);
                Database.clearPending(c, u);
                return null;
            });
            p.setFireTicks(0);
            p.setFallDistance(0f);
            Location dest = teleport ? resolveDestination(to, loaded.get(Snapshot.LOCATION)) : null;
            if (dest != null) {
                p.teleportAsync(dest).whenComplete((ok, t) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    afterSwitch(p, from, to);
                    result.complete(ok != null && ok);
                }));
            } else {
                afterSwitch(p, from, to);
                result.complete(true);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Transfer apply failed for " + p.getName(), t);
            result.complete(false);
        } finally {
            switching.remove(u);
            switchTarget.remove(u);
        }
    }

    private Location resolveDestination(Group to, String data) {
        if (plugin.getConfig().getBoolean("transfer.restore-last-location", false) && data != null) {
            String[] a = data.split(";");
            if (a.length >= 6 && to.hasWorld(a[0])) {
                World w = Bukkit.getWorld(a[0]);
                if (w != null) {
                    try {
                        return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                                Float.parseFloat(a[4]), Float.parseFloat(a[5]));
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        return to.spawnLocation();
    }

    private void afterSwitch(Player p, Group from, Group to) {
        plugin.msg().send(p, "switched", "group", to.display());
        String snd = plugin.getConfig().getString("transfer.sound", "");
        if (snd != null && !snd.isEmpty()) p.playSound(p.getLocation(), snd, 1f, 1f);
        if (plugin.getConfig().getBoolean("transfer.notify-groups", true)) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o == p) continue;
                Group og = groupOf(o);
                if (from != null && og == from) plugin.msg().send(o, "player-left-group", "player", p.getName());
                else if (og == to) plugin.msg().send(o, "player-joined-group", "player", p.getName());
            }
        }
        plugin.boards().refresh(p);
        plugin.boards().applyVisibility(p);
        applyPack(p, from, to);
    }

    /** Sends the destination's resource pack when it differs from the source's. A client can only hold one pack set at a time. */
    private void applyPack(Player p, Group from, Group to) {
        String url = to.packUrl == null ? "" : to.packUrl;
        String old = from == null || from.packUrl == null ? "" : from.packUrl;
        if (url.isEmpty() || url.equals(old)) return;
        byte[] hash = null;
        String hex = to.packHash == null ? "" : to.packHash.trim();
        if (hex.length() == 40) {
            try {
                hash = new byte[20];
                for (int i = 0; i < 20; i++) hash[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            } catch (NumberFormatException e) {
                hash = null;
            }
        }
        plugin.msg().send(p, "pack-loading", "group", to.display());
        p.setResourcePack(url, hash);
    }
}
