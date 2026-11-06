package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.api.GroupCreateEvent;
import cz.failxos.mwn.api.WorldAssignEvent;
import cz.failxos.mwn.model.Group;
import cz.failxos.mwn.util.MwnException;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Holds all virtual servers (groups), persists them to groups.yml and indexes world -> group. */
public final class GroupManager {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{2,32}$");
    private final MWNPlugin plugin;
    private final File file;
    private final Map<String, Group> byKey = new ConcurrentHashMap<>();
    private final Map<String, Group> byId = new ConcurrentHashMap<>();
    private final Map<String, Group> byWorld = new ConcurrentHashMap<>();
    private int counter;

    public GroupManager(MWNPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "groups.yml");
    }

    public void load() {
        byKey.clear();
        byId.clear();
        if (!file.exists()) plugin.saveResource("groups.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        counter = y.getInt("counter", 0);
        ConfigurationSection root = y.getConfigurationSection("groups");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                String id = s.getString("id");
                if (id == null) id = nextId();
                Group g = new Group(id, key.toLowerCase(Locale.ROOT));
                g.displayName = s.getString("display-name", key);
                g.description = s.getString("description", "");
                g.icon = s.getString("icon", "GRASS_BLOCK");
                g.guiSlot = s.getInt("gui-slot", -1);
                g.difficulty = s.getString("difficulty", "NORMAL");
                g.defaultGamemode = s.getString("default-gamemode", "SURVIVAL");
                g.maintenance = s.getBoolean("maintenance", false);
                g.chatMuted = s.getBoolean("chat-muted", false);
                g.worlds.addAll(s.getStringList("worlds"));
                ConfigurationSection st = s.getConfigurationSection("settings");
                if (st != null) {
                    g.isolatedChat = st.getBoolean("isolated-chat", true);
                    g.separateInventory = st.getBoolean("separate-inventory", true);
                    g.separateEnderchest = st.getBoolean("separate-enderchest", true);
                    g.separateXp = st.getBoolean("separate-xp", true);
                    g.separateHealth = st.getBoolean("separate-health", true);
                    g.separateGamemode = st.getBoolean("separate-gamemode", true);
                    g.separateEconomy = st.getBoolean("separate-economy", false);
                }
                g.maxPlayers = s.getInt("limits.max-players", 0);
                if (s.isConfigurationSection("spawn")) {
                    g.hasSpawn = true;
                    g.spawnWorld = s.getString("spawn.world");
                    g.sx = s.getDouble("spawn.x");
                    g.sy = s.getDouble("spawn.y");
                    g.sz = s.getDouble("spawn.z");
                    g.syaw = (float) s.getDouble("spawn.yaw");
                    g.spitch = (float) s.getDouble("spawn.pitch");
                    if (g.spawnWorld == null) g.hasSpawn = false;
                }
                g.scoreboardEnabled = s.getBoolean("features.scoreboard", true);
                g.tablistEnabled = s.getBoolean("features.tablist", true);
                g.packUrl = s.getString("resource-pack.url", "");
                g.packHash = s.getString("resource-pack.hash", "");
                g.boardTitle = s.getString("scoreboard.title");
                g.boardLines = new ArrayList<>(s.getStringList("scoreboard.lines"));
                readBools(s.getConfigurationSection("rules"), g.rules);
                readBools(s.getConfigurationSection("plugin-isolation"), g.features);
                byKey.put(g.key, g);
                byId.put(g.id, g);
            }
        }
        reindex();
    }

    private static void readBools(ConfigurationSection s, Map<String, Boolean> out) {
        if (s == null) return;
        for (String k : s.getKeys(false)) out.put(k, s.getBoolean(k, true));
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("FailMultiWorld groups. Managed by /mwn commands; edit carefully and run /mwn reload.",
                "id = permanent identity (never change), key = name, worlds = assigned worlds."));
        y.set("counter", counter);
        for (Group g : all()) {
            String p = "groups." + g.key + ".";
            y.set(p + "id", g.id);
            y.set(p + "display-name", g.displayName);
            y.set(p + "description", g.description);
            y.set(p + "icon", g.icon);
            y.set(p + "gui-slot", g.guiSlot);
            y.set(p + "difficulty", g.difficulty);
            y.set(p + "default-gamemode", g.defaultGamemode);
            y.set(p + "maintenance", g.maintenance);
            y.set(p + "chat-muted", g.chatMuted);
            y.set(p + "worlds", new ArrayList<>(g.worlds));
            y.set(p + "settings.isolated-chat", g.isolatedChat);
            y.set(p + "settings.separate-inventory", g.separateInventory);
            y.set(p + "settings.separate-enderchest", g.separateEnderchest);
            y.set(p + "settings.separate-xp", g.separateXp);
            y.set(p + "settings.separate-health", g.separateHealth);
            y.set(p + "settings.separate-gamemode", g.separateGamemode);
            y.set(p + "settings.separate-economy", g.separateEconomy);
            y.set(p + "limits.max-players", g.maxPlayers);
            if (g.hasSpawn) {
                y.set(p + "spawn.world", g.spawnWorld);
                y.set(p + "spawn.x", g.sx);
                y.set(p + "spawn.y", g.sy);
                y.set(p + "spawn.z", g.sz);
                y.set(p + "spawn.yaw", g.syaw);
                y.set(p + "spawn.pitch", g.spitch);
            }
            y.set(p + "features.scoreboard", g.scoreboardEnabled);
            y.set(p + "features.tablist", g.tablistEnabled);
            if (!g.packUrl.isEmpty()) {
                y.set(p + "resource-pack.url", g.packUrl);
                y.set(p + "resource-pack.hash", g.packHash);
            }
            if (g.boardTitle != null) y.set(p + "scoreboard.title", g.boardTitle);
            if (!g.boardLines.isEmpty()) y.set(p + "scoreboard.lines", g.boardLines);
            for (Map.Entry<String, Boolean> e : g.rules.entrySet()) y.set(p + "rules." + e.getKey(), e.getValue());
            for (Map.Entry<String, Boolean> e : g.features.entrySet()) y.set(p + "plugin-isolation." + e.getKey(), e.getValue());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save groups.yml: " + e.getMessage());
        }
    }

    private void reindex() {
        byWorld.clear();
        for (Group g : byKey.values()) for (String w : g.worlds) byWorld.put(w.toLowerCase(Locale.ROOT), g);
    }

    private String nextId() {
        return String.format("server_%03d", ++counter);
    }

    public static boolean validName(String n) {
        return n != null && NAME.matcher(n).matches();
    }

    public List<Group> all() {
        List<Group> l = new ArrayList<>(byKey.values());
        l.sort(Comparator.comparing(g -> g.id));
        return l;
    }

    public Collection<Group> values() { return byKey.values(); }
    public Group get(String key) { return key == null ? null : byKey.get(key.toLowerCase(Locale.ROOT)); }
    public Group byId(String id) { return id == null ? null : byId.get(id); }
    public Group byWorld(String world) { return world == null ? null : byWorld.get(world.toLowerCase(Locale.ROOT)); }
    public int count() { return byKey.size(); }

    public Group create(String name) throws MwnException {
        if (!validName(name)) throw new MwnException("invalid-name", name);
        if (get(name) != null) throw new MwnException("group-exists", name);
        Group g = new Group(nextId(), name.toLowerCase(Locale.ROOT));
        g.displayName = name;
        byKey.put(g.key, g);
        byId.put(g.id, g);
        save();
        Bukkit.getPluginManager().callEvent(new GroupCreateEvent(g));
        return g;
    }

    public boolean assign(String world, Group g) {
        WorldAssignEvent ev = new WorldAssignEvent(world, g);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;
        Group old = byWorld(world);
        if (old != null) old.worlds.removeIf(w -> w.equalsIgnoreCase(world));
        g.worlds.add(world);
        reindex();
        save();
        applyWorldSettings(g);
        return true;
    }

    public boolean unassign(String world) {
        Group old = byWorld(world);
        if (old == null) return false;
        WorldAssignEvent ev = new WorldAssignEvent(world, null);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return false;
        old.worlds.removeIf(w -> w.equalsIgnoreCase(world));
        reindex();
        save();
        return true;
    }

    public void remove(Group g) {
        byKey.remove(g.key);
        byId.remove(g.id);
        reindex();
        save();
    }

    public void rename(Group g, String newKey) throws MwnException {
        if (!validName(newKey)) throw new MwnException("invalid-name", newKey);
        if (get(newKey) != null) throw new MwnException("group-exists", newKey);
        byKey.remove(g.key);
        String old = g.key;
        g.key = newKey.toLowerCase(Locale.ROOT);
        byKey.put(g.key, g);
        if (old.equalsIgnoreCase(plugin.getConfig().getString("lobby-group", ""))) {
            plugin.getConfig().set("lobby-group", g.key);
            plugin.saveConfig();
        }
        save();
    }

    public void applyWorldSettings(Group g) {
        for (String wn : g.worlds) {
            org.bukkit.World w = Bukkit.getWorld(wn);
            if (w == null) continue;
            try {
                w.setDifficulty(org.bukkit.Difficulty.valueOf(g.difficulty.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Group " + g.key + ": invalid difficulty '" + g.difficulty + "'");
            }
        }
    }

    public void applyAllWorldSettings() {
        for (Group g : all()) applyWorldSettings(g);
    }
}
