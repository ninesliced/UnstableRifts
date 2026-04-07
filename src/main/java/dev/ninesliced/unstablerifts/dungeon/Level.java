package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * A single level (floor) within a dungeon run.
 * Contains the room generation graph and mob tracking.
 */
public final class Level {

    private final String name;
    private final int index;
    private final List<RoomData> rooms = new ArrayList<>();
    private final List<Vector3i> keySpawnPositions = new ArrayList<>();

    private final Map<String, List<RoomData>> branches = new HashMap<>();
    private final List<RoomData> mainBranchRooms = new ArrayList<>();

    private final Map<Long, RoomData> blockOwnership = new HashMap<>();

    @Nullable
    private RoomData entranceRoom;
    @Nullable
    private RoomData bossRoom;
    @Nullable
    private RoomData shopRoom;
    @Nullable
    private RoomData treasureRoom;

    public Level(@Nonnull String name, int index) {
        this.name = name;
        this.index = index;
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @Nonnull
    public String getName() {
        return name;
    }

    public int getIndex() {
        return index;
    }

    public void addRoom(@Nonnull RoomData room) {
        rooms.add(room);
        if ((room.getType() == RoomType.SPAWN) && entranceRoom == null) {
            entranceRoom = room;
        }
        if (room.getType() == RoomType.BOSS) {
            bossRoom = room;
        }
        if (room.getType() == RoomType.SHOP && shopRoom == null) {
            shopRoom = room;
        }
        if (room.getType() == RoomType.TREASURE && treasureRoom == null) {
            treasureRoom = room;
        }
        String bid = room.getBranchId();
        branches.computeIfAbsent(bid, k -> new ArrayList<>()).add(room);
        if (room.isMainBranch()) {
            mainBranchRooms.add(room);
        }
    }

    public void addKeySpawnPosition(@Nonnull Vector3i position) {
        keySpawnPositions.add(new Vector3i(position));
    }

    @Nonnull
    public List<RoomData> getRooms() {
        return Collections.unmodifiableList(rooms);
    }

    @Nonnull
    public List<Vector3i> getKeySpawnPositions() {
        return Collections.unmodifiableList(keySpawnPositions);
    }

    @Nullable
    public RoomData getEntranceRoom() {
        return entranceRoom;
    }

    @Nullable
    public RoomData getBossRoom() {
        return bossRoom;
    }

    @Nullable
    public RoomData getShopRoom() {
        return shopRoom;
    }

    @Nullable
    public RoomData getTreasureRoom() {
        return treasureRoom;
    }

    @Nonnull
    public List<RoomData> getMainBranchRooms() {
        return Collections.unmodifiableList(mainBranchRooms);
    }

    @Nonnull
    public Map<String, List<RoomData>> getBranches() {
        return Collections.unmodifiableMap(branches);
    }

    /**
     * Returns all rooms on a specific branch.
     */
    @Nonnull
    public List<RoomData> getBranchRooms(@Nonnull String branchId) {
        List<RoomData> branchRooms = branches.get(branchId);
        return branchRooms != null ? Collections.unmodifiableList(branchRooms) : Collections.emptyList();
    }

    /**
     * Collects all mobs that should be spawned across all rooms.
     */
    @Nonnull
    public List<String> getAllMobsToSpawn() {
        List<String> all = new ArrayList<>();
        for (RoomData room : rooms) {
            all.addAll(room.getMobsToSpawn());
        }
        return all;
    }

    /**
     * Collects all spawned mob refs across all rooms.
     */
    @Nonnull
    public List<Ref<EntityStore>> getAllSpawnedMobs() {
        List<Ref<EntityStore>> all = new ArrayList<>();
        for (RoomData room : rooms) {
            all.addAll(room.getSpawnedMobs());
        }
        return all;
    }

    /**
     * Count of mobs still alive across the entire level.
     */
    public int getAliveMobCount() {
        int count = 0;
        for (RoomData room : rooms) {
            count += room.getAliveMobCount();
        }
        return count;
    }

    /**
     * Total expected mobs across the level (set during distribution, not affected by chunk loading).
     */
    public int getTotalSpawnedMobs() {
        int count = 0;
        for (RoomData room : rooms) {
            count += room.getExpectedMobCount();
        }
        return count;
    }

    /**
     * Tick all rooms to update mob kill tracking.
     */
    public void updateMobTracking() {
        for (RoomData room : rooms) {
            room.updateMobTracking();
        }
    }

    public void setBlockOwner(int x, int z, @Nonnull RoomData room) {
        blockOwnership.put(packXZ(x, z), room);
    }

    @Nullable
    public RoomData getBlockOwner(int x, int z) {
        return blockOwnership.get(packXZ(x, z));
    }

    /**
     * Resolves the room at a full 3D position.
     * <p>
     * The fast ownership map is keyed by X/Z only, so when rooms share columns at
     * different heights we validate the Y bounds and fall back to a bounded scan.
     */
    @Nullable
    public RoomData findRoomAt(int x, int y, int z) {
        RoomData owner = getBlockOwner(x, z);
        if (owner != null && (!owner.hasBounds() || owner.contains(x, y, z))) {
            return owner;
        }

        for (RoomData room : rooms) {
            if (room == owner) {
                continue;
            }
            if (!room.hasBounds()) {
                continue;
            }
            if (room.contains(x, y, z)) {
                return room;
            }
        }

        return owner != null && !owner.hasBounds() ? owner : null;
    }

    @Nonnull
    public Map<Long, RoomData> getBlockOwnership() {
        return Collections.unmodifiableMap(blockOwnership);
    }

}
