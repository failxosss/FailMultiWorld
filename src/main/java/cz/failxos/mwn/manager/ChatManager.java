package cz.failxos.mwn.manager;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import cz.failxos.mwn.util.Text;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Isolated group chat, global / staff chat, private messages, ignore list, cooldown and word filter. */
public final class ChatManager implements Listener {
    public enum Mode { GROUP, GLOBAL, STAFF }

    private final MWNPlugin plugin;
    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastChat = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignores = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> lastPm = new ConcurrentHashMap<>();

    public ChatManager(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public void setIgnores(UUID u, Set<UUID> set) {
        Set<UUID> s = ConcurrentHashMap.newKeySet();
        s.addAll(set);
        ignores.put(u, s);
    }

    public void cleanup(UUID u) {
        modes.remove(u);
        lastChat.remove(u);
        ignores.remove(u);
        lastPm.remove(u);
    }

    public Mode modeOf(Player p) { return modes.getOrDefault(p.getUniqueId(), Mode.GROUP); }
    public void setMode(Player p, Mode m) { modes.put(p.getUniqueId(), m); }

    public boolean isIgnoring(UUID owner, UUID target) {
        Set<UUID> s = ignores.get(owner);
        return s != null && s.contains(target);
    }

    /** Toggles ignore; returns true if the target is now ignored. */
    public boolean toggleIgnore(Player p, UUID target) {
        Set<UUID> s = ignores.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        boolean now;
        if (s.contains(target)) {
            s.remove(target);
            now = false;
        } else {
            s.add(target);
            now = true;
        }
        plugin.db().setIgnore(p.getUniqueId(), target, now);
        return now;
    }

    private static boolean receives(Group sender, Group viewer) {
        if (sender == viewer) return true;
        boolean sIso = sender != null && sender.isolatedChat;
        boolean vIso = viewer != null && viewer.isolatedChat;
        return !sIso && !vIso;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Group sg = plugin.switcher().groupOf(p);
        Mode mode = modeOf(p);
        if (mode == Mode.GLOBAL && !p.hasPermission("mwn.chat.global")) mode = Mode.GROUP;
        if (mode == Mode.STAFF && !p.hasPermission("mwn.chat.staff")) mode = Mode.GROUP;
        final Mode fm = mode;
        boolean bypass = p.hasPermission("mwn.bypass.chat");

        if (sg != null && sg.chatMuted && !bypass) {
            e.setCancelled(true);
            plugin.msg().send(p, "chat-muted");
            return;
        }
        int cd = plugin.getConfig().getInt("chat.cooldown-seconds", 0);
        long now = System.currentTimeMillis();
        if (cd > 0 && !bypass) {
            Long last = lastChat.get(p.getUniqueId());
            if (last != null && now - last < cd * 1000L) {
                e.setCancelled(true);
                plugin.msg().send(p, "chat-cooldown", "seconds", Math.max(1, cd - (now - last) / 1000L));
                return;
            }
            lastChat.put(p.getUniqueId(), now);
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(e.message()).toLowerCase(Locale.ROOT);
        if (!bypass) {
            for (String w : plugin.getConfig().getStringList("chat.blocked-words")) {
                if (!w.isEmpty() && plain.contains(w.toLowerCase(Locale.ROOT))) {
                    e.setCancelled(true);
                    plugin.msg().send(p, "chat-blocked");
                    return;
                }
            }
        }

        e.viewers().removeIf(a -> {
            if (!(a instanceof Player v)) return false;
            if (v.getUniqueId().equals(p.getUniqueId())) return false;
            if (isIgnoring(v.getUniqueId(), p.getUniqueId())) return true;
            return switch (fm) {
                case GLOBAL -> false;
                case STAFF -> !v.hasPermission("mwn.chat.staff");
                case GROUP -> !receives(sg, plugin.switcher().groupOf(v));
            };
        });

        String fmt = plugin.getConfig().getString("chat.format." + fm.name().toLowerCase(Locale.ROOT), "<player>: <message>");
        Component groupDisp = sg == null ? Component.text(plugin.getConfig().getString("chat.no-group-name", "-")) : sg.display();
        String groupName = sg == null ? "-" : sg.key;
        e.renderer(ChatRenderer.viewerUnaware((source, name, message) -> Text.parse(fmt,
                Placeholder.component("player", name),
                Placeholder.component("message", message),
                Placeholder.component("group", groupDisp),
                Placeholder.unparsed("group_name", groupName))));
    }

    /** One-off message to all players (global) or staff. */
    public void broadcast(Player from, Mode mode, String text) {
        String fmt = plugin.getConfig().getString("chat.format." + mode.name().toLowerCase(Locale.ROOT), "<player>: <message>");
        Group sg = plugin.switcher().groupOf(from);
        Component groupDisp = sg == null ? Component.text("-") : sg.display();
        Component out = Text.parse(fmt,
                Placeholder.component("player", from.displayName()),
                Placeholder.component("message", Component.text(text)),
                Placeholder.component("group", groupDisp),
                Placeholder.unparsed("group_name", sg == null ? "-" : sg.key));
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (mode == Mode.STAFF && !v.hasPermission("mwn.chat.staff")) continue;
            if (!v.equals(from) && isIgnoring(v.getUniqueId(), from.getUniqueId())) continue;
            v.sendMessage(out);
        }
        Bukkit.getConsoleSender().sendMessage(out);
    }

    public void sendPrivate(Player from, Player to, String text) {
        if (isIgnoring(to.getUniqueId(), from.getUniqueId()) && !from.hasPermission("mwn.bypass.chat")) {
            plugin.msg().send(from, "msg-ignored", "player", to.getName());
            return;
        }
        if (!plugin.getConfig().getBoolean("chat.private-across-groups", true)
                && plugin.switcher().groupOf(from) != plugin.switcher().groupOf(to)) {
            plugin.msg().send(from, "msg-other-group", "player", to.getName());
            return;
        }
        Component m = Component.text(text);
        plugin.msg().send(from, "msg-to", "player", to.getName(), "message", m);
        plugin.msg().send(to, "msg-from", "player", from.getName(), "message", m);
        lastPm.put(to.getUniqueId(), from.getUniqueId());
        lastPm.put(from.getUniqueId(), to.getUniqueId());
    }

    public Player replyTarget(Player p) {
        UUID u = lastPm.get(p.getUniqueId());
        return u == null ? null : Bukkit.getPlayer(u);
    }

    public void announce(Group g, Component c) {
        for (Player p : Bukkit.getOnlinePlayers()) if (plugin.switcher().groupOf(p) == g) p.sendMessage(c);
    }

}
