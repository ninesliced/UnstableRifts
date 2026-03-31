package dev.ninesliced.unstablerifts.dungeon;

import javax.annotation.Nonnull;

/**
 * Tracks the lifecycle state of a dungeon game.
 */
public enum GameState {
    /**
     * Dungeon is being generated in the background.
     */
    GENERATING("Generating"),
    /**
     * Generation complete, waiting for players to be ready.
     */
    READY("Ready"),
    /**
     * Game is active, players are fighting through rooms.
     */
    ACTIVE("Active"),
    /**
     * Players are in the boss room.
     */
    BOSS("Boss Fight"),
    /**
     * Transitioning between levels (portal / staircase animation).
     */
    TRANSITIONING("Transitioning"),
    /**
     * Game has ended (victory or abort).
     */
    COMPLETE("Complete");

    private final String displayName;

    GameState(@Nonnull String displayName) {
        this.displayName = displayName;
    }

    @Nonnull
    public String getDisplayName() {
        return displayName;
    }
}

