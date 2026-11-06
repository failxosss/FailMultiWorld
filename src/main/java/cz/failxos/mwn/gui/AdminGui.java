package cz.failxos.mwn.gui;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import cz.failxos.mwn.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Administration panel (/mwn gui): overview of groups. Left click toggles maintenance, right click sends info, shift+left toggles chat mute. */
public final class AdminGui implements Listener {
    private static final class Holder implements InventoryHolder {
        final Map<Integer, Group> slots = new HashMap<>();
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final MWNPlugin plugin;

    public AdminGui(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 54, Text.parse(plugin.msg().raw("admin-gui-title")));
        int slot = 0;
        for (Group g : plugin.groups().all()) {
            if (slot >= 54) break;
            h.slots.put(slot, g);
            h.inv.setItem(slot++, item(g));
        }
        p.openInventory(h.inv);
    }

    private ItemStack item(Group g) {
        Material m = Material.matchMaterial(g.icon);
        ItemStack it = new ItemStack(g.maintenance ? Material.RED_CONCRETE : (m == null ? Material.GRASS_BLOCK : m));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(g.display().decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (Component c : plugin.msg().lines("admin-gui-lore", "key", g.key, "id", g.id, "players", plugin.switcher().playersIn(g),
                "max", g.maxPlayers > 0 ? String.valueOf(g.maxPlayers) : plugin.msg().raw("unlimited"),
                "worlds", g.worlds.size(), "maintenance", g.maintenance, "muted", g.chatMuted)) {
            lore.add(c.decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("mwn.admin")) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Group g = h.slots.get(e.getRawSlot());
        if (g == null) return;
        if (e.isShiftClick()) {
            g.chatMuted = !g.chatMuted;
        } else if (e.isRightClick()) {
            p.performCommand("mwn info " + g.key);
            return;
        } else {
            g.maintenance = !g.maintenance;
        }
        plugin.groups().save();
        h.inv.setItem(e.getRawSlot(), item(g));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
