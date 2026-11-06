package cz.failxos.mwn.listener;

import cz.failxos.mwn.MWNPlugin;
import cz.failxos.mwn.model.Group;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;

/** Enforces per-group world rules (pvp, hunger, fall-damage, mob-spawning, build, explosions, fire-spread, natural-regeneration). */
public final class SettingsListener implements Listener {
    private final MWNPlugin plugin;

    public SettingsListener(MWNPlugin plugin) {
        this.plugin = plugin;
    }

    private Group of(World w) {
        return w == null ? null : plugin.groups().byWorld(w.getName());
    }

    private boolean denied(World w, String rule) {
        Group g = of(w);
        return g != null && !g.rule(rule);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) d = shooter;
        if (d instanceof Player && denied(victim.getWorld(), "pvp")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p && denied(p.getWorld(), "fall-damage")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && e.getFoodLevel() < p.getFoodLevel() && denied(p.getWorld(), "hunger")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if ((e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED || e.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN)
                && denied(e.getEntity().getWorld(), "natural-regeneration")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if ((r == CreatureSpawnEvent.SpawnReason.NATURAL || r == CreatureSpawnEvent.SpawnReason.CHUNK_GEN)
                && denied(e.getLocation().getWorld(), "mob-spawning")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (denied(e.getBlock().getWorld(), "build") && !e.getPlayer().hasPermission("mwn.bypass.build")) {
            e.setCancelled(true);
            plugin.msg().send(e.getPlayer(), "build-denied");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (denied(e.getBlock().getWorld(), "build") && !e.getPlayer().hasPermission("mwn.bypass.build")) {
            e.setCancelled(true);
            plugin.msg().send(e.getPlayer(), "build-denied");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (denied(e.getLocation().getWorld(), "explosions")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (denied(e.getBlock().getWorld(), "explosions")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (denied(e.getBlock().getWorld(), "fire-spread")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD && denied(e.getBlock().getWorld(), "fire-spread")) e.setCancelled(true);
    }
}
