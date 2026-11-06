package cz.failxos.mwn.util;

import cz.failxos.mwn.MWNPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Loads messages.yml; every user-visible text comes from here. Placeholders are MiniMessage tags like &lt;player&gt;. */
public final class Msg {
    private final MWNPlugin plugin;
    private YamlConfiguration yml;

    public Msg(MWNPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        yml = YamlConfiguration.loadConfiguration(f);
        var in = plugin.getResource("messages.yml");
        if (in != null) yml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    public String raw(String key) {
        String s = yml.getString(key);
        return s == null ? "<red>Missing message: " + key : s;
    }

    private TagResolver[] resolvers(Object[] kv) {
        List<TagResolver> rs = new ArrayList<>();
        rs.add(Placeholder.component("prefix", Text.parse(raw("prefix"))));
        if (kv.length == 1) {
            // single value from MwnException: usable as <name>, <group>, <player>, <world> or <value>
            for (String n : new String[]{"name", "group", "player", "world", "value"}) {
                rs.add(Placeholder.unparsed(n, String.valueOf(kv[0])));
            }
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String name = String.valueOf(kv[i]);
            Object v = kv[i + 1];
            rs.add(v instanceof Component c ? Placeholder.component(name, c) : Placeholder.unparsed(name, String.valueOf(v)));
        }
        return rs.toArray(new TagResolver[0]);
    }

    public Component component(String key, Object... kv) {
        return Text.parse(raw(key), resolvers(kv));
    }

    public List<Component> lines(String key, Object... kv) {
        List<Component> out = new ArrayList<>();
        TagResolver[] rs = resolvers(kv);
        List<String> l = yml.getStringList(key);
        if (l.isEmpty()) l = List.of("<red>Missing message list: " + key);
        for (String s : l) out.add(Text.parse(s, rs));
        return out;
    }

    public void send(CommandSender to, String key, Object... kv) {
        to.sendMessage(component(key, kv));
    }

    public void sendLines(CommandSender to, String key, Object... kv) {
        for (Component c : lines(key, kv)) to.sendMessage(c);
    }
}
