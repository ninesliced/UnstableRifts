package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import dev.ninesliced.unstablerifts.systems.KweebecScaleHelper;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Handles dungeon mob spawning, spatial clustering, and kill tracking.
 */
public final class MobSpawningService {

    private static final Logger LOGGER = Logger.getLogger(MobSpawningService.class.getName());

    /**
     * Max distance (squared) between two spawn points to be in the same cluster.
     */
    private static final double CLUSTER_RADIUS_SQ = 10.0 * 10.0;
    /**
     * Max random XZ offset from the cluster center when spawning a mob.
     */
    private static final double CLUSTER_SPREAD = 2.5;

    /**
     * Maps each dungeon mob UUID to the room it belongs to.
     * Populated when mobs are spawned; used by MobDeathTrackingSystem to route kill events.
     */
    private final ConcurrentHashMap<UUID, RoomData> dungeonMobRegistry = new ConcurrentHashMap<>();

    /**
     * Registers a dungeon mob UUID so that kill events can be routed to its room.
     */
    public void registerDungeonMob(@Nonnull UUID uuid, @Nonnull RoomData room) {
        dungeonMobRegistry.put(uuid, room);
    }

    /**
     * Called by MobDeathTrackingSystem when an NPC entity is permanently removed.
     * Increments the kill counter for the owning room.
     */
    public void onDungeonMobKilled(@Nonnull UUID uuid) {
        RoomData room = dungeonMobRegistry.remove(uuid);
        if (room != null) {
            room.onMobKilled();
        }
    }

    /**
     * Removes all mob registry entries belonging to the given rooms.
     */
    public void removeRegistryEntriesForRooms(@Nonnull java.util.Set<RoomData> rooms) {
        dungeonMobRegistry.values().removeIf(rooms::contains);
    }

    /**
     * Spawns all mobs for a dungeon level: pinned, configured, and randomly-distributed.
     */
    public void spawnLevelMobs(@Nonnull Level level, @Nonnull Store<EntityStore> store) {
        int totalSpawned = 0;
        Random random = new Random();

        for (RoomData room : level.getRooms()) {
            int plannedMobCount = room.getMobsToSpawn().size()
                    + room.getPinnedMobSpawns().size()
                    + room.getPrefabMobMarkerPositions().size();
            room.setExpectedMobCount(plannedMobCount);

            // Locked rooms defer ALL mob spawning until the player enters.
            if (room.isLocked()) continue;

            spawnPinnedMobs(room, store);
            totalSpawned += spawnPoolMobs(room, store, random);
        }
        LOGGER.info("Spawned " + totalSpawned + " mobs for level " + level.getName());
    }

    /**
     * Spawns mobs only for the first {@code count} rooms in the level (by insertion order).
     * Sets expected mob counts for ALL rooms but only spawns for the early ones.
     * Returns the set of rooms that were spawned so the caller can skip them later.
     */
    @Nonnull
    public Set<RoomData> spawnEarlyRoomMobs(@Nonnull Level level, @Nonnull Store<EntityStore> store, int count) {
        int totalSpawned = 0;
        int roomsSpawned = 0;
        Random random = new Random();
        Set<RoomData> spawnedRooms = new HashSet<>();

        for (RoomData room : level.getRooms()) {
            int plannedMobCount = room.getMobsToSpawn().size()
                    + room.getPinnedMobSpawns().size()
                    + room.getPrefabMobMarkerPositions().size();
            room.setExpectedMobCount(plannedMobCount);

            if (room.isLocked()) continue;

            if (roomsSpawned < count) {
                spawnPinnedMobs(room, store);
                totalSpawned += spawnPoolMobs(room, store, random);
                spawnedRooms.add(room);
                roomsSpawned++;
            }
        }
        LOGGER.info("Early-spawned " + totalSpawned + " mobs in " + roomsSpawned
                + "/" + level.getRooms().size() + " rooms for level " + level.getName());
        return spawnedRooms;
    }

    /**
     * Spawns mobs for all rooms in the level that were NOT already spawned.
     */
    public void spawnRemainingMobs(@Nonnull Level level, @Nonnull Store<EntityStore> store,
                                   @Nonnull Set<RoomData> alreadySpawned) {
        int totalSpawned = 0;
        Random random = new Random();

        for (RoomData room : level.getRooms()) {
            if (room.isLocked()) continue;
            if (alreadySpawned.contains(room)) continue;

            spawnPinnedMobs(room, store);
            totalSpawned += spawnPoolMobs(room, store, random);
        }
        LOGGER.info("Spawned remaining " + totalSpawned + " mobs for level " + level.getName());
    }

