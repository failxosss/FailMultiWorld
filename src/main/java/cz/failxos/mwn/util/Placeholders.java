package cz.failxos.mwn.util;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.hook.Hooks;
import cz.failxos.mwn.model.Group;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Internal %mwn_*% placeholders (also exposed through PlaceholderAPI) plus PlaceholderAPI pass-through. */
public final class Placeholders {
    private static final Pattern P = Pattern.compile("%mwn_([A-Za-z0-9_]+)%");
    private static final String[] BASES = {"group_is_maintenance", "group_displayname", "group_description", "group_max_players",
            "group_players", "group_online", "group_name", "group_id", "group", "player_group", "player_world", "total_players",
            "server_name", "server_status"};
    private static final Map<String, Function<OfflinePlayer, String>> CUSTOM = new ConcurrentHashMap<>();

    private Placeholders() {}

    public static void registerCustom(String name, Function<OfflinePlayer, String> fn) {
        CUSTOM.put(name.toLowerCase(), fn);
    }

    public static String apply(MWNPlugin pl, Player p, String text) {
        if (text == null) return "";
        text = text.replace("%player%", p.getName());
        Matcher m = P.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String r = resolve(pl, p, m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(r == null ? m.group(0) : r));
        }
        m.appendTail(sb);
        text = sb.toString();
        if (pl.papiPresent()) text = Hooks.setPlaceholders(p, text);
        return text;
    }

    public static String resolve(MWNPlugin pl, OfflinePlayer op, String params) {
        Function<OfflinePlayer, String> custom = CUSTOM.get(params.toLowerCase());
        if (custom != null) return custom.apply(op);
        Player p = op == null ? null : op.getPlayer();
        if (params.startsWith("anim_")) {
            var sec = pl.getConfig().getConfigurationSection("animations." + params.substring(5));
            if (sec == null) return "";
            var frames = sec.getStringList("frames");
            if (frames.isEmpty()) return "";
            long interval = Math.max(1, sec.getInt("interval-ticks", 10)) * 50L;
            return frames.get((int) ((System.currentTimeMillis() / interval) % frames.size()));
        }
        if (p != null && params.equals("playtime")) return cz.failxos.mwn.manager.StatsManager.format(pl.stats().current(p));
        if (p != null && params.equals("playtime_total")) return cz.failxos.mwn.manager.StatsManager.format(pl.stats().total(p));
        if (p != null && params.equals("playtime_seconds")) return String.valueOf(pl.stats().current(p));
        if (p != null && params.equals("balance")) return cz.failxos.mwn.manager.EconomyStore.format(pl, pl.economy().get(p.getUniqueId(), pl.economy().scope(p, null)));
        if (p != null && params.equals("friends")) return String.valueOf(pl.friends().friendsOf(p.getUniqueId()).size());
        if (params.startsWith("feature_")) {
            Group g = p == null ? null : pl.switcher().groupOf(p);
            return String.valueOf(pl.isolation().isEnabled(g, params.substring(8)));
        }
        String base = null;
        String key = null;
        for (String b : BASES) {
            if (params.equals(b)) {
                base = b;
                break;
            }
            if (params.startsWith(b + "_")) {
                base = b;
                key = params.substring(b.length() + 1);
                break;
            }
        }
        if (base == null) return null;
        Group g;
        if (key != null) {
            g = pl.groups().get(key);
            if (g == null) return pl.msg().raw("placeholder-unknown-group");
        } else {
            g = p == null ? null : pl.switcher().groupOf(p);
        }
        String none = pl.msg().raw("placeholder-no-group");
        switch (base) {
            case "group", "player_group" -> {
                if (base.equals("player_group")) return g == null ? none : g.plainName();
                return g == null ? none : g.key;
            }
            case "group_name" -> { return g == null ? none : g.plainName(); }
            case "group_displayname" -> {
                return g == null ? none : LegacyComponentSerializer.legacySection().serialize(g.display());
            }
            case "group_players", "group_online" -> { return g == null ? "0" : String.valueOf(pl.switcher().playersIn(g)); }
            case "group_max_players" -> {
                return g == null ? none : (g.maxPlayers > 0 ? String.valueOf(g.maxPlayers) : pl.msg().raw("unlimited"));
            }
            case "group_description" -> { return g == null ? none : g.description; }
            case "group_is_maintenance" -> { return g == null ? "false" : String.valueOf(g.maintenance); }
            case "group_id" -> { return g == null ? none : g.id; }
            case "player_world" -> { return p == null ? "" : p.getWorld().getName(); }
            case "total_players" -> { return String.valueOf(Bukkit.getOnlinePlayers().size()); }
            case "server_name" -> { return pl.getConfig().getString("server-name", "Network"); }
            case "server_status" -> {
                if (g == null) return pl.msg().raw("status-online");
                if (g.maintenance) return pl.msg().raw("status-maintenance");
                if (g.maxPlayers > 0 && pl.switcher().playersIn(g) >= g.maxPlayers) return pl.msg().raw("status-full");
                return pl.msg().raw("status-online");
            }
            default -> { return null; }
        }
    }
}
