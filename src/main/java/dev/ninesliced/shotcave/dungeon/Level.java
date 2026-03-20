package dev.ninesliced.shotcave.dungeon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single level (floor) within a dungeon run.
 * Contains the room generation graph and mob tracking.
 */
public final class Level {

    private final String name;
    private final int index;
    private final List<RoomData> rooms = new ArrayList<>();

    private final Map<String, List<RoomData>> branches = new HashMap<>();
    private final List<RoomData> mainBranchRooms = new ArrayList<>();

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

    @Nonnull
    public List<RoomData> getRooms() {
        return Collections.unmodifiableList(rooms);
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
     * Total mobs spawned across the level.
     */
    public int getTotalSpawnedMobs() {
        int count = 0;
        for (RoomData room : rooms) {
            count += room.getSpawnedMobs().size();
        }
        return count;
    }
}

