package dev.ninesliced.unstablerifts.dungeon;

import org.joml.Vector3i;

import javax.annotation.Nonnull;

/**
 * A single objective within a challenge room.
 */
public final class ChallengeObjective {

    private final Type type;
    private final Vector3i position;
    private final int mobClearPercent;
    private boolean completed;

    public ChallengeObjective(@Nonnull Type type, @Nonnull Vector3i position) {
        this(type, position, 100);
    }

    public ChallengeObjective(@Nonnull Type type, @Nonnull Vector3i position, int mobClearPercent) {
        this.type = type;
        this.position = position;
        this.mobClearPercent = type == Type.MOB_CLEAR
                ? Math.max(0, Math.min(100, mobClearPercent))
                : 0;
    }

    @Nonnull
    public Type getType() {
        return type;
    }

    @Nonnull
    public Vector3i getPosition() {
        return position;
    }

    public int getMobClearPercent() {
        return mobClearPercent;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void complete() {
        this.completed = true;
    }

    /**
     * Human-readable description for the challenge HUD.
     */
    @Nonnull
    public String getDisplayName() {
        return switch (type) {
            case ACTIVATION_ZONE -> "Reach the activation zone";
            case MOB_CLEAR -> mobClearPercent >= 100
                    ? "Clear all enemies"
                    : "Defeat " + mobClearPercent + "% of enemies";
        };
    }

    public enum Type {
        /**
         * Player must enter an activation zone.
         */
        ACTIVATION_ZONE,
        /**
         * A configured percentage of the room's mobs must be defeated.
         */
        MOB_CLEAR
    }
}
