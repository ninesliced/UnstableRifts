package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import dev.ninesliced.unstablerifts.shop.ShopItemData;
import dev.ninesliced.unstablerifts.shop.ShopItemType;

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

    private static final float HEALTH_EPSILON = 0.001f;
    private final RoomType type;
    private final Vector3i anchor;
    private final int rotation;
    private final List<Vector3i> spawnerPositions;
    private final List<ExitSpawner> exitSpawners = new ArrayList<>();
    private final List<Vector3d> prefabMobMarkerPositions;
    private final List<String> mobsToSpawn;
    private final List<TrackedMob> trackedMobs = new ArrayList<>();
    private final List<RoomData> children = new ArrayList<>();
    private final List<Vector3i> mobSpawnPoints = new ArrayList<>();
    private final List<PinnedMobSpawn> pinnedMobSpawns = new ArrayList<>();
    private final List<Vector3i> keySpawnerPositions = new ArrayList<>();
    private final List<PortalMarker> portals = new ArrayList<>();
    private final List<Vector3i> portalExitPositions = new ArrayList<>();
    // ── Door & lock system ──
    private final List<Vector3i> lockDoorBlockPositions = new ArrayList<>();
    private final List<TrackedDoorBlock> trackedDoorBlocks = new ArrayList<>();
    private final List<Vector3i> doorPositions = new ArrayList<>();
    private final List<DoorMarker> doorMarkers = new ArrayList<>();
    private final List<Vector3i> activationZonePositions = new ArrayList<>();
    private final List<ChallengeObjective> challenges = new ArrayList<>();
    private int branchDepth;
    private String branchId;
    private boolean hasMobClearActivator = false;
    private boolean locked = false;
    @Nonnull
    private String enterTitle = "";
    @Nonnull
    private String enterSubtitle = "";
    @Nonnull
    private String unlockTitle = "";
    @Nonnull
    private String unlockSubtitle = "";
    @Nonnull
    private String exitTitle = "";
    @Nonnull
    private String exitSubtitle = "";
    private boolean doorsSealed = false;
    private DoorMode doorMode = DoorMode.ACTIVATOR;
    // ── Portal ──
    private boolean portalSpawned = false;
    private long portalSpawnedAt = 0L;

    // ── Shop system ──
    private final List<ShopItemSlot> shopItemSlots = new ArrayList<>();
    @Nullable
    private Vector3i shopKeeperPosition;
    private double shopActionRange = 5.0;
    private float shopKeeperYaw = 0.0f;
    private int shopRefreshCost = 0;
    private int shopRefreshCount = 0;

    // ── Lock door prefab blocks (pasted at runtime, removed on clear) ──
    // ── Challenge system ──
    private boolean challengeActive = false;
    // ── Boss walk-in tracking ──
    private boolean playerEnteredRoom = false;
    private double distanceWalkedInRoom = 0.0;
    @Nullable
    private Vector3d lastTrackedPosition = null;
    // Bounding box (world coordinates)
    private int boundsMinX, boundsMinZ, boundsMaxX, boundsMaxZ;
    private int boundsMinY, boundsMaxY;
    private boolean hasBounds = false;
    /**
     * How many mobs were assigned to this room (including deferred room mobs).
     */
    private int expectedMobCount = 0;
    /**
     * How many tracked mobs have been confirmed killed via RemoveReason.REMOVE events.
     */
    private volatile int confirmedKillCount = 0;
    @Nullable
    private RoomData parent;
    private boolean cleared = false;

    public RoomData(@Nonnull RoomType type,
                    @Nonnull Vector3i anchor,
                    int rotation,
                    @Nonnull List<Vector3i> spawnerPositions,
                    @Nonnull List<Vector3d> prefabMobMarkerPositions,
                    @Nonnull List<String> mobsToSpawn) {
        this(type, anchor, rotation, spawnerPositions, prefabMobMarkerPositions, mobsToSpawn, 0, "main");
    }

    public RoomData(@Nonnull RoomType type,
                    @Nonnull Vector3i anchor,
                    int rotation,
                    @Nonnull List<Vector3i> spawnerPositions,
                    @Nonnull List<Vector3d> prefabMobMarkerPositions,
                    @Nonnull List<String> mobsToSpawn,
                    int branchDepth,
                    @Nonnull String branchId) {
        this.type = type;
        this.anchor = anchor;
        this.rotation = rotation;
        this.spawnerPositions = new ArrayList<>(spawnerPositions);
        this.prefabMobMarkerPositions = new ArrayList<>(prefabMobMarkerPositions);
        this.mobsToSpawn = new ArrayList<>(mobsToSpawn);
        this.branchDepth = branchDepth;
        this.branchId = branchId;
    }

    private static boolean isMobDead(@Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return false;
        }
        try {
            EntityStatMap statMap = ref.getStore().getComponent(ref, EntityStatMap.getComponentType());
            if (statMap == null) {
                return false;
            }
            int healthIdx = DefaultEntityStatTypes.getHealth();
            EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
            if (healthStat == null) {
                return false;
            }
            return healthStat.get() <= (healthStat.getMin() + HEALTH_EPSILON);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Nonnull
    public List<Vector3i> getLockDoorBlockPositions() {
        return lockDoorBlockPositions;
    }

    public void addLockDoorBlockPosition(@Nonnull Vector3i pos) {
        addLockDoorBlockPosition(pos, this, doorMode, "lockDoor", null);
    }

    public void addLockDoorBlockPosition(@Nonnull Vector3i pos,
                                         @Nonnull RoomData targetRoom,
                                         @Nonnull DoorMode mode) {
        addLockDoorBlockPosition(pos, targetRoom, mode, "trackedDoor", null);
    }

    public void addLockDoorBlockPosition(@Nonnull Vector3i pos,
                                         @Nonnull RoomData targetRoom,
                                         @Nonnull DoorMode mode,
                                         @Nonnull String kind,
                                         @Nullable Vector3i sourceDoorMarker) {
        lockDoorBlockPositions.add(pos);
        trackedDoorBlocks.add(new TrackedDoorBlock(
                new Vector3i(pos),
                targetRoom,
                mode,
                kind,
                sourceDoorMarker != null ? new Vector3i(sourceDoorMarker) : null));
    }

    @Nonnull
    public List<TrackedDoorBlock> getTrackedDoorBlocks() {
        return trackedDoorBlocks;
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
    public List<ExitSpawner> getExitSpawners() {
        return Collections.unmodifiableList(exitSpawners);
    }

    public void addExitSpawner(@Nonnull Vector3i position, int rotation) {
        exitSpawners.add(new ExitSpawner(position, rotation));
    }

    @Nonnull
    public List<Vector3d> getPrefabMobMarkerPositions() {
        return Collections.unmodifiableList(prefabMobMarkerPositions);
    }

    @Nonnull
    public List<String> getMobsToSpawn() {
        return Collections.unmodifiableList(mobsToSpawn);
    }

    public void addMobToSpawn(@Nonnull String mobId) {
        mobsToSpawn.add(mobId);
    }

    @Nonnull
    public List<Ref<EntityStore>> getSpawnedMobs() {
        List<Ref<EntityStore>> refs = new ArrayList<>(trackedMobs.size());
        for (TrackedMob mob : trackedMobs) {
            refs.add(mob.ref);
        }
        return refs;
    }

    public void addSpawnedMob(@Nonnull Ref<EntityStore> ref) {
        UUID refUuid = tryResolveUuid(ref);
        TrackedMob existing = findTrackedMob(ref, refUuid);
        if (existing != null) {
            existing.ref = ref;
            existing.updateDeathState();
            return;
        }
        trackedMobs.add(new TrackedMob(refUuid, ref));
    }

    /**
     * Kept for call-site compatibility — the boolean is no longer used.
     */
    public void addSpawnedMob(@Nonnull Ref<EntityStore> ref, boolean ignored) {
        addSpawnedMob(ref);
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

    public int getExpectedMobCount() {
        return expectedMobCount;
    }

    public void setExpectedMobCount(int count) {
        this.expectedMobCount = Math.max(0, count);
    }

    public void updateMobTracking() {
        for (TrackedMob mob : trackedMobs) {
            mob.updateDeathState();
        }
    }

    public boolean isMobDefeated(@Nullable Ref<EntityStore> ref) {
        if (ref == null) {
            return true;
        }
        UUID refUuid = tryResolveUuid(ref);
        TrackedMob tracked = findTrackedMob(ref, refUuid);
        if (tracked != null) {
            tracked.updateDeathState();
            return tracked.dead || !tracked.ref.isValid();
        }
        return !ref.isValid() || isMobDead(ref);
    }

    /**
     * Called when a dungeon mob belonging to this room is confirmed killed
     * (entity removed with RemoveReason.REMOVE).
     */
    public void onMobKilled() {
        confirmedKillCount++;
    }

    /**
     * Returns true if all expected mobs in this room have been confirmed killed.
     */
    public boolean areAllMobsDead() {
        if (expectedMobCount == 0) {
            return true;
        }
        return confirmedKillCount >= expectedMobCount;
    }

    /**
     * Count of mobs still alive in this room (expected minus confirmed kills).
     */
    public int getAliveMobCount() {
        return Math.max(0, expectedMobCount - confirmedKillCount);
    }

    public int getBranchDepth() {
        return branchDepth;
    }

    public void setBranchDepth(int branchDepth) {
        this.branchDepth = branchDepth;
    }

    @Nonnull
    public String getBranchId() {
        return branchId != null ? branchId : "main";
    }

    public void setBranchId(@Nonnull String branchId) {
        this.branchId = branchId;
    }

    public boolean isMainBranch() {
        return branchDepth == 0;
    }

    @Nonnull
    public List<Vector3i> getMobSpawnPoints() {
        return mobSpawnPoints;
    }

    public void addMobSpawnPoint(@Nonnull Vector3i pos) {
        mobSpawnPoints.add(pos);
    }

    @Nonnull
    public List<Vector3i> getKeySpawnerPositions() {
        return keySpawnerPositions;
    }

    public void addKeySpawnerPosition(@Nonnull Vector3i pos) {
        keySpawnerPositions.add(pos);
    }

    @Nonnull
    public List<Vector3i> getPortalPositions() {
        List<Vector3i> positions = new ArrayList<>(portals.size());
        for (PortalMarker portal : portals) {
            positions.add(portal.position());
        }
        return positions;
    }

    @Nonnull
    public List<PortalMarker> getPortals() {
        return Collections.unmodifiableList(portals);
    }

    public void addPortalPosition(@Nonnull Vector3i pos) {
        addPortal(pos, PortalMode.NEXT_LEVEL);
    }

    public void addPortal(@Nonnull Vector3i pos, @Nonnull PortalMode mode) {
        portals.add(new PortalMarker(pos, mode));
    }

    @Nonnull
    public List<Vector3i> getPortalExitPositions() {
        return portalExitPositions;
    }

    public void addPortalExitPosition(@Nonnull Vector3i pos) {
        portalExitPositions.add(pos);
    }

    public boolean isPortalSpawned() {
        return portalSpawned;
    }

    public void setPortalSpawned(boolean portalSpawned) {
        this.portalSpawned = portalSpawned;
    }

    public long getPortalSpawnedAt() {
        return portalSpawnedAt;
    }

    public void setPortalSpawnedAt(long portalSpawnedAt) {
        this.portalSpawnedAt = portalSpawnedAt;
    }

    // ── Door & lock system ──

    @Nullable
    public PortalMarker findPortalAt(int bx, int by, int bz) {
        for (PortalMarker portal : portals) {
            Vector3i pos = portal.position();
            if (pos.x == bx && pos.z == bz && (pos.y == by || pos.y == by - 1)) {
                return portal;
            }
        }
        return null;
    }

    @Nonnull
    public List<Vector3i> getDoorPositions() {
        return doorPositions;
    }

    public void addDoorPosition(@Nonnull Vector3i pos) {
        doorPositions.add(pos);
    }

    public void addDoorPosition(@Nonnull Vector3i pos, @Nonnull DoorMode mode) {
        doorPositions.add(pos);
        doorMarkers.add(new DoorMarker(pos, mode));
    }

    @Nonnull
    public List<DoorMarker> getDoorMarkers() {
        return Collections.unmodifiableList(doorMarkers);
    }

    public boolean hasDoorMode(@Nonnull DoorMode mode) {
        for (DoorMarker doorMarker : doorMarkers) {
            if (doorMarker.mode() == mode) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    public List<Vector3i> getDoorPositions(@Nonnull DoorMode mode) {
        List<Vector3i> matching = new ArrayList<>();
        for (DoorMarker doorMarker : doorMarkers) {
            if (doorMarker.mode() == mode) {
                matching.add(doorMarker.position());
            }
        }
        return matching;
    }

    public int getDoorCount(@Nonnull DoorMode mode) {
        int count = 0;
        for (DoorMarker doorMarker : doorMarkers) {
            if (doorMarker.mode() == mode) {
                count++;
            }
        }
        return count;
    }

    public boolean hasDoorMarkerNear(@Nonnull Vector3i pos,
                                     @Nonnull DoorMode mode,
                                     int verticalTolerance) {
        for (DoorMarker doorMarker : doorMarkers) {
            if (doorMarker.mode() != mode) {
                continue;
            }
            Vector3i markerPos = doorMarker.position();
            if (markerPos.x == pos.x
                    && markerPos.z == pos.z
                    && Math.abs(markerPos.y - pos.y) <= Math.max(0, verticalTolerance)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    public List<Vector3i> getActivationZonePositions() {
        return activationZonePositions;
    }

    public void addActivationZonePosition(@Nonnull Vector3i pos) {
        activationZonePositions.add(pos);
    }

    public boolean hasMobClearActivator() {
        return hasMobClearActivator;
    }

    public void setHasMobClearActivator(boolean hasMobClearActivator) {
        this.hasMobClearActivator = hasMobClearActivator;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    @Nonnull
    public String getEnterTitle() {
        return enterTitle;
    }

    public void setEnterTitle(@Nonnull String enterTitle) {
        this.enterTitle = enterTitle;
    }

    @Nonnull
    public String getEnterSubtitle() {
        return enterSubtitle;
    }

    public void setEnterSubtitle(@Nonnull String enterSubtitle) {
        this.enterSubtitle = enterSubtitle;
    }

    @Nonnull
    public String getUnlockTitle() {
        return unlockTitle;
    }

    public void setUnlockTitle(@Nonnull String unlockTitle) {
        this.unlockTitle = unlockTitle;
    }

    @Nonnull
    public String getUnlockSubtitle() {
        return unlockSubtitle;
    }

    public void setUnlockSubtitle(@Nonnull String unlockSubtitle) {
        this.unlockSubtitle = unlockSubtitle;
    }

    @Nonnull
    public String getExitTitle() {
        return exitTitle;
    }

    public void setExitTitle(@Nonnull String exitTitle) {
        this.exitTitle = exitTitle;
    }

    @Nonnull
    public String getExitSubtitle() {
        return exitSubtitle;
    }

    public void setExitSubtitle(@Nonnull String exitSubtitle) {
        this.exitSubtitle = exitSubtitle;
    }

    public boolean isDoorsSealed() {
        return doorsSealed;
    }

    public void setDoorsSealed(boolean doorsSealed) {
        this.doorsSealed = doorsSealed;
    }

    @Nonnull
    public DoorMode getDoorMode() {
        return doorMode;
    }

    // ── Challenge system ──

    public void setDoorMode(@Nonnull DoorMode doorMode) {
        this.doorMode = doorMode;
    }

    public boolean isChallengeActive() {
        return challengeActive;
    }

    public void setChallengeActive(boolean challengeActive) {
        this.challengeActive = challengeActive;
    }

    @Nonnull
    public List<ChallengeObjective> getChallenges() {
        return challenges;
    }

    // ── Boss walk-in tracking ──

    public void addChallenge(@Nonnull ChallengeObjective objective) {
        challenges.add(objective);
    }

    public boolean isPlayerEnteredRoom() {
        return playerEnteredRoom;
    }

    public void setPlayerEnteredRoom(boolean playerEnteredRoom) {
        this.playerEnteredRoom = playerEnteredRoom;
    }

    public double getDistanceWalkedInRoom() {
        return distanceWalkedInRoom;
    }

    public void setDistanceWalkedInRoom(double distance) {
        this.distanceWalkedInRoom = distance;
    }

    public void addDistanceWalked(double distance) {
        this.distanceWalkedInRoom += distance;
    }

    @Nullable
    public Vector3d getLastTrackedPosition() {
        return lastTrackedPosition;
    }

    // ── Map bounds ──

    public void setLastTrackedPosition(@Nullable Vector3d lastTrackedPosition) {
        this.lastTrackedPosition = lastTrackedPosition;
    }

    public boolean hasBounds() {
        return hasBounds;
    }

    public int getBoundsMinX() {
        return boundsMinX;
    }

    public int getBoundsMinZ() {
        return boundsMinZ;
    }

    public int getBoundsMaxX() {
        return boundsMaxX;
    }

    public int getBoundsMaxZ() {
        return boundsMaxZ;
    }

    public int getBoundsMinY() {
        return boundsMinY;
    }

    public int getBoundsMaxY() {
        return boundsMaxY;
    }

    public void setBounds(int minX, int minZ, int maxX, int maxZ) {
        this.boundsMinX = minX;
        this.boundsMinZ = minZ;
        this.boundsMaxX = maxX;
        this.boundsMaxZ = maxZ;
        this.hasBounds = true;
    }

    public void setYBounds(int minY, int maxY) {
        this.boundsMinY = minY;
        this.boundsMaxY = maxY;
    }

    public boolean containsY(int y) {
        return hasBounds && y >= boundsMinY && y <= boundsMaxY;
    }

    public boolean containsXZ(int x, int z) {
        return hasBounds && x >= boundsMinX && x <= boundsMaxX && z >= boundsMinZ && z <= boundsMaxZ;
    }

    public boolean contains(int x, int y, int z) {
        return containsXZ(x, z) && containsY(y);
    }

    // ── Pinned mob spawns (from configured UnstableRifts_Mob_Spawner blocks) ──

    /**
     * Check if a Y coordinate is within the room's Y bounds with a margin.
     * The margin shrinks the valid range inward (player must be further inside).
     */
    public boolean containsY(int y, int margin) {
        return hasBounds && y >= (boundsMinY + margin) && y <= (boundsMaxY - margin);
    }

    public void addPinnedMobSpawn(@Nonnull Vector3i position, @Nonnull String mobId) {
        pinnedMobSpawns.add(new PinnedMobSpawn(position, mobId));
    }

    @Nonnull
    public List<PinnedMobSpawn> getPinnedMobSpawns() {
        return pinnedMobSpawns;
    }

    // ── Shop system ──

    @Nullable
    public Vector3i getShopKeeperPosition() {
        return shopKeeperPosition;
    }

    public void setShopKeeperPosition(@Nonnull Vector3i position) {
        this.shopKeeperPosition = position;
    }

    public float getShopKeeperYaw() {
        return shopKeeperYaw;
    }

    public void setShopKeeperYaw(float yaw) {
        this.shopKeeperYaw = yaw;
    }

    public double getShopActionRange() {
        return shopActionRange;
    }

    public void setShopActionRange(double range) {
        this.shopActionRange = range;
    }

    public int getShopRefreshCost() {
        return shopRefreshCost;
    }

    public void setShopRefreshCost(int cost) {
        this.shopRefreshCost = Math.max(0, cost);
    }

    public int getShopRefreshCount() {
        return shopRefreshCount;
    }

    public void setShopRefreshCount(int count) {
        this.shopRefreshCount = Math.max(0, count);
    }

    @Nonnull
    public List<ShopItemSlot> getShopItemSlots() {
        return Collections.unmodifiableList(shopItemSlots);
    }

    public void addShopItemSlot(@Nonnull ShopItemSlot slot) {
        shopItemSlots.add(slot);
    }

    @Nullable
    private TrackedMob findTrackedMob(@Nonnull Ref<EntityStore> ref, @Nullable UUID refUuid) {
        for (TrackedMob mob : trackedMobs) {
            if (refUuid != null && refUuid.equals(mob.uuid)) {
                return mob;
            }
            if (mob.ref == ref) {
                return mob;
            }
        }
        return null;
    }

    @Nullable
    private UUID tryResolveUuid(@Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return null;
        }
        try {
            UUIDComponent uuidComponent = ref.getStore().getComponent(ref, UUIDComponent.getComponentType());
            return uuidComponent != null ? uuidComponent.getUuid() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    @Override
    public String toString() {
        return "RoomData{type=" + type + ", anchor=" + anchor
                + ", branch=" + branchId + "(d" + branchDepth + ")"
                + ", prefabMarkers=" + prefabMobMarkerPositions.size()
                + ", mobs=" + mobsToSpawn.size() + "}";
    }

    public record ExitSpawner(@Nonnull Vector3i position, int rotation) {
    }

    public record PortalMarker(@Nonnull Vector3i position, @Nonnull PortalMode mode) {
    }

    public record DoorMarker(@Nonnull Vector3i position, @Nonnull DoorMode mode) {
    }

    public record PinnedMobSpawn(@Nonnull Vector3i position, @Nonnull String mobId) {
    }

    public record TrackedDoorBlock(@Nonnull Vector3i position,
                                   @Nonnull RoomData targetRoom,
                                   @Nonnull DoorMode mode,
                                   @Nonnull String kind,
                                   @Nullable Vector3i sourceDoorMarker) {
    }

    /**
     * A shop item emplacement parsed from a prefab's ShopItemData block component.
     */
    public record ShopItemSlot(@Nonnull Vector3i position,
                               @Nonnull ShopItemType itemType,
                               int price,
                               @Nonnull List<ShopItemData.WeightedEntry> weapons,
                               @Nonnull List<ShopItemData.WeightedEntry> armors,
                               @Nonnull String minRarity,
                               @Nonnull String maxRarity) {
    }

    private static final class TrackedMob {
        @Nullable
        private final UUID uuid;
        @Nonnull
        private Ref<EntityStore> ref;
        private boolean dead;

        private TrackedMob(@Nullable UUID uuid, @Nonnull Ref<EntityStore> ref) {
            this.uuid = uuid;
            this.ref = ref;
            updateDeathState();
        }

        private void updateDeathState() {
            if (dead) {
                return;
            }
            if (isMobDead(ref)) {
                dead = true;
            }
        }
    }
}
