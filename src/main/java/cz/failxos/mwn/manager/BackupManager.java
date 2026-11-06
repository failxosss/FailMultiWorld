package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import cz.failxos.mwn.util.MwnException;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Zip backups of a group's worlds with verification and retention. Restoring is intentionally manual. */
public final class BackupManager {
    private final MWNPlugin plugin;

    public BackupManager(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public File dirFor(Group g) {
        return new File(new File(plugin.getDataFolder(), "backups"), g.id);
    }

    public List<File> list(Group g) {
        File[] f = dirFor(g).listFiles((d, n) -> n.endsWith(".zip"));
        if (f == null) return List.of();
        List<File> l = new ArrayList<>(Arrays.asList(f));
        l.sort(Comparator.comparing(File::getName).reversed());
        return l;
    }

    public void backup(CommandSender who, Group g) {
        List<File> folders = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String wn : g.worlds) {
            World w = Bukkit.getWorld(wn);
            if (w == null) continue;
            w.save();
            folders.add(w.getWorldFolder());
            names.add(wn);
        }
        if (folders.isEmpty()) {
            plugin.msg().send(who, "backup-no-worlds", "group", g.key);
            return;
        }
        File dir = dirFor(g);
        File target = new File(dir, new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".zip");
        plugin.msg().send(who, "backup-started", "group", g.key);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String resultKey = "backup-done";
            Object[] args;
            try {
                dir.mkdirs();
                try (OutputStream os = Files.newOutputStream(target.toPath()); ZipOutputStream zos = new ZipOutputStream(os)) {
                    for (int i = 0; i < folders.size(); i++) zipFolder(zos, folders.get(i).toPath(), names.get(i));
                }
                int entries;
                try (ZipFile zf = new ZipFile(target)) {
                    entries = zf.size();
                }
                if (entries == 0) throw new IOException("empty archive");
                prune(g);
                args = new Object[]{"group", g.key, "file", target.getName(), "size", target.length() / 1024 + " KiB"};
            } catch (IOException e) {
                plugin.getLogger().warning("Backup failed: " + e.getMessage());
                target.delete();
                resultKey = "backup-failed";
                args = new Object[]{"group", g.key};
            }
            final String k = resultKey;
            final Object[] a = args;
            Bukkit.getScheduler().runTask(plugin, () -> plugin.msg().send(who, k, a));
        });
    }

    public File find(Group g, String name) {
        for (File f : list(g)) if (f.getName().equalsIgnoreCase(name)) return f;
        return null;
    }

    /**
     * Restores a backup. Validates the archive, requires the group to be empty, unloads its worlds, moves the current
     * folders to "&lt;world&gt;.pre-restore-&lt;time&gt;" (nothing is deleted) and extracts the archive.
     */
    public void restore(CommandSender who, Group g, File zip) throws MwnException {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.switcher().groupOf(p) == g) throw new MwnException("group-not-empty", "group", g.key);
        }
        Map<String, World.Environment> envs = new LinkedHashMap<>();
        for (String wn : g.worlds) {
            World w = Bukkit.getWorld(wn);
            if (w == null) continue;
            if (!w.getPlayers().isEmpty()) throw new MwnException("world-not-empty", "name", wn);
            if (w.equals(Bukkit.getWorlds().get(0))) throw new MwnException("world-protected", "name", wn);
            envs.put(wn, w.getEnvironment());
        }
        Set<String> tops = new HashSet<>();
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().replace('\\', '/');
                String top = n.contains("/") ? n.substring(0, n.indexOf('/')) : n;
                if (top.isEmpty() || !g.hasWorld(top)) throw new MwnException("restore-invalid-archive", "file", zip.getName());
                tops.add(top);
            }
        } catch (IOException e) {
            throw new MwnException("restore-invalid-archive", "file", zip.getName());
        }
        if (tops.isEmpty()) throw new MwnException("restore-invalid-archive", "file", zip.getName());
        List<String> unloaded = new ArrayList<>();
        for (String wn : envs.keySet()) {
            World w = Bukkit.getWorld(wn);
            if (w != null && !Bukkit.unloadWorld(w, true)) {
                for (String u : unloaded) new WorldCreator(u).environment(envs.get(u)).createWorld();
                throw new MwnException("world-unload-failed", "name", wn);
            }
            unloaded.add(wn);
        }
        plugin.msg().send(who, "restore-started", "group", g.key, "file", zip.getName());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = true;
            Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            List<Path[]> moved = new ArrayList<>();
            try (ZipFile zf = new ZipFile(zip)) {
                for (String top : tops) {
                    Path dir = container.resolve(top).normalize();
                    if (!dir.startsWith(container) || dir.equals(container)) throw new IOException("unsafe path");
                    if (Files.exists(dir)) {
                        Path bak = container.resolve(top + ".pre-restore-" + ts);
                        Files.move(dir, bak);
                        moved.add(new Path[]{dir, bak});
                    }
                }
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    Path t = container.resolve(e.getName()).normalize();
                    if (!t.startsWith(container)) throw new IOException("unsafe entry " + e.getName());
                    if (e.isDirectory()) {
                        Files.createDirectories(t);
                    } else {
                        Files.createDirectories(t.getParent());
                        try (java.io.InputStream in = zf.getInputStream(e)) {
                            Files.copy(in, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } catch (IOException e) {
                ok = false;
                plugin.getLogger().warning("Restore failed: " + e.getMessage() + " - rolling back");
                for (Path[] m : moved) {
                    try {
                        if (Files.exists(m[0])) deleteTree(m[0]);
                        Files.move(m[1], m[0]);
                    } catch (IOException ex) {
                        plugin.getLogger().severe("Rollback failed for " + m[0] + ": " + ex.getMessage());
                    }
                }
            }
            final boolean fok = ok;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Set<String> reload = new HashSet<>(envs.keySet());
                if (fok) reload.addAll(tops);
                for (String wn : reload) {
                    try {
                        new WorldCreator(wn).environment(envs.getOrDefault(wn, World.Environment.NORMAL)).createWorld();
                    } catch (RuntimeException ex) {
                        plugin.getLogger().warning("Could not reload world " + wn + ": " + ex.getMessage());
                    }
                }
                plugin.msg().send(who, fok ? "restore-done" : "restore-failed", "group", g.key);
            });
        });
    }

    private void deleteTree(Path root) throws IOException {
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
    }

    private void prune(Group g) {
        int keep = plugin.getConfig().getInt("backup.keep", 5);
        List<File> l = list(g);
        for (int i = Math.max(1, keep); i < l.size(); i++) l.get(i).delete();
    }

    private void zipFolder(ZipOutputStream zos, Path root, String prefix) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                if (f.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                zos.putNextEntry(new ZipEntry(prefix + "/" + root.relativize(f).toString().replace('\\', '/')));
                try {
                    Files.copy(f, zos);
                } catch (IOException ignored) {
                    // file changed while copying; entry stays as is
                }
                zos.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
