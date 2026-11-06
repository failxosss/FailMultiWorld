package cz.failxos.mwn.api;

import cz.failxos.mwn.model.Group;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Fired before a player switches virtual server. {@code from} may be null (ungrouped). */
public class GroupSwitchEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Group from;
    private final Group to;
    private final boolean teleport;
    private boolean cancelled;

    public GroupSwitchEvent(Player who, Group from, Group to, boolean teleport) {
        super(who);
        this.from = from;
        this.to = to;
        this.teleport = teleport;
    }

    public Group getFrom() { return from; }
    public Group getTo() { return to; }
    public boolean willTeleport() { return teleport; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
