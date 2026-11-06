package cz.failxos.mwn.hook;

import cz.failxos.mwn.MWNPlugin;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/** Isolated entry points for optional integrations so missing plugins never cause class-loading errors. */
public final class Hooks {
    private Hooks() {}

    public static void registerPapi(MWNPlugin plugin) {
        new MwnExpansion(plugin).register();
    }

    public static String setPlaceholders(Player p, String text) {
        return PlaceholderAPI.setPlaceholders(p, text);
    }
}
