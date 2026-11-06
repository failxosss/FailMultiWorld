package cz.failxos.mwn.api;

import cz.failxos.mwn.model.Group;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired before a world is assigned to a group. {@code group} is null when the world is being unassigned. */
public class WorldAssignEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String world;
    private final Group group;
    private boolean cancelled;

    public WorldAssignEvent(String world, Group group) {
        this.world = world;
        this.group = group;
    }

    public String getWorld() { return world; }
    public Group getGroup() { return group; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
