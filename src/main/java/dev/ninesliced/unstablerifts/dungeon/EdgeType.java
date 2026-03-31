package dev.ninesliced.unstablerifts.dungeon;

/**
 * Types of connections (edges) between dungeon rooms.
 */
public enum EdgeType {
    /**
     * Normal open passage.
     */
    OPEN,
    /**
     * Sealed with a wall prefab — impassable.
     */
    WALL,
    /**
     * Requires a key item to pass.
     */
    KEY_DOOR,
    /**
     * Opens when an activator condition is met.
     */
    LOCKED_DOOR,
    /**
     * Permanently sealed — cannot be opened by the player.
     */
    CLOSED_DOOR,
    /**
     * Teleport link (portal entry ↔ exit).
     */
    PORTAL
}
