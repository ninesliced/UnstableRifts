package dev.ninesliced.shotcave.dungeon;

/**
 * How a Shotcave_Portal marker should behave at runtime.
 */
public enum PortalMode {
    /** Advances the party to the next level, or exits the dungeon on the last floor. */
    NEXT_LEVEL,
    /** Teleports the player to the nearest ancestor room that contains exit portal markers. */
    CLOSEST_EXIT
}
