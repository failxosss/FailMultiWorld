package cz.failxos.mwn.api;

import cz.failxos.mwn.model.Group;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a virtual server (group) was created. */
public class GroupCreateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Group group;

    public GroupCreateEvent(Group group) { this.group = group; }

    public Group getGroup() { return group; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
