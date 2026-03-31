package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class RollComponent implements Component<EntityStore> {

    private static ComponentType<EntityStore, RollComponent> componentType;

    private long lastRollTime;
    private int rollTicksRemaining;
    @Nullable
    private Vector3d rollDirection;

    public RollComponent() {
    }

    @Nonnull
    public static ComponentType<EntityStore, RollComponent> getComponentType() {
        if (componentType == null) {
            throw new IllegalStateException("RollComponent has not been registered yet");
        }
        return componentType;
    }

    public static void setComponentType(@Nonnull ComponentType<EntityStore, RollComponent> type) {
        componentType = type;
    }

    public long getLastRollTime() {
        return this.lastRollTime;
    }

    public void setLastRollTime(long lastRollTime) {
        this.lastRollTime = lastRollTime;
    }

    public boolean isRolling() {
        return this.rollTicksRemaining > 0;
    }

    public int getRollTicksRemaining() {
        return this.rollTicksRemaining;
    }

    @Nullable
    public Vector3d getRollDirection() {
        return this.rollDirection;
    }

    public void startRoll(@Nonnull Vector3d direction, int durationTicks) {
        this.rollDirection = direction;
        this.rollTicksRemaining = durationTicks;
    }

    /**
     * Decrements the roll timer. Returns true if the roll is still active.
     */
    public boolean tickRoll() {
        if (this.rollTicksRemaining <= 0) {
            this.rollDirection = null;
            return false;
        }
        this.rollTicksRemaining--;
        if (this.rollTicksRemaining <= 0) {
            this.rollDirection = null;
            return false;
        }
        return true;
    }

    public void clearRoll() {
        this.rollDirection = null;
        this.rollTicksRemaining = 0;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        RollComponent copy = new RollComponent();
        copy.lastRollTime = this.lastRollTime;
        copy.rollTicksRemaining = this.rollTicksRemaining;
        copy.rollDirection = this.rollDirection != null ? new Vector3d(this.rollDirection) : null;
        return copy;
    }
}
