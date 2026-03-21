package dev.ninesliced.shotcave.dungeon;

/**
 * Categorizes each room within a dungeon level.
 */
public enum RoomType {
    /** First room of a level where players spawn. */
    SPAWN,
    /** Connecting passage between real rooms. */
    CORRIDOR,
    /** Mid-run room with puzzle/enemies and a reward. */
    CHALLENGE,
    /** High-value room gated behind a key door. */
    TREASURE,
    /** Safe room where the player can buy items. */
    SHOP,
    /** Final room of a level — defeat the boss to advance. */
    BOSS,
    /** A wall prefab used to seal exits. */
    WALL
}

