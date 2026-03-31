package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * A single objective within a challenge room.
 */
public final class ChallengeObjective {

    private final Type type;
    private final Vector3i position;
    private boolean completed;
    // MOB_ACTIVATOR fields
    @Nullable
    private Ref<EntityStore> spawnedMob;
    @Nullable
    private Map<String, Integer> mobPool;
    private int spawnCount = 1;
    private boolean mobSpawned;

    public ChallengeObjective(@Nonnull Type type, @Nonnull Vector3i position) {
        this.type = type;
        this.position = position;
    }

    @Nonnull
    public Type getType() {
        return type;
    }

    @Nonnull
    public Vector3i getPosition() {
        return position;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void complete() {
        this.completed = true;
    }

    @Nullable
    public Ref<EntityStore> getSpawnedMob() {
        return spawnedMob;
    }

    // ── MOB_ACTIVATOR ──

    public void setSpawnedMob(@Nullable Ref<EntityStore> spawnedMob) {
        this.spawnedMob = spawnedMob;
        this.mobSpawned = true;
    }

    public boolean isMobSpawned() {
        return mobSpawned;
    }

    @Nullable
    public Map<String, Integer> getMobPool() {
        return mobPool;
    }

    public void setMobPool(@Nullable Map<String, Integer> mobPool) {
        this.mobPool = mobPool;
    }

    public int getSpawnCount() {
        return spawnCount;
    }

    public void setSpawnCount(int spawnCount) {
        this.spawnCount = spawnCount;
    }

    /**
     * Human-readable description for the challenge HUD.
     */
    @Nonnull
    public String getDisplayName() {
        return switch (type) {
            case ACTIVATION_ZONE -> "Reach the activation zone";
            case MOB_ACTIVATOR -> "Defeat the summoned enemy";
            case MOB_CLEAR -> "Clear all enemies";
        };
    }

    public enum Type {
        /**
         * Player must enter an activation zone.
         */
        ACTIVATION_ZONE,
        /**
         * A specific mob must be killed.
         */
        MOB_ACTIVATOR,
        /**
         * All mobs in the room must be cleared.
         */
        MOB_CLEAR
    }
}
