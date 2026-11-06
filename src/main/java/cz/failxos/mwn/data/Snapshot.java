package cz.failxos.mwn.data;

import cz.failxos.mwn.model.Group;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Captures and applies player state, split into categories that can be shared or isolated per group. */
public final class Snapshot {
    public static final String INVENTORY = "inventory";
    public static final String ENDERCHEST = "enderchest";
    public static final String XP = "xp";
    public static final String HEALTH = "health";
    public static final String GAMEMODE = "gamemode";
    public static final String LOCATION = "location";
    public static final List<String> CATEGORIES = List.of(INVENTORY, ENDERCHEST, XP, HEALTH, GAMEMODE, LOCATION);

    private Snapshot() {}

    /** Storage profile of a category in a group. Equal profiles mean the data is shared between those groups. */
    public static String profile(Group g, String cat) {
        if (g == null) return "shared";
        return switch (cat) {
            case INVENTORY -> g.separateInventory ? g.id : "shared";
            case ENDERCHEST -> g.separateEnderchest ? g.id : "shared";
            case XP -> g.separateXp ? g.id : "shared";
            case HEALTH -> g.separateHealth ? g.id : "shared";
            case GAMEMODE -> g.separateGamemode ? g.id : "shared";
            default -> g.id;
        };
    }

    public static Map<String, String> capture(Player p, Group g) {
        Map<String, String> m = new HashMap<>();
        PlayerInventory inv = p.getInventory();
        ItemStack[] all = new ItemStack[41];
        copy(inv.getStorageContents(), all, 0, 36);
        copy(inv.getArmorContents(), all, 36, 4);
        copy(inv.getExtraContents(), all, 40, 1);
        m.put(INVENTORY, encode(all));
        m.put(ENDERCHEST, encode(p.getEnderChest().getContents()));
        m.put(XP, p.getLevel() + ";" + p.getExp());
        StringBuilder fx = new StringBuilder();
        for (PotionEffect e : p.getActivePotionEffects()) {
            if (fx.length() > 0) fx.append('|');
            fx.append(e.getType().getKey()).append(',').append(e.getAmplifier()).append(',').append(e.getDuration());
        }
        m.put(HEALTH, p.getHealth() + ";" + p.getFoodLevel() + ";" + p.getSaturation() + ";" + fx);
        m.put(GAMEMODE, p.getGameMode().name() + ";" + p.getAllowFlight() + ";" + p.isFlying());
        Location l = p.getLocation();
        if (l.getWorld() != null && (g == null || g.hasWorld(l.getWorld().getName()))) {
            m.put(LOCATION, l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch());
        }
        return m;
    }

    /** Applies loaded categories (value null = no stored data, use defaults). Main thread only. */
    @SuppressWarnings("deprecation")
    public static void apply(Player p, Group g, Map<String, String> loaded) {
        for (Map.Entry<String, String> e : loaded.entrySet()) {
            String d = e.getValue();
            switch (e.getKey()) {
                case INVENTORY -> {
                    ItemStack[] all = d == null ? new ItemStack[41] : normalize(decode(d), 41);
                    PlayerInventory inv = p.getInventory();
                    inv.setStorageContents(Arrays.copyOfRange(all, 0, 36));
                    inv.setArmorContents(Arrays.copyOfRange(all, 36, 40));
                    inv.setExtraContents(Arrays.copyOfRange(all, 40, 41));
                }
                case ENDERCHEST -> {
                    if (d == null) p.getEnderChest().clear();
                    else p.getEnderChest().setContents(normalize(decode(d), p.getEnderChest().getSize()));
                }
                case XP -> {
                    int level = 0;
                    float exp = 0f;
                    if (d != null) {
                        String[] a = d.split(";");
                        try {
                            level = Integer.parseInt(a[0]);
                            exp = Float.parseFloat(a[1]);
                        } catch (RuntimeException ignored) { }
                    }
                    p.setLevel(level);
                    p.setExp(Math.max(0f, Math.min(0.999f, exp)));
                }
                case HEALTH -> {
                    double health = p.getMaxHealth();
                    int food = 20;
                    float sat = 5f;
                    String effects = "";
                    if (d != null) {
                        String[] a = d.split(";", 4);
                        try {
                            health = Double.parseDouble(a[0]);
                            food = Integer.parseInt(a[1]);
                            sat = Float.parseFloat(a[2]);
                            effects = a.length > 3 ? a[3] : "";
                        } catch (RuntimeException ignored) { }
                    }
                    for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(pe.getType());
                    for (String fx : effects.split("\\|")) {
                        String[] f = fx.split(",");
                        if (f.length < 3) continue;
                        try {
                            NamespacedKey k = NamespacedKey.fromString(f[0]);
                            PotionEffectType t = k == null ? null : PotionEffectType.getByKey(k);
                            if (t != null) p.addPotionEffect(new PotionEffect(t, Integer.parseInt(f[2]), Integer.parseInt(f[1])));
                        } catch (RuntimeException ignored) { }
                    }
                    p.setHealth(Math.max(0.5, Math.min(health, p.getMaxHealth())));
                    p.setFoodLevel(food);
                    p.setSaturation(sat);
                }
                case GAMEMODE -> {
                    GameMode gm = parseMode(g == null ? "SURVIVAL" : g.defaultGamemode);
                    boolean allow = false;
                    boolean fly = false;
                    if (d != null) {
                        String[] a = d.split(";");
                        gm = parseMode(a[0]);
                        if (a.length > 2) {
                            allow = Boolean.parseBoolean(a[1]);
                            fly = Boolean.parseBoolean(a[2]);
                        }
                    }
                    p.setGameMode(gm);
                    if (gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR) {
                        p.setAllowFlight(allow);
                        p.setFlying(allow && fly);
                    }
                }
                default -> { }
            }
        }
    }

    private static GameMode parseMode(String s) {
        try {
            return GameMode.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return GameMode.SURVIVAL;
        }
    }

    private static void copy(ItemStack[] src, ItemStack[] dst, int off, int len) {
        for (int i = 0; i < len && i < src.length; i++) dst[off + i] = src[i];
    }

    private static ItemStack[] normalize(ItemStack[] in, int size) {
        return Arrays.copyOf(in, size);
    }

    private static String encode(ItemStack[] items) {
        ItemStack[] safe = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) safe[i] = items[i] == null ? new ItemStack(Material.AIR) : items[i];
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(safe));
    }

    private static ItemStack[] decode(String s) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(s));
    }
}
