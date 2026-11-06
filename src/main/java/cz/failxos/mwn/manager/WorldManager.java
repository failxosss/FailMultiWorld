package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.api.GroupCreateEvent;
import cz.failxos.mwn.model.Group;
import cz.failxos.mwn.util.MwnException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Creates, imports, deletes worlds and suggests which worlds belong together. */
public final class WorldManager {
    public record Suggestion(String world, Group group, double confidence, String reason) {}

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{2,40}$");
    private static final Pattern DIM_SUFFIX = Pattern.compile("[_\\-.](the_end|nether|end)$");

    private final MWNPlugin plugin;
    private final File file;
    private YamlConfiguration yml;

    public WorldManager(MWNPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "worlds.yml");
        this.yml = YamlConfiguration.loadConfiguration(file);
    }

    /** Loads every world registered in worlds.yml. Call once the server finished loading. */
    public void loadManagedWorlds() {
        yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("worlds");
        if (s == null) return;
        for (String name : s.getKeys(false)) {
            if (Bukkit.getWorld(name) != null) continue;
            try {
                WorldCreator wc = new WorldCreator(name).environment(World.Environment.valueOf(s.getString(name + ".environment", "NORMAL")));
                if (s.contains(name + ".seed")) wc.seed(s.getLong(name + ".seed"));
                World w = wc.createWorld();
                if (w == null) plugin.getLogger().warning("Could not load world " + name);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not load world " + name + ": " + e.getMessage());
            }
        }
    }

    private void register(World w) {
        yml.set("worlds." + w.getName() + ".environment", w.getEnvironment().name());
        yml.set("worlds." + w.getName() + ".seed", w.getSeed());
        saveYml();
    }

    private void unregister(String name) {
        yml.set("worlds." + name, null);
        saveYml();
    }

    private void saveYml() {
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save worlds.yml: " + e.getMessage());
        }
    }

    public static boolean validWorldName(String n) {
        return n != null && NAME.matcher(n).matches();
    }

    private boolean isProtectedWorld(String name) {
        String def = Bukkit.getWorlds().get(0).getName();
        return name.equalsIgnoreCase(def) || name.equalsIgnoreCase(def + "_nether") || name.equalsIgnoreCase(def + "_the_end");
    }

    /** Creates a new world (or imports an existing folder when allowExisting). Main thread. */
    public World createWorld(String name, World.Environment env, Long seed, boolean allowExisting) throws MwnException {
        if (!validWorldName(name)) throw new MwnException("invalid-name", name);
        if (Bukkit.getWorld(name) != null) throw new MwnException("world-exists", name);
        File folder = new File(Bukkit.getWorldContainer(), name);
        if (folder.exists() && !allowExisting) throw new MwnException("world-folder-exists", name);
        if (!folder.exists() && allowExisting) throw new MwnException("world-folder-missing", name);
        WorldCreator wc = new WorldCreator(name).environment(env);
        if (seed != null) wc.seed(seed);
        World w = wc.createWorld();
        if (w == null) throw new MwnException("world-create-failed", name);
        register(w);
        return w;
    }

    /** /mwn create: new world + new group + assignment + spawn. */
    public Group createGroupWithWorld(String name) throws MwnException {
        if (!GroupManager.validName(name)) throw new MwnException("invalid-name", name);
        if (plugin.groups().get(name) != null) throw new MwnException("group-exists", name);
        World w = createWorld(name, World.Environment.NORMAL, null, false);
        Group g = plugin.groups().create(name);
        plugin.groups().assign(w.getName(), g);
        Location sp = w.getSpawnLocation();
        sp.add(0.5, 0, 0.5);
        g.setSpawn(sp);
        plugin.groups().save();
        return g;
    }

    public List<Suggestion> suggest() {
        List<Suggestion> out = new ArrayList<>();
        double min = plugin.getConfig().getDouble("grouping.min-confidence", 0.5);
        for (World w : Bukkit.getWorlds()) {
            if (plugin.groups().byWorld(w.getName()) != null) continue;
            String name = w.getName().toLowerCase(Locale.ROOT);
            String base = baseName(name);
            Group best = null;
            double bestScore = 0;
            String reason = "";
            boolean tie = false;
            for (Group g : plugin.groups().values()) {
                double sc = 0;
                String why = "";
                if (g.key.equals(base)) {
                    sc = 1.0;
                    why = "exact base name";
                } else if (aliases(g).contains(base) || aliases(g).contains(name)) {
                    sc = 0.95;
                    why = "configured alias";
                } else {
                    for (String gw : g.worlds) {
                        if (baseName(gw.toLowerCase(Locale.ROOT)).equals(base)) {
                            sc = 0.9;
                            why = "same base as world " + gw;
                            break;
                        }
                    }
                    if (sc == 0 && Math.min(base.length(), g.key.length()) >= 4
                            && (g.key.startsWith(base) || base.startsWith(g.key))) {
                        sc = 0.6;
                        why = "similar name";
                    }
                }
                if (sc > bestScore) {
                    best = g;
                    bestScore = sc;
                    reason = why;
                    tie = false;
                } else if (sc == bestScore && sc > 0) {
                    tie = true;
                }
            }
            if (best != null && tie) bestScore = Math.min(bestScore, 0.5);
            if (best != null && bestScore >= min) out.add(new Suggestion(w.getName(), best, bestScore, tie ? "ambiguous: " + reason : reason));
        }
        return out;
    }

    private List<String> aliases(Group g) {
        List<String> l = new ArrayList<>();
        for (String a : plugin.getConfig().getStringList("grouping.aliases." + g.key)) l.add(a.toLowerCase(Locale.ROOT));
        return l;
    }

    public static String baseName(String lower) {
        String prev;
        String cur = lower;
        do {
            prev = cur;
            cur = DIM_SUFFIX.matcher(cur).replaceAll("");
        } while (!cur.equals(prev) && !cur.isEmpty());
        return cur.isEmpty() ? lower : cur;
    }

    /** Applies suggestions at or above the auto-assign threshold. Returns the number of assigned worlds. */
    public int applySuggestions() {
        double th = plugin.getConfig().getDouble("grouping.auto-assign-threshold", 0.9);
        int n = 0;
        for (Suggestion s : suggest()) {
            if (s.confidence() >= th && !s.reason().startsWith("ambiguous") && plugin.groups().assign(s.world(), s.group())) n++;
        }
        return n;
    }

    public void logSuggestions() {
        List<Suggestion> l = suggest();
        if (plugin.getConfig().getBoolean("grouping.auto-assign", false)) {
            int n = applySuggestions();
            if (n > 0) plugin.getLogger().info("Automatically assigned " + n + " world(s) to groups.");
            l = suggest();
        }
        for (Suggestion s : l) {
            plugin.getLogger().info("World '" + s.world() + "' looks like part of group '" + s.group().key + "' ("
                    + Math.round(s.confidence() * 100) + "%, " + s.reason() + "). Use /mwn group " + s.world() + " " + s.group().key);
        }
    }

    /** Deletes a single world (unload + files). Throws if unsafe. */
    public void deleteWorld(World w, Runnable done) throws MwnException {
        File folder = checkDeletable(w);
        if (plugin.groups().byWorld(w.getName()) != null) plugin.groups().unassign(w.getName());
        String name = w.getName();
        if (!Bukkit.unloadWorld(w, true)) throw new MwnException("world-unload-failed", name);
        unregister(name);
        deleteFolderAsync(folder, done);
    }

    private File checkDeletable(World w) throws MwnException {
        if (isProtectedWorld(w.getName())) throw new MwnException("world-protected", w.getName());
        if (!w.getPlayers().isEmpty()) throw new MwnException("world-not-empty", w.getName());
        try {
            File folder = w.getWorldFolder().getCanonicalFile();
            File container = Bukkit.getWorldContainer().getCanonicalFile();
            if (folder.getParentFile() == null || !folder.getParentFile().equals(container) || !folder.getName().equals(w.getName())) {
                throw new MwnException("unsafe-path", w.getName());
            }
            return folder;
        } catch (IOException e) {
            throw new MwnException("unsafe-path", w.getName());
        }
    }

    /** Deletes a group; optionally also its worlds. Validates everything before touching anything. */
    public void deleteGroup(Group g, boolean deleteWorlds, Runnable done) throws MwnException {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.switcher().groupOf(p) == g) throw new MwnException("group-not-empty", g.key);
        }
        List<World> loaded = new ArrayList<>();
        List<File> folders = new ArrayList<>();
        if (deleteWorlds) {
            for (String wn : g.worlds) {
                World w = Bukkit.getWorld(wn);
                if (w == null) continue;
                folders.add(checkDeletable(w));
                loaded.add(w);
            }
        }
        for (World w : loaded) {
            String name = w.getName();
            if (!Bukkit.unloadWorld(w, true)) throw new MwnException("world-unload-failed", name);
            unregister(name);
        }
        plugin.groups().remove(g);
        if (g.key.equalsIgnoreCase(plugin.getConfig().getString("lobby-group", ""))) {
            plugin.getConfig().set("lobby-group", "");
            plugin.saveConfig();
        }
        plugin.db().deleteGroupData(g.id).exceptionally(t -> {
            plugin.getLogger().warning("Could not clean database for " + g.id + ": " + t.getMessage());
            return null;
        });
        if (folders.isEmpty()) {
            done.run();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (File f : folders) deleteRecursively(f.toPath());
            Bukkit.getScheduler().runTask(plugin, done);
        });
    }

    private void deleteFolderAsync(File folder, Runnable done) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            deleteRecursively(folder.toPath());
            Bukkit.getScheduler().runTask(plugin, done);
        });
    }

    private void deleteRecursively(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            plugin.getLogger().warning("Could not fully delete " + root + ": " + e.getMessage());
        }
    }
}
