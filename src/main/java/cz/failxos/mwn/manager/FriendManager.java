package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.data.Database;
import cz.failxos.mwn.model.Group;
import cz.failxos.mwn.util.MwnException;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Optional friends system: requests (per session), persistent friend list, online status and the friend's current server. */
public final class FriendManager {
    private final MWNPlugin plugin;
    private final java.util.Map<UUID, Set<UUID>> friends = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Set<UUID>> requests = new ConcurrentHashMap<>();

    public FriendManager(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("friends.enabled", true);
    }

    public Set<UUID> friendsOf(UUID u) {
        return friends.computeIfAbsent(u, k -> ConcurrentHashMap.newKeySet());
    }

    public void load(Player p) {
        if (!enabled()) return;
        UUID u = p.getUniqueId();
        plugin.db().async(c -> {
            Set<UUID> s = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT friend FROM mwn_friends WHERE uuid=?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            s.add(UUID.fromString(rs.getString(1)));
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
            }
            return s;
        }).whenComplete((s, t) -> {
            if (t != null) {
                plugin.getLogger().log(Level.WARNING, "Could not load friends of " + p.getName(), t);
                return;
            }
            friendsOf(u).addAll(s);
        });
        notifyFriends(p, "friend-joined");
    }

    public void unload(Player p) {
        notifyFriends(p, "friend-left");
        friends.remove(p.getUniqueId());
        requests.remove(p.getUniqueId());
    }

    private void notifyFriends(Player p, String key) {
        if (!enabled() || !plugin.getConfig().getBoolean("friends.notifications", true)) return;
        for (UUID f : friendsOf(p.getUniqueId())) {
            Player o = Bukkit.getPlayer(f);
            if (o != null) plugin.msg().send(o, key, "player", p.getName());
        }
    }

    private void checkEnabled() throws MwnException {
        if (!enabled()) throw new MwnException("friends-disabled");
    }

    public void request(Player from, Player to) throws MwnException {
        checkEnabled();
        if (from.getUniqueId().equals(to.getUniqueId())) throw new MwnException("friend-self");
        Set<UUID> mine = friendsOf(from.getUniqueId());
        if (mine.contains(to.getUniqueId())) throw new MwnException("friend-already", "player", to.getName());
        if (mine.size() >= plugin.getConfig().getInt("friends.max", 50)) throw new MwnException("friend-limit");
        Set<UUID> reqs = requests.computeIfAbsent(to.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        if (!reqs.add(from.getUniqueId())) throw new MwnException("friend-request-pending", "player", to.getName());
        plugin.msg().send(from, "friend-request-sent", "player", to.getName());
        plugin.msg().send(to, "friend-request-received", "player", from.getName());
    }

    public void accept(Player p, String name) throws MwnException {
        checkEnabled();
        Player from = Bukkit.getPlayerExact(name);
        Set<UUID> reqs = requests.get(p.getUniqueId());
        if (from == null || reqs == null || !reqs.remove(from.getUniqueId())) throw new MwnException("friend-no-request", "player", name);
        if (friendsOf(p.getUniqueId()).size() >= plugin.getConfig().getInt("friends.max", 50)) throw new MwnException("friend-limit");
        link(p.getUniqueId(), from.getUniqueId(), true);
        plugin.msg().send(p, "friend-added", "player", from.getName());
        plugin.msg().send(from, "friend-added", "player", p.getName());
    }

    public void deny(Player p, String name) throws MwnException {
        checkEnabled();
        Player from = Bukkit.getPlayerExact(name);
        Set<UUID> reqs = requests.get(p.getUniqueId());
        if (from == null || reqs == null || !reqs.remove(from.getUniqueId())) throw new MwnException("friend-no-request", "player", name);
        plugin.msg().send(p, "friend-denied", "player", from.getName());
    }

    public void remove(Player p, String name) throws MwnException {
        checkEnabled();
        UUID target = find(p, name);
        if (target == null) throw new MwnException("friend-not-found", "player", name);
        link(p.getUniqueId(), target, false);
        plugin.msg().send(p, "friend-removed", "player", name);
    }

    private UUID find(Player p, String name) {
        for (UUID f : friendsOf(p.getUniqueId())) {
            String n = Bukkit.getOfflinePlayer(f).getName();
            if (n != null && n.equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    private void link(UUID a, UUID b, boolean add) {
        if (add) {
            friendsOf(a).add(b);
            friendsOf(b).add(a);
        } else {
            friendsOf(a).remove(b);
            friendsOf(b).remove(a);
        }
        plugin.db().async(c -> Database.inTx(c, tx -> {
            String sql = add ? "REPLACE INTO mwn_friends (uuid, friend) VALUES (?,?)" : "DELETE FROM mwn_friends WHERE uuid=? AND friend=?";
            try (PreparedStatement ps = tx.prepareStatement(sql)) {
                ps.setString(1, a.toString());
                ps.setString(2, b.toString());
                ps.executeUpdate();
                ps.setString(1, b.toString());
                ps.setString(2, a.toString());
                ps.executeUpdate();
            }
            return null;
        })).exceptionally(t -> {
            plugin.getLogger().log(Level.WARNING, "Could not save friend change", t);
            return null;
        });
    }

    public void list(Player p) throws MwnException {
        checkEnabled();
        Set<UUID> set = friendsOf(p.getUniqueId());
        plugin.msg().send(p, "friend-list-header", "count", set.size());
        boolean showServer = plugin.getConfig().getBoolean("friends.show-server", true);
        for (UUID f : set) {
            OfflinePlayer o = Bukkit.getOfflinePlayer(f);
            String name = o.getName() == null ? f.toString().substring(0, 8) : o.getName();
            Player on = Bukkit.getPlayer(f);
            if (on == null) {
                plugin.msg().send(p, "friend-list-offline", "player", name);
            } else {
                Group g = plugin.switcher().groupOf(on);
                plugin.msg().send(p, "friend-list-online", "player", name,
                        "server", showServer ? (g == null ? "-" : g.plainName()) : plugin.msg().raw("friend-hidden"));
            }
        }
    }

    public void join(Player p, String name) throws MwnException {
        checkEnabled();
        UUID target = find(p, name);
        Player on = target == null ? null : Bukkit.getPlayer(target);
        if (target == null) throw new MwnException("friend-not-found", "player", name);
        if (on == null) throw new MwnException("friend-offline", "player", name);
        if (!plugin.getConfig().getBoolean("friends.show-server", true)) throw new MwnException("friend-hidden-server");
        Group g = plugin.switcher().groupOf(on);
        if (g == null) throw new MwnException("friend-offline", "player", name);
        plugin.switcher().switchTo(p, g, true, false);
    }
}
