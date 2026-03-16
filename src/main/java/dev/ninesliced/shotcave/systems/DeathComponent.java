package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * ECS component storing per-player death and revive state.
 * Attached to every player entity by {@link DeathPlayerAddedSystem}.
 */
public final class DeathComponent implements Component<EntityStore> {

    public static final long REVIVE_WINDOW_MS = 30_000L;

    public static final float REVIVE_DURATION_S = 5.0f;

    public static final long REVIVE_GRACE_MS = 600L;

    private static ComponentType<EntityStore, DeathComponent> componentType;

    private boolean dead;
    private boolean ghost;
    private long deathTime;
    private float reviveProgress;
    private long lastReviveTickTime;
    @Nullable
    private UUID reviverUuid;
    @Nullable
    private Vector3d deathPosition;

    public DeathComponent() {
    }

    public static void setComponentType(@Nonnull ComponentType<EntityStore, DeathComponent> type) {
        componentType = type;
    }

    @Nonnull
    public static ComponentType<EntityStore, DeathComponent> getComponentType() {
        if (componentType == null) {
            throw new IllegalStateException("DeathComponent has not been registered yet");
        }
        return componentType;
    }

    /**
     * Marks the player as dead and records the revive marker position. Starts the revive window timer.
     */
    public void markDead(@Nonnull Vector3d position) {
        this.dead = true;
        this.ghost = false;
        this.deathTime = System.currentTimeMillis();
        this.reviveProgress = 0.0f;
        this.reviverUuid = null;
        this.deathPosition = position.clone();
    }

    public void markGhost() {
        this.ghost = true;
        this.deathPosition = null;
        this.reviveProgress = 0.0f;
        this.reviverUuid = null;
    }

    public void revive() {
        this.dead = false;
        this.ghost = false;
        this.deathTime = 0L;
        this.reviveProgress = 0.0f;
        this.reviverUuid = null;
        this.deathPosition = null;
    }

    public void reset() {
        revive();
    }

    public boolean isDead() {
        return dead;
    }

    public boolean isGhost() {
        return ghost;
    }

    /**
     * Returns {@code true} if the player is dead but still within the revive window
     * (not yet a ghost).
     */
    public boolean isInReviveWindow() {
        return dead && !ghost
                && (System.currentTimeMillis() - deathTime) < REVIVE_WINDOW_MS;
    }

    public boolean isReviveWindowExpired() {
        return dead && !ghost
                && (System.currentTimeMillis() - deathTime) >= REVIVE_WINDOW_MS;
    }

    public long getDeathTime() {
        return deathTime;
    }

    @Nullable
    public Vector3d getDeathPosition() {
        return deathPosition;
    }

    public float getReviveProgress() {
        return reviveProgress;
    }

    /**
     * Adds revive progress from a specific reviver. If the reviver changes,
     * progress resets.
     *
     * @param dt       delta time in seconds
     * @param reviverId UUID of the player performing the revive
     */
    public void addReviveProgress(float dt, @Nonnull UUID reviverId) {
        if (this.reviverUuid != null && !this.reviverUuid.equals(reviverId)) {
            this.reviveProgress = 0.0f;
        }
        this.reviverUuid = reviverId;
        this.reviveProgress += dt;
        this.lastReviveTickTime = System.currentTimeMillis();
    }

    /**
     * Clears revive progress and reviver tracking only if the grace period
     * has elapsed since the last revive tick. This prevents flickering when
     * the crouch key pulses briefly between frames.
     *
     * @return {@code true} if progress was actually cleared
     */
    public boolean clearReviveProgressIfExpired() {
        if (this.reviverUuid == null) return false;
        if ((System.currentTimeMillis() - this.lastReviveTickTime) >= REVIVE_GRACE_MS) {
            this.reviveProgress = 0.0f;
            this.reviverUuid = null;
            return true;
        }
        return false;
    }

    public void clearReviveProgress() {
        this.reviveProgress = 0.0f;
        this.reviverUuid = null;
        this.lastReviveTickTime = 0L;
    }

    public boolean isReviveComplete() {
        return reviveProgress >= REVIVE_DURATION_S;
    }

    @Nullable
    public UUID getReviverUuid() {
        return reviverUuid;
    }

    /**
     * Returns the remaining seconds in the revive window, clamped to 0.
     */
    public float getReviveWindowRemainingSeconds() {
        long elapsed = System.currentTimeMillis() - deathTime;
        return Math.max(0f, (REVIVE_WINDOW_MS - elapsed) / 1000.0f);
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        DeathComponent copy = new DeathComponent();
        copy.dead = this.dead;
        copy.ghost = this.ghost;
        copy.deathTime = this.deathTime;
        copy.reviveProgress = this.reviveProgress;
        copy.lastReviveTickTime = this.lastReviveTickTime;
        copy.reviverUuid = this.reviverUuid;
        copy.deathPosition = this.deathPosition != null ? this.deathPosition.clone() : null;
        return copy;
    }
}
