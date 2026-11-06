package cz.failxos.mwn.model;

import cz.failxos.mwn.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A virtual server: a named set of worlds with its own settings. The id is permanent; the key (name) may change. */
public final class Group {
    public final String id;
    public String key;
    public String displayName;
    public String description = "";
    public String icon = "GRASS_BLOCK";
    public int guiSlot = -1;
    public final Set<String> worlds = new LinkedHashSet<>();

    public boolean isolatedChat = true;
    public boolean separateInventory = true;
    public boolean separateEnderchest = true;
    public boolean separateXp = true;
    public boolean separateHealth = true;
    public boolean separateGamemode = true;
    public boolean separateEconomy = false;

    public int maxPlayers = 0;
    public boolean maintenance = false;
    public boolean chatMuted = false;
    public boolean scoreboardEnabled = true;
    public boolean tablistEnabled = true;
    public String difficulty = "NORMAL";
    public String defaultGamemode = "SURVIVAL";

    /** World rules: pvp, hunger, fall-damage, mob-spawning, build, explosions, fire-spread, natural-regeneration. */
    public final Map<String, Boolean> rules = new LinkedHashMap<>();
    /** Plugin-isolation feature switches (feature name -> enabled). Missing = enabled. */
    public final Map<String, Boolean> features = new LinkedHashMap<>();

    public boolean hasSpawn;
    public String spawnWorld;
    public double sx, sy, sz;
    public float syaw, spitch;

    public String packUrl = "";
    public String packHash = "";

    public String boardTitle;
    public List<String> boardLines = new ArrayList<>();

    public Group(String id, String key) {
        this.id = id;
        this.key = key;
        this.displayName = key;
    }

    public Component display() {
        return Text.parse(displayName);
    }

    public String plainName() {
        return Text.plain(displayName);
    }

    public boolean hasWorld(String name) {
        for (String w : worlds) if (w.equalsIgnoreCase(name)) return true;
        return false;
    }

    public boolean rule(String name) {
        return rules.getOrDefault(name, true);
    }

    public boolean feature(String name) {
        for (Map.Entry<String, Boolean> e : features.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return true;
    }

    public void setSpawn(Location l) {
        hasSpawn = true;
        spawnWorld = l.getWorld().getName();
        sx = l.getX();
        sy = l.getY();
        sz = l.getZ();
        syaw = l.getYaw();
        spitch = l.getPitch();
    }

    public Location spawnLocation() {
        if (hasSpawn) {
            World w = Bukkit.getWorld(spawnWorld);
            if (w != null) return new Location(w, sx, sy, sz, syaw, spitch);
        }
        for (String n : worlds) {
            World w = Bukkit.getWorld(n);
            if (w != null) return w.getSpawnLocation();
        }
        return null;
    }
}
