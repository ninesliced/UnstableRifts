package dev.ninesliced.shotcave.dungeon;

/**
 * How a door block in a room should behave at runtime.
 */
public enum DoorMode {
    /** Opened when a team key is consumed (right-click / F-key). */
    KEY,
    /** Opened when all activators in the room have been triggered. */
    ACTIVATOR
}
