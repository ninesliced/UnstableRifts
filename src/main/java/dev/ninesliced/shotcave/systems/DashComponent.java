package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class DashComponent implements Component<EntityStore> {

    /** Epoch-millis timestamp of the player's last successful dash. */
    private long lastDashTime;

    @Nullable
    private Vector3d dashDirection;
    private int trailTicksRemaining;

    private static ComponentType<EntityStore, DashComponent> componentType;

    public static void setComponentType(@Nonnull ComponentType<EntityStore, DashComponent> type) {
        componentType = type;
    }

    @Nonnull
    public static ComponentType<EntityStore, DashComponent> getComponentType() {
        if (componentType == null) {
            throw new IllegalStateException("DashComponent has not been registered yet");
        }
        return componentType;
    }

    public DashComponent() {
    }

    public long getLastDashTime() {
        return this.lastDashTime;
    }

    public void setLastDashTime(long lastDashTime) {
        this.lastDashTime = lastDashTime;
    }

    @Nullable
    public Vector3d getDashDirection() {
        return this.dashDirection;
    }

    public int getTrailTicksRemaining() {
        return this.trailTicksRemaining;
    }

    public void startDash(@Nonnull Vector3d direction, int trailTicks) {
        this.dashDirection = direction;
        this.trailTicksRemaining = trailTicks;
    }

    public boolean tickTrail() {
        if (this.trailTicksRemaining <= 0) {
            this.dashDirection = null;
            return false;
        }
        this.trailTicksRemaining--;
        if (this.trailTicksRemaining <= 0) {
            this.dashDirection = null;
            return false;
        }
        return true;
    }

    public boolean isDashing() {
        return this.dashDirection != null && this.trailTicksRemaining > 0;
    }

    public void clearDash() {
        this.dashDirection = null;
        this.trailTicksRemaining = 0;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        DashComponent copy = new DashComponent();
        copy.lastDashTime = this.lastDashTime;
        copy.dashDirection = this.dashDirection != null
                ? this.dashDirection.clone()
                : null;
        copy.trailTicksRemaining = this.trailTicksRemaining;
        return copy;
    }
}
