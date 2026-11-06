package cz.failxos.mwn.gui;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import cz.failxos.mwn.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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

/** Inventory based server selector. Every click is cancelled so items can never be taken or moved. */
public final class SelectorGui implements Listener {
    private static final class Holder implements InventoryHolder {
        final Map<Integer, Group> slots = new HashMap<>();
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final MWNPlugin plugin;

    public SelectorGui(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("gui.rows", 3)));
        int size = rows * 9;
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, size, Text.parse(plugin.getConfig().getString("gui.title", "Servers")));
        boolean hide = plugin.getConfig().getBoolean("gui.hide-without-permission", true)
                && plugin.getConfig().getBoolean("require-group-permission", false);
        int auto = 0;
        for (Group g : plugin.groups().all()) {
            if (hide && !p.hasPermission("mwn.group." + g.key) && !p.hasPermission("mwn.group.*")) continue;
            int slot = g.guiSlot;
            if (slot < 0 || slot >= size || h.slots.containsKey(slot)) {
                while (auto < size && h.slots.containsKey(auto)) auto++;
                if (auto >= size) break;
                slot = auto;
            }
            h.slots.put(slot, g);
            h.inv.setItem(slot, item(g));
        }
        p.openInventory(h.inv);
    }

    private ItemStack item(Group g) {
        Material m = Material.matchMaterial(g.icon);
        ItemStack it = new ItemStack(m == null ? Material.GRASS_BLOCK : m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(g.display().decoration(TextDecoration.ITALIC, false));
        int players = plugin.switcher().playersIn(g);
        String status = g.maintenance ? plugin.msg().raw("status-maintenance")
                : (g.maxPlayers > 0 && players >= g.maxPlayers ? plugin.msg().raw("status-full") : plugin.msg().raw("status-online"));
        String max = g.maxPlayers > 0 ? String.valueOf(g.maxPlayers) : plugin.msg().raw("unlimited");
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("gui.lore")) {
            lore.add(Text.parse(line.replace("%description%", g.description).replace("%players%", String.valueOf(players))
                    .replace("%max%", max).replace("%status%", status)).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Group g = h.slots.get(e.getRawSlot());
        if (g == null) return;
        p.closeInventory();
        plugin.switcher().switchTo(p, g, true, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
