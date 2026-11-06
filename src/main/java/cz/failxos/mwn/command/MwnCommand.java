package cz.failxos.mwn.command;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.manager.ChatManager;
import cz.failxos.mwn.manager.WorldManager;
import cz.failxos.mwn.model.Group;
import cz.failxos.mwn.util.MwnException;
import cz.failxos.mwn.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MwnCommand implements TabExecutor {
    private record Pending(String target, long expires) {}

    private static final Map<String, String> PERMS = new HashMap<>();
    private static final List<String> SUBS = List.of("help", "version", "reload", "create", "delete", "rename", "list", "info",
            "group", "ungroup", "world", "suggest", "setspawn", "setlobby", "maintenance", "set", "feature", "mutechat", "announce",
            "backup", "backups", "status", "diagnostics", "integrations", "debug", "portal", "gui", "friend", "profile", "queue", "restore", "chat", "globalchat", "staffchat", "msg", "reply", "ignore");
    private static final List<String> SETTINGS = List.of("display-name", "description", "icon", "gui-slot", "difficulty", "default-gamemode",
            "max-players", "isolated-chat", "separate-inventory", "separate-enderchest", "separate-xp", "separate-health",
            "separate-gamemode", "separate-economy", "pack-url", "pack-hash", "scoreboard", "tablist", "pvp", "hunger", "fall-damage", "mob-spawning", "build", "explosions",
            "fire-spread", "natural-regeneration");

    static {
        for (String s : List.of("help", "version", "list", "info", "chat", "ignore", "friend", "profile", "queue")) PERMS.put(s, "mwn.use");
        PERMS.put("globalchat", "mwn.chat.global");
        PERMS.put("staffchat", "mwn.chat.staff");
        PERMS.put("msg", "mwn.chat.msg");
        PERMS.put("reply", "mwn.chat.msg");
        PERMS.put("create", "mwn.create");
        PERMS.put("delete", "mwn.delete");
        PERMS.put("backup", "mwn.backup");
        PERMS.put("backups", "mwn.backup");
        PERMS.put("restore", "mwn.backup");
    }

    private final MWNPlugin plugin;
    private final Map<String, Pending> pending = new HashMap<>();

    public MwnCommand(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    private Group group(String name) throws MwnException {
        Group g = plugin.groups().get(name);
        if (g == null) throw new MwnException("unknown-group", name);
        return g;
    }

    private World world(String name) throws MwnException {
        World w = Bukkit.getWorld(name);
        if (w == null) throw new MwnException("unknown-world", name);
        return w;
    }

    private Player player(CommandSender s) throws MwnException {
        if (s instanceof Player p) return p;
        throw new MwnException("players-only");
    }

    private static void need(String[] a, int n, String usage) throws MwnException {
        if (a.length < n) throw new MwnException("usage", "usage", usage);
    }

    private static String join(String[] a, int from) {
        return String.join(" ", Arrays.copyOfRange(a, from, a.length));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            plugin.msg().sendLines(s, "help");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (!SUBS.contains(sub)) {
            plugin.msg().send(s, "unknown-command");
            return true;
        }
        if (!s.hasPermission(PERMS.getOrDefault(sub, "mwn.admin"))) {
            plugin.msg().send(s, "no-permission");
            return true;
        }
        try {
            switch (sub) {
                case "help" -> plugin.msg().sendLines(s, "help");
                case "version" -> plugin.msg().send(s, "version", "version", plugin.getPluginMeta().getVersion());
                case "reload" -> {
                    plugin.reloadAll();
                    plugin.msg().send(s, "reloaded");
                }
                case "create" -> create(s, a);
                case "delete" -> delete(s, a);
                case "rename" -> {
                    need(a, 3, "/mwn rename <old> <new>");
                    Group g = group(a[1]);
                    plugin.groups().rename(g, a[2]);
                    plugin.msg().send(s, "group-renamed", "old", a[1], "new", g.key, "id", g.id);
                }
                case "list" -> list(s);
                case "info" -> {
                    need(a, 2, "/mwn info <group>");
                    info(s, group(a[1]));
                }
                case "group" -> {
                    need(a, 3, "/mwn group <world> <group>");
                    assign(s, a[1], a[2]);
                }
                case "ungroup" -> {
                    need(a, 2, "/mwn ungroup <world>");
                    unassign(s, a[1]);
                }
                case "world" -> worldCmd(s, a);
                case "suggest" -> suggest(s, a);
                case "setspawn" -> {
                    need(a, 2, "/mwn setspawn <group>");
                    Player p = player(s);
                    Group g = group(a[1]);
                    if (!g.hasWorld(p.getWorld().getName())) throw new MwnException("spawn-wrong-world", "group", g.key);
                    g.setSpawn(p.getLocation());
                    plugin.groups().save();
                    plugin.msg().send(s, "spawn-set", "group", g.display());
                }
                case "setlobby" -> {
                    need(a, 2, "/mwn setlobby <group>");
                    Group g = group(a[1]);
                    plugin.getConfig().set("lobby-group", g.key);
                    plugin.saveConfig();
                    plugin.msg().send(s, "lobby-set", "group", g.display());
                }
                case "maintenance" -> {
                    need(a, 2, "/mwn maintenance <group> [on|off]");
                    Group g = group(a[1]);
                    if (a.length > 2) g.maintenance = a[2].equalsIgnoreCase("on") || a[2].equalsIgnoreCase("true");
                    else g.maintenance = !g.maintenance;
                    plugin.groups().save();
                    plugin.msg().send(s, g.maintenance ? "maintenance-on" : "maintenance-off", "group", g.display());
                }
                case "set" -> set(s, a);
                case "feature" -> {
                    need(a, 4, "/mwn feature <group> <feature> <true|false>");
                    Group g = group(a[1]);
                    boolean v = parseBool(a[3]);
                    g.features.put(a[2], v);
                    plugin.groups().save();
                    plugin.msg().send(s, "feature-set", "group", g.display(), "feature", a[2], "value", v);
                }
                case "mutechat" -> {
                    need(a, 2, "/mwn mutechat <group>");
                    Group g = group(a[1]);
                    g.chatMuted = !g.chatMuted;
                    plugin.groups().save();
                    plugin.msg().send(s, g.chatMuted ? "mutechat-on" : "mutechat-off", "group", g.display());
                }
                case "announce" -> {
                    need(a, 3, "/mwn announce <group> <message>");
                    Group g = group(a[1]);
                    plugin.chat().announce(g, plugin.msg().component("announce", "message", Text.parse(join(a, 2))));
                    plugin.msg().send(s, "announce-sent", "group", g.display());
                }
                case "backup" -> {
                    need(a, 2, "/mwn backup <group>");
                    plugin.backups().backup(s, group(a[1]));
                }
                case "backups" -> {
                    need(a, 2, "/mwn backups <group>");
                    Group g = group(a[1]);
                    List<File> l = plugin.backups().list(g);
                    plugin.msg().send(s, "backups-header", "group", g.key, "count", l.size());
                    for (File f : l) plugin.msg().send(s, "backups-entry", "file", f.getName(), "size", f.length() / 1024 + " KiB");
                }
                case "status", "diagnostics" -> status(s, sub.equals("diagnostics"));
                case "integrations" -> integrations(s);
                case "debug" -> {
                    plugin.setDebug(!plugin.isDebug());
                    plugin.msg().send(s, plugin.isDebug() ? "debug-on" : "debug-off");
                }
                case "gui" -> plugin.adminGui().open(player(s));
                case "portal" -> portal(s, a);
                case "friend" -> friend(s, a);
                case "queue" -> queue(s, a);
                case "restore" -> restore(s, a);
                case "profile" -> profile(s, a);
                case "chat" -> chatMode(s, a);
                case "globalchat" -> {
                    need(a, 2, "/mwn globalchat <message>");
                    plugin.chat().broadcast(player(s), ChatManager.Mode.GLOBAL, join(a, 1));
                }
                case "staffchat" -> {
                    need(a, 2, "/mwn staffchat <message>");
                    plugin.chat().broadcast(player(s), ChatManager.Mode.STAFF, join(a, 1));
                }
                case "msg" -> {
                    need(a, 3, "/mwn msg <player> <message>");
                    Player me = player(s);
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) throw new MwnException("unknown-player", a[1]);
                    plugin.chat().sendPrivate(me, t, join(a, 2));
                }
                case "reply" -> {
                    need(a, 2, "/mwn reply <message>");
                    Player me = player(s);
                    Player t = plugin.chat().replyTarget(me);
                    if (t == null) throw new MwnException("no-reply-target");
                    plugin.chat().sendPrivate(me, t, join(a, 1));
                }
                case "ignore" -> {
                    need(a, 2, "/mwn ignore <player>");
                    Player me = player(s);
                    Player online = Bukkit.getPlayerExact(a[1]);
                    UUID target = online != null ? online.getUniqueId() : null;
                    if (target == null) {
                        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(a[1]);
                        if (off != null) target = off.getUniqueId();
                    }
                    if (target == null || target.equals(me.getUniqueId())) throw new MwnException("unknown-player", a[1]);
                    boolean now = plugin.chat().toggleIgnore(me, target);
                    plugin.msg().send(s, now ? "ignore-on" : "ignore-off", "player", a[1]);
                }
                default -> plugin.msg().send(s, "unknown-command");
            }
        } catch (MwnException e) {
            plugin.msg().send(s, e.key, e.args);
        }
        return true;
    }

    private static boolean parseBool(String v) throws MwnException {
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on")) return true;
        if (v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off")) return false;
        throw new MwnException("setting-invalid", "value", v);
    }

    private void create(CommandSender s, String[] a) throws MwnException {
        need(a, 2, "/mwn create <name>");
        plugin.msg().send(s, "creating", "name", a[1]);
        Group g = plugin.worlds().createGroupWithWorld(a[1]);
        plugin.groups().applyWorldSettings(g);
        Location l = g.spawnLocation();
        plugin.msg().sendLines(s, "created", "name", g.key, "id", g.id, "world", a[1],
                "spawn", l == null ? "-" : l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
    }

    private void delete(CommandSender s, String[] a) throws MwnException {
        need(a, 2, "/mwn delete <group> [confirm|keepworlds]");
        Group g = group(a[1]);
        String mode = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
        String target = "group:" + g.id;
        if (mode.isEmpty()) {
            pending.put(s.getName(), new Pending(target, System.currentTimeMillis() + 30_000L));
            plugin.msg().send(s, "delete-warning", "group", g.key, "worlds", g.worlds.isEmpty() ? "-" : String.join(", ", g.worlds));
            return;
        }
        if (!mode.equals("confirm") && !mode.equals("keepworlds")) throw new MwnException("usage", "usage", "/mwn delete <group> [confirm|keepworlds]");
        Pending p = pending.remove(s.getName());
        if (p == null || p.expires() < System.currentTimeMillis() || !p.target().equals(target)) throw new MwnException("confirm-expired");
        plugin.worlds().deleteGroup(g, mode.equals("confirm"), () -> plugin.msg().send(s, "delete-done", "group", g.key));
    }

    private void list(CommandSender s) {
        plugin.msg().send(s, "list-header", "count", plugin.groups().count());
        for (Group g : plugin.groups().all()) s.sendMessage(entry(g));
    }

    public Component entry(Group g) {
        int players = plugin.switcher().playersIn(g);
        String status = g.maintenance ? plugin.msg().raw("status-maintenance") : plugin.msg().raw("status-online");
        return plugin.msg().component("list-entry", "group", g.display(), "key", g.key, "players", players,
                        "max", g.maxPlayers > 0 ? String.valueOf(g.maxPlayers) : plugin.msg().raw("unlimited"), "status", status)
                .clickEvent(ClickEvent.runCommand("/server " + g.key))
                .hoverEvent(HoverEvent.showText(plugin.msg().component("list-hover")));
    }

    private void info(CommandSender s, Group g) {
        Location l = g.spawnLocation();
        plugin.msg().sendLines(s, "info", "group", g.display(), "key", g.key, "id", g.id,
                "worlds", g.worlds.isEmpty() ? "-" : String.join(", ", g.worlds),
                "players", plugin.switcher().playersIn(g),
                "max", g.maxPlayers > 0 ? String.valueOf(g.maxPlayers) : plugin.msg().raw("unlimited"),
                "maintenance", g.maintenance, "chat", g.isolatedChat, "inventory", g.separateInventory,
                "enderchest", g.separateEnderchest, "xp", g.separateXp, "difficulty", g.difficulty,
                "description", g.description.isEmpty() ? "-" : g.description,
                "spawn", l == null ? "-" : l.getWorld().getName() + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
    }

    private void assign(CommandSender s, String worldName, String groupName) throws MwnException {
        World w = world(worldName);
        Group g = group(groupName);
        if (!plugin.groups().assign(w.getName(), g)) throw new MwnException("assign-cancelled", "world", w.getName());
        plugin.msg().send(s, "world-assigned", "world", w.getName(), "group", g.display());
    }

    private void unassign(CommandSender s, String worldName) throws MwnException {
        World w = world(worldName);
        if (plugin.groups().byWorld(w.getName()) == null) throw new MwnException("world-not-assigned", "world", w.getName());
        if (!plugin.groups().unassign(w.getName())) throw new MwnException("assign-cancelled", "world", w.getName());
        plugin.msg().send(s, "world-unassigned", "world", w.getName());
    }

    private void worldCmd(CommandSender s, String[] a) throws MwnException {
        need(a, 2, "/mwn world <create|import|delete|assign|unassign|list|info> ...");
        String sub = a[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create", "import" -> {
                need(a, 3, "/mwn world " + sub + " <world> [--group <group>] [--environment NORMAL|NETHER|THE_END] [--seed <n>]");
                String groupName = null;
                World.Environment env = World.Environment.NORMAL;
                Long seed = null;
                for (int i = 3; i + 1 < a.length; i += 2) {
                    switch (a[i].toLowerCase(Locale.ROOT)) {
                        case "--group" -> groupName = a[i + 1];
                        case "--environment" -> {
                            try {
                                env = World.Environment.valueOf(a[i + 1].toUpperCase(Locale.ROOT));
                            } catch (IllegalArgumentException e) {
                                throw new MwnException("invalid-environment", "value", a[i + 1]);
                            }
                        }
                        case "--seed" -> {
                            try {
                                seed = Long.parseLong(a[i + 1]);
                            } catch (NumberFormatException e) {
                                throw new MwnException("setting-invalid", "value", a[i + 1]);
                            }
                        }
                        default -> throw new MwnException("usage", "usage", "/mwn world create <world> [--group g] [--environment e] [--seed n]");
                    }
                }
                Group g = groupName == null ? null : group(groupName);
                plugin.msg().send(s, "creating", "name", a[2]);
                World w = plugin.worlds().createWorld(a[2], env, seed, sub.equals("import"));
                if (g != null) plugin.groups().assign(w.getName(), g);
                plugin.msg().send(s, "world-created", "world", w.getName(), "environment", w.getEnvironment().name(),
                        "group", g == null ? "-" : g.key);
            }
            case "delete" -> {
                need(a, 3, "/mwn world delete <world> [confirm]");
                World w = world(a[2]);
                String target = "world:" + w.getName();
                if (a.length < 4 || !a[3].equalsIgnoreCase("confirm")) {
                    pending.put(s.getName(), new Pending(target, System.currentTimeMillis() + 30_000L));
                    plugin.msg().send(s, "world-delete-warning", "world", w.getName());
                    return;
                }
                Pending p = pending.remove(s.getName());
                if (p == null || p.expires() < System.currentTimeMillis() || !p.target().equals(target)) throw new MwnException("confirm-expired");
                String name = w.getName();
                plugin.worlds().deleteWorld(w, () -> plugin.msg().send(s, "world-deleted", "world", name));
            }
            case "assign" -> {
                need(a, 4, "/mwn world assign <world> <group>");
                assign(s, a[2], a[3]);
            }
            case "unassign" -> {
                need(a, 3, "/mwn world unassign <world>");
                unassign(s, a[2]);
            }
            case "list" -> {
                plugin.msg().send(s, "world-list-header", "count", Bukkit.getWorlds().size());
                for (World w : Bukkit.getWorlds()) {
                    Group g = plugin.groups().byWorld(w.getName());
                    plugin.msg().send(s, "world-list-entry", "world", w.getName(), "environment", w.getEnvironment().name(),
                            "group", g == null ? "-" : g.key, "players", w.getPlayers().size());
                }
            }
            case "info" -> {
                need(a, 3, "/mwn world info <world>");
                World w = world(a[2]);
                Group g = plugin.groups().byWorld(w.getName());
                Difficulty d = w.getDifficulty();
                plugin.msg().sendLines(s, "world-info", "world", w.getName(), "environment", w.getEnvironment().name(),
                        "group", g == null ? "-" : g.key, "players", w.getPlayers().size(), "seed", w.getSeed(),
                        "difficulty", d.name(), "chunks", w.getLoadedChunks().length);
            }
            default -> throw new MwnException("usage", "usage", "/mwn world <create|import|delete|assign|unassign|list|info>");
        }
    }

    private void suggest(CommandSender s, String[] a) {
        if (a.length > 1 && a[1].equalsIgnoreCase("apply")) {
            plugin.msg().send(s, "suggest-applied", "count", plugin.worlds().applySuggestions());
            return;
        }
        List<WorldManager.Suggestion> l = plugin.worlds().suggest();
        if (l.isEmpty()) {
            plugin.msg().send(s, "suggest-none");
            return;
        }
        plugin.msg().send(s, "suggest-header");
        for (WorldManager.Suggestion x : l) {
            plugin.msg().send(s, "suggest-entry", "world", x.world(), "group", x.group().key,
                    "confidence", Math.round(x.confidence() * 100), "reason", x.reason());
        }
    }

    private void set(CommandSender s, String[] a) throws MwnException {
        need(a, 4, "/mwn set <group> <setting> <value>");
        Group g = group(a[1]);
        String k = a[2].toLowerCase(Locale.ROOT);
        String v = join(a, 3);
        try {
            switch (k) {
                case "display-name" -> g.displayName = v;
                case "description" -> g.description = v;
                case "icon" -> {
                    if (org.bukkit.Material.matchMaterial(v) == null) throw new MwnException("setting-invalid", "value", v);
                    g.icon = v.toUpperCase(Locale.ROOT);
                }
                case "gui-slot" -> g.guiSlot = Integer.parseInt(v);
                case "max-players" -> g.maxPlayers = Math.max(0, Integer.parseInt(v));
                case "difficulty" -> {
                    g.difficulty = Difficulty.valueOf(v.toUpperCase(Locale.ROOT)).name();
                    plugin.groups().applyWorldSettings(g);
                }
                case "pack-url" -> g.packUrl = v.equalsIgnoreCase("none") ? "" : v;
                case "pack-hash" -> g.packHash = v.equalsIgnoreCase("none") ? "" : v;
                case "default-gamemode" -> g.defaultGamemode = GameMode.valueOf(v.toUpperCase(Locale.ROOT)).name();
                case "isolated-chat" -> g.isolatedChat = parseBool(v);
                case "separate-inventory" -> g.separateInventory = parseBool(v);
                case "separate-enderchest" -> g.separateEnderchest = parseBool(v);
                case "separate-xp" -> g.separateXp = parseBool(v);
                case "separate-health" -> g.separateHealth = parseBool(v);
                case "separate-gamemode" -> g.separateGamemode = parseBool(v);
                case "separate-economy" -> {
                    g.separateEconomy = parseBool(v);
                    plugin.economy().forget(g.id);
                }
                case "scoreboard" -> g.scoreboardEnabled = parseBool(v);
                case "tablist" -> g.tablistEnabled = parseBool(v);
                case "pvp", "hunger", "fall-damage", "mob-spawning", "build", "explosions", "fire-spread", "natural-regeneration" ->
                        g.rules.put(k, parseBool(v));
                default -> throw new MwnException("setting-unknown", "setting", k);
            }
        } catch (IllegalArgumentException e) {
            throw new MwnException("setting-invalid", "value", v);
        }
        plugin.groups().save();
        plugin.msg().send(s, "setting-updated", "group", g.display(), "setting", k, "value", v);
    }

    private void status(CommandSender s, boolean full) {
        String db;
        try {
            plugin.db().sync(c -> {
                try (var st = c.createStatement()) {
                    st.execute("SELECT 1");
                }
                return null;
            });
            db = "OK (" + plugin.db().type() + ", " + plugin.db().lastLatencyMs + " ms)";
        } catch (Exception e) {
            db = "ERROR: " + e.getMessage();
        }
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        plugin.msg().sendLines(s, "status", "groups", plugin.groups().count(), "worlds", Bukkit.getWorlds().size(),
                "online", Bukkit.getOnlinePlayers().size(), "tps", String.format(Locale.ROOT, "%.1f", Bukkit.getTPS()[0]),
                "memory", used + " / " + rt.maxMemory() / 1048576L + " MiB", "db", db,
                "failures", plugin.db().failures.get(), "transfers", plugin.switcher().activeTransfers());
        if (full) {
            for (Group g : plugin.groups().all()) {
                plugin.msg().send(s, "diag-group", "key", g.key, "id", g.id, "players", plugin.switcher().playersIn(g),
                        "worlds", g.worlds.size(), "maintenance", g.maintenance);
            }
            integrations(s);
        }
    }

    private void integrations(CommandSender s) {
        for (String name : List.of("PlaceholderAPI", "LuckPerms", "Vault")) {
            boolean on = Bukkit.getPluginManager().isPluginEnabled(name);
            plugin.msg().send(s, "integration-line", "name", name, "status", plugin.msg().raw(on ? "integration-found" : "integration-missing"),
                    "note", plugin.msg().raw("integration-note-" + name.toLowerCase(Locale.ROOT)));
        }
    }

    private void portal(CommandSender s, String[] a) throws MwnException {
        need(a, 2, "/mwn portal <pos1|pos2|create|delete|toggle|list> ...");
        var pm = plugin.portals();
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "pos1" -> {
                pm.setPos(player(s), true);
                plugin.msg().send(s, "portal-pos", "pos", "1");
            }
            case "pos2" -> {
                pm.setPos(player(s), false);
                plugin.msg().send(s, "portal-pos", "pos", "2");
            }
            case "create" -> {
                need(a, 4, "/mwn portal create <name> <group>");
                var p = pm.create(player(s), a[2], group(a[3]));
                plugin.msg().send(s, "portal-created", "name", p.name, "group", p.group, "permission", p.permission);
            }
            case "delete" -> {
                need(a, 3, "/mwn portal delete <name>");
                if (!pm.delete(a[2])) throw new MwnException("portal-unknown", "name", a[2]);
                plugin.msg().send(s, "portal-deleted", "name", a[2]);
            }
            case "toggle" -> {
                need(a, 3, "/mwn portal toggle <name>");
                var p = pm.get(a[2]);
                if (p == null) throw new MwnException("portal-unknown", "name", a[2]);
                pm.toggle(p);
                plugin.msg().send(s, "portal-toggled", "name", p.name, "value", p.enabled);
            }
            case "list" -> {
                plugin.msg().send(s, "portal-list-header", "count", pm.all().size());
                for (var p : pm.all()) {
                    plugin.msg().send(s, "portal-list-entry", "name", p.name, "world", p.world, "group", p.group, "enabled", p.enabled);
                }
            }
            default -> throw new MwnException("usage", "usage", "/mwn portal <pos1|pos2|create|delete|toggle|list>");
        }
    }

    private void queue(CommandSender s, String[] a) throws MwnException {
        Player p = player(s);
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "status";
        var w = plugin.queue().where(p.getUniqueId());
        if (sub.equals("leave")) {
            if (!plugin.queue().remove(p.getUniqueId())) throw new MwnException("queue-not-queued");
            plugin.msg().send(s, "queue-left");
        } else {
            if (w == null) throw new MwnException("queue-not-queued");
            plugin.msg().send(s, "queue-position", "group", w.group().display(), "position", w.position());
        }
    }

    private void restore(CommandSender s, String[] a) throws MwnException {
        need(a, 2, "/mwn restore <group> [file] [confirm]");
        Group g = group(a[1]);
        boolean confirm = a[a.length - 1].equalsIgnoreCase("confirm") && a.length > 2;
        String fileArg = a.length > 2 && !a[2].equalsIgnoreCase("confirm") ? a[2] : null;
        java.io.File f;
        if (fileArg == null) {
            var l = plugin.backups().list(g);
            if (l.isEmpty()) throw new MwnException("restore-no-backups", "group", g.key);
            f = l.get(0);
        } else {
            f = plugin.backups().find(g, fileArg);
            if (f == null) throw new MwnException("restore-file-unknown", "file", fileArg);
        }
        String target = "restore:" + g.id + ":" + f.getName();
        if (!confirm) {
            pending.put(s.getName(), new Pending(target, System.currentTimeMillis() + 30_000L));
            plugin.msg().send(s, "restore-warning", "group", g.key, "file", f.getName());
            return;
        }
        Pending p = pending.remove(s.getName());
        if (p == null || p.expires() < System.currentTimeMillis() || !p.target().equals(target)) throw new MwnException("confirm-expired");
        plugin.backups().restore(s, g, f);
    }

    private void friend(CommandSender s, String[] a) throws MwnException {
        Player p = player(s);
        need(a, 2, "/mwn friend <add|accept|deny|remove|list|join> [player]");
        var fm = plugin.friends();
        String sub = a[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            fm.list(p);
            return;
        }
        need(a, 3, "/mwn friend " + sub + " <player>");
        switch (sub) {
            case "add" -> {
                Player t = Bukkit.getPlayerExact(a[2]);
                if (t == null) throw new MwnException("unknown-player", a[2]);
                fm.request(p, t);
            }
            case "accept" -> fm.accept(p, a[2]);
            case "deny" -> fm.deny(p, a[2]);
            case "remove" -> fm.remove(p, a[2]);
            case "join" -> fm.join(p, a[2]);
            default -> throw new MwnException("usage", "usage", "/mwn friend <add|accept|deny|remove|list|join> [player]");
        }
    }

    private void profile(CommandSender s, String[] a) throws MwnException {
        Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player pl ? pl : null);
        if (t == null) throw new MwnException(a.length > 1 ? "unknown-player" : "players-only", "player", a.length > 1 ? a[1] : "");
        Group g = plugin.switcher().groupOf(t);
        boolean self = s instanceof Player pl2 && pl2.getUniqueId().equals(t.getUniqueId());
        boolean showServer = self || s.hasPermission("mwn.admin") || plugin.getConfig().getBoolean("friends.show-server", true);
        plugin.msg().sendLines(s, "profile", "player", t.getName(), "group", showServer ? (g == null ? "-" : g.plainName()) : plugin.msg().raw("friend-hidden"),
                "total", cz.failxos.mwn.manager.StatsManager.format(plugin.stats().total(t)),
                "current", cz.failxos.mwn.manager.StatsManager.format(plugin.stats().current(t)),
                "friends", plugin.friends().friendsOf(t.getUniqueId()).size(),
                "joined", new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(t.getFirstPlayed())));
        for (var e : plugin.stats().of(t.getUniqueId()).entrySet()) {
            Group eg = plugin.groups().byId(e.getKey());
            plugin.msg().send(s, "profile-group-entry", "group", eg == null ? e.getKey() : eg.plainName(),
                    "time", cz.failxos.mwn.manager.StatsManager.format(e.getValue()));
        }
    }

    private void chatMode(CommandSender s, String[] a) throws MwnException {
        Player p = player(s);
        if (a.length < 2) {
            plugin.msg().send(s, "chat-mode-current", "mode", plugin.chat().modeOf(p).name().toLowerCase(Locale.ROOT));
            return;
        }
        ChatManager.Mode m;
        try {
            m = ChatManager.Mode.valueOf(a[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MwnException("usage", "usage", "/mwn chat <group|global|staff>");
        }
        if (m == ChatManager.Mode.GLOBAL && !p.hasPermission("mwn.chat.global")) throw new MwnException("no-permission");
        if (m == ChatManager.Mode.STAFF && !p.hasPermission("mwn.chat.staff")) throw new MwnException("no-permission");
        plugin.chat().setMode(p, m);
        plugin.msg().send(s, "chat-mode-set", "mode", m.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String sub : SUBS) if (s.hasPermission(PERMS.getOrDefault(sub, "mwn.admin"))) out.add(sub);
        } else {
            String sub = a[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "delete", "rename", "info", "setspawn", "setlobby", "maintenance", "mutechat", "announce", "backup", "backups", "set", "feature" -> {
                    if (a.length == 2) plugin.groups().values().forEach(g -> out.add(g.key));
                    else if (sub.equals("delete") && a.length == 3) out.addAll(List.of("confirm", "keepworlds"));
                    else if (sub.equals("set") && a.length == 3) out.addAll(SETTINGS);
                    else if (sub.equals("feature") && a.length == 3) out.addAll(plugin.isolation().knownFeatures());
                    else if (sub.equals("feature") && a.length == 4) out.addAll(List.of("true", "false"));
                }
                case "group" -> {
                    if (a.length == 2) Bukkit.getWorlds().forEach(w -> out.add(w.getName()));
                    else if (a.length == 3) plugin.groups().values().forEach(g -> out.add(g.key));
                }
                case "ungroup" -> {
                    if (a.length == 2) Bukkit.getWorlds().forEach(w -> out.add(w.getName()));
                }
                case "world" -> {
                    if (a.length == 2) out.addAll(List.of("create", "import", "delete", "assign", "unassign", "list", "info"));
                    else if (a.length == 3 && !a[1].equalsIgnoreCase("create") && !a[1].equalsIgnoreCase("import")) Bukkit.getWorlds().forEach(w -> out.add(w.getName()));
                    else if (a.length == 4 && a[1].equalsIgnoreCase("assign")) plugin.groups().values().forEach(g -> out.add(g.key));
                    else if (a.length >= 4 && (a[1].equalsIgnoreCase("create") || a[1].equalsIgnoreCase("import"))) {
                        if (a[a.length - 2].equalsIgnoreCase("--group")) plugin.groups().values().forEach(g -> out.add(g.key));
                        else if (a[a.length - 2].equalsIgnoreCase("--environment")) out.addAll(List.of("NORMAL", "NETHER", "THE_END"));
                        else out.addAll(List.of("--group", "--environment", "--seed"));
                    }
                }
                case "suggest" -> {
                    if (a.length == 2) out.add("apply");
                }
                case "portal" -> {
                    if (a.length == 2) out.addAll(List.of("pos1", "pos2", "create", "delete", "toggle", "list"));
                    else if (a.length == 3 && List.of("delete", "toggle").contains(a[1].toLowerCase(Locale.ROOT))) plugin.portals().all().forEach(p -> out.add(p.name));
                    else if (a.length == 4 && a[1].equalsIgnoreCase("create")) plugin.groups().values().forEach(g -> out.add(g.key));
                }
                case "chat" -> {
                    if (a.length == 2) out.addAll(List.of("group", "global", "staff"));
                }
                case "friend" -> {
                    if (a.length == 2) out.addAll(List.of("add", "accept", "deny", "remove", "list", "join"));
                    else if (a.length == 3 && a[1].equalsIgnoreCase("add")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                    else if (a.length == 3 && s instanceof Player me) {
                        for (UUID f : plugin.friends().friendsOf(me.getUniqueId())) {
                            String n = Bukkit.getOfflinePlayer(f).getName();
                            if (n != null) out.add(n);
                        }
                    }
                }
                case "profile" -> {
                    if (a.length == 2) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                }
                case "msg", "ignore" -> {
                    if (a.length == 2) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                }
                default -> { }
            }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
