package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import cz.failxos.mwn.util.MwnException;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Cuboid portals that send players to a virtual server. Stored in portals.yml. */
public final class PortalManager implements Listener {
    public static final class Portal {
        public String name;
        public String world;
        public int x1, y1, z1, x2, y2, z2;
        public String group;
        public String permission = "";
        public int cooldown = 3;
        public boolean enabled = true;

        boolean contains(Location l) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            return x >= Math.min(x1, x2) && x <= Math.max(x1, x2) && y >= Math.min(y1, y2) && y <= Math.max(y1, y2)
                    && z >= Math.min(z1, z2) && z <= Math.max(z1, z2);
        }
    }

    private final MWNPlugin plugin;
    private final File file;
    private final Map<String, Portal> portals = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public PortalManager(MWNPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "portals.yml");
    }

    public void load() {
        portals.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("portals");
        if (s == null) return;
        for (String name : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(name);
            if (c == null) continue;
            Portal p = new Portal();
            p.name = name;
            p.world = c.getString("world", "");
            p.x1 = c.getInt("x1");
            p.y1 = c.getInt("y1");
            p.z1 = c.getInt("z1");
            p.x2 = c.getInt("x2");
            p.y2 = c.getInt("y2");
            p.z2 = c.getInt("z2");
            p.group = c.getString("group", "");
            p.permission = c.getString("permission", "");
            p.cooldown = c.getInt("cooldown", 3);
            p.enabled = c.getBoolean("enabled", true);
            portals.put(name.toLowerCase(Locale.ROOT), p);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Portal p : portals.values()) {
            String k = "portals." + p.name + ".";
            y.set(k + "world", p.world);
            y.set(k + "x1", p.x1);
            y.set(k + "y1", p.y1);
            y.set(k + "z1", p.z1);
            y.set(k + "x2", p.x2);
            y.set(k + "y2", p.y2);
            y.set(k + "z2", p.z2);
            y.set(k + "group", p.group);
            y.set(k + "permission", p.permission);
            y.set(k + "cooldown", p.cooldown);
            y.set(k + "enabled", p.enabled);
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save portals.yml: " + e.getMessage());
        }
    }

    public List<Portal> all() { return new ArrayList<>(portals.values()); }

    public void setPos(Player p, boolean first) {
        (first ? pos1 : pos2).put(p.getUniqueId(), p.getLocation().getBlock().getLocation());
    }

    public Portal create(Player p, String name, Group g) throws MwnException {
        if (!name.matches("[A-Za-z0-9_-]{2,32}")) throw new MwnException("invalid-name", name);
        if (portals.containsKey(name.toLowerCase(Locale.ROOT))) throw new MwnException("portal-exists", name);
        Location a = pos1.get(p.getUniqueId());
        Location b = pos2.get(p.getUniqueId());
        if (a == null || b == null || a.getWorld() == null || !a.getWorld().equals(b.getWorld())) throw new MwnException("portal-no-selection");
        Portal portal = new Portal();
        portal.name = name;
        portal.world = a.getWorld().getName();
        portal.x1 = a.getBlockX();
        portal.y1 = a.getBlockY();
        portal.z1 = a.getBlockZ();
        portal.x2 = b.getBlockX();
        portal.y2 = b.getBlockY();
        portal.z2 = b.getBlockZ();
        portal.group = g.key;
        portal.permission = "mwn.portal." + name.toLowerCase(Locale.ROOT);
        portals.put(name.toLowerCase(Locale.ROOT), portal);
        save();
        return portal;
    }

    public boolean delete(String name) {
        boolean r = portals.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (r) save();
        return r;
    }

    public Portal get(String name) { return portals.get(name.toLowerCase(Locale.ROOT)); }

    public void toggle(Portal p) {
        p.enabled = !p.enabled;
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        Location from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) return;
        if (portals.isEmpty()) return;
        Player p = e.getPlayer();
        for (Portal portal : portals.values()) {
            if (!portal.enabled || !portal.contains(to)) continue;
            Group g = plugin.groups().get(portal.group);
            if (g == null) return;
            if (!portal.permission.isEmpty() && !p.hasPermission(portal.permission) && !p.hasPermission("mwn.portal.*")) {
                plugin.msg().send(p, "portal-no-permission");
                return;
            }
            String ck = p.getUniqueId() + ":" + portal.name;
            long now = System.currentTimeMillis();
            Long last = cooldowns.get(ck);
            if (last != null && now - last < portal.cooldown * 1000L) return;
            cooldowns.put(ck, now);
            if (plugin.switcher().groupOf(p) == g) return;
            plugin.switcher().switchTo(p, g, true, false);
            return;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        pos1.remove(u);
        pos2.remove(u);
        cooldowns.keySet().removeIf(k -> k.startsWith(u.toString()));
    }
}
