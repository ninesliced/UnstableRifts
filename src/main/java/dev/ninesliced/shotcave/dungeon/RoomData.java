package dev.ninesliced.shotcave.dungeon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single room in the dungeon generation graph.
 * Tracks spatial placement, mob spawning, and parent–child relationships.
 */
public final class RoomData {

    private final RoomType type;
    private final Vector3i anchor;
    private final int rotation;
    private final List<Vector3i> spawnerPositions;
    private final List<Vector3d> prefabMobMarkerPositions;
    private final List<String> mobsToSpawn;
    private final List<Ref<EntityStore>> spawnedMobs = new ArrayList<>();
    private final List<RoomData> children = new ArrayList<>();
    @Nullable
    private RoomData parent;
    private boolean cleared = false;

    public RoomData(@Nonnull RoomType type,
                    @Nonnull Vector3i anchor,
                    int rotation,
                    @Nonnull List<Vector3i> spawnerPositions,
                    @Nonnull List<Vector3d> prefabMobMarkerPositions,
                    @Nonnull List<String> mobsToSpawn) {
        this.type = type;
        this.anchor = anchor;
        this.rotation = rotation;
        this.spawnerPositions = new ArrayList<>(spawnerPositions);
        this.prefabMobMarkerPositions = new ArrayList<>(prefabMobMarkerPositions);
        this.mobsToSpawn = new ArrayList<>(mobsToSpawn);
    }

    @Nonnull
    public RoomType getType() {
        return type;
    }

    @Nonnull
    public Vector3i getAnchor() {
        return anchor;
    }

    public int getRotation() {
        return rotation;
    }

    @Nonnull
    public List<Vector3i> getSpawnerPositions() {
        return Collections.unmodifiableList(spawnerPositions);
    }

    @Nonnull
    public List<Vector3d> getPrefabMobMarkerPositions() {
        return Collections.unmodifiableList(prefabMobMarkerPositions);
    }

    @Nonnull
    public List<String> getMobsToSpawn() {
        return Collections.unmodifiableList(mobsToSpawn);
    }

    @Nonnull
    public List<Ref<EntityStore>> getSpawnedMobs() {
        return spawnedMobs;
    }

    public void addSpawnedMob(@Nonnull Ref<EntityStore> ref) {
        UUID refUuid = null;
        if (ref.isValid()) {
            try {
                UUIDComponent uuidComponent = ref.getStore().getComponent(ref, UUIDComponent.getComponentType());
                if (uuidComponent != null) {
                    refUuid = uuidComponent.getUuid();
                }
            } catch (Exception ignored) {
            }
        }

        for (Ref<EntityStore> existing : spawnedMobs) {
            if (existing == ref) {
                return;
            }
            if (refUuid != null && existing.isValid()) {
                try {
                    UUIDComponent existingUuidComponent = existing.getStore().getComponent(existing, UUIDComponent.getComponentType());
                    if (existingUuidComponent != null && refUuid.equals(existingUuidComponent.getUuid())) {
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        spawnedMobs.add(ref);
    }

    public boolean hasPrefabMobMarkerNear(@Nonnull Vector3d position, double maxDistanceSq) {
        for (Vector3d markerPosition : prefabMobMarkerPositions) {
            double dx = markerPosition.x - position.x;
            double dy = markerPosition.y - position.y;
            double dz = markerPosition.z - position.z;
            if (dx * dx + dy * dy + dz * dz <= maxDistanceSq) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public RoomData getParent() {
        return parent;
    }

    public void setParent(@Nullable RoomData parent) {
        this.parent = parent;
    }

    @Nonnull
    public List<RoomData> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public void addChild(@Nonnull RoomData child) {
        children.add(child);
        child.setParent(this);
    }

    public boolean isCleared() {
        return cleared;
    }

    public void setCleared(boolean cleared) {
        this.cleared = cleared;
    }

    /**
     * Returns true if all spawned mobs in this room are dead (invalid refs).
     */
    public boolean areAllMobsDead() {
        if (spawnedMobs.isEmpty()) {
            return true;
        }
        for (Ref<EntityStore> mob : spawnedMobs) {
            if (mob.isValid()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Count of mobs still alive in this room.
     */
    public int getAliveMobCount() {
        int count = 0;
        for (Ref<EntityStore> mob : spawnedMobs) {
            if (mob.isValid()) {
                count++;
            }
        }
        return count;
    }

    @Nonnull
    @Override
    public String toString() {
        return "RoomData{type=" + type + ", anchor=" + anchor
                + ", prefabMarkers=" + prefabMobMarkerPositions.size()
                + ", mobs=" + mobsToSpawn.size() + "}";
    }
}