    /**
     * Spawns all mobs for a single room (pinned + random pool).
     * Used when a player enters a locked room whose spawning was deferred.
     */
    public void spawnRoomMobs(@Nonnull RoomData room, @Nonnull Store<EntityStore> store) {
        spawnPinnedMobs(room, store);
        int poolSpawned = spawnPoolMobs(room, store, new Random());
        LOGGER.info("Deferred spawn for room " + room.getType() + " at " + room.getAnchor()
                + ": pinned=" + room.getPinnedMobSpawns().size() + " pool=" + poolSpawned);
    }

    /**
     * Spawns randomly-distributed mobs from the room's mob pool at its spawn points.
     * Returns the number of mobs successfully spawned.
     */
    private int spawnPoolMobs(@Nonnull RoomData room, @Nonnull Store<EntityStore> store, @Nonnull Random random) {
        List<String> mobs = room.getMobsToSpawn();
        List<Vector3i> allPoints = room.getMobSpawnPoints();
        if (mobs.isEmpty() || allPoints.isEmpty()) return 0;

        List<Vector3i> chosen = new ArrayList<>(allPoints);
        Collections.shuffle(chosen, random);
        if (chosen.size() > mobs.size()) {
            chosen = chosen.subList(0, mobs.size());
        }

        List<List<Integer>> clusters = buildClusters(chosen);
        int spawned = 0;
        int mobIndex = 0;
        for (List<Integer> cluster : clusters) {
            double cx = 0, cy = 0, cz = 0;
            for (int idx : cluster) {
                Vector3i sp = chosen.get(idx);
                cx += sp.x;
                cy += sp.y;
                cz += sp.z;
            }
            cx /= cluster.size();
            cy /= cluster.size();
            cz /= cluster.size();

            for (int k = 0; k < cluster.size() && mobIndex < mobs.size(); k++, mobIndex++) {
                String mobId = mobs.get(mobIndex);
                if (mobId == null || mobId.isBlank()) continue;

                double ox = (random.nextDouble() - 0.5) * 2.0 * CLUSTER_SPREAD;
                double oz = (random.nextDouble() - 0.5) * 2.0 * CLUSTER_SPREAD;
                Vector3d spawnPos = new Vector3d(cx + 0.5 + ox, cy + 1.0, cz + 0.5 + oz);

                Ref<EntityStore> mobRef = spawnMob(store, mobId, spawnPos, room);
                if (mobRef != null) spawned++;
            }
        }
        return spawned;
    }

    /**
     * Spawns pinned mobs (from configured spawner blocks) at their exact positions.
     */
    public void spawnPinnedMobs(@Nonnull RoomData room, @Nonnull Store<EntityStore> store) {
        for (RoomData.PinnedMobSpawn p : room.getPinnedMobSpawns()) {
            Vector3d spawnPos = new Vector3d(
                    p.position().x + 0.5, p.position().y + 1.0, p.position().z + 0.5);
            spawnMob(store, p.mobId(), spawnPos, room);
        }
    }

    @javax.annotation.Nullable
    private Ref<EntityStore> spawnMob(@Nonnull Store<EntityStore> store,
                                      @Nonnull String mobId,
                                      @Nonnull Vector3d position,
                                      @Nonnull RoomData room) {
        try {
            var mobResult = NPCPlugin.get().spawnNPC(store, mobId, null, position, Rotation3f.ZERO);
            Ref<EntityStore> mobRef = mobResult != null ? mobResult.first() : null;
            if (mobRef != null) {
                KweebecScaleHelper.applyScale(store, mobRef, mobId);
                room.addSpawnedMob(mobRef);
                UUIDComponent uuidComp = store.getComponent(mobRef, UUIDComponent.getComponentType());
                if (uuidComp != null) {
                    registerDungeonMob(uuidComp.getUuid(), room);
                }
            }
            return mobRef;
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Failed to spawn mob: " + mobId, e);
            return null;
        }
    }

    /**
     * Groups spawn point indices into spatial clusters. Two points belong to the same
     * cluster if at least one existing member is within CLUSTER_RADIUS_SQ.
     */
    @Nonnull
    List<List<Integer>> buildClusters(@Nonnull List<Vector3i> points) {
        List<List<Integer>> clusters = new ArrayList<>();
        boolean[] assigned = new boolean[points.size()];

        for (int i = 0; i < points.size(); i++) {
            if (assigned[i]) continue;

            List<Integer> cluster = new ArrayList<>();
            cluster.add(i);
            assigned[i] = true;

            for (int c = 0; c < cluster.size(); c++) {
                Vector3i member = points.get(cluster.get(c));
                for (int j = 0; j < points.size(); j++) {
                    if (assigned[j]) continue;
                    Vector3i candidate = points.get(j);
                    double dx = member.x - candidate.x;
                    double dy = member.y - candidate.y;
                    double dz = member.z - candidate.z;
                    if (dx * dx + dy * dy + dz * dz <= CLUSTER_RADIUS_SQ) {
                        cluster.add(j);
                        assigned[j] = true;
                    }
                }
            }

            clusters.add(cluster);
        }
        return clusters;
    }
}
