package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.logging.Logger;

/**
 * Central door management: seals and unseals rooms by placing/removing door blocks
 * at the positions recorded by {@link MarkerType#DOOR} markers in the prefab.
 */
public final class DoorService {

    private static final Logger LOGGER = Logger.getLogger(DoorService.class.getName());

    public DoorService() {
    }

    /**
     * Places door blocks at all door positions in the room.
     */
    public void sealRoom(@Nonnull RoomData room, @Nonnull World world, @Nonnull String doorBlock) {
        if (room.isDoorsSealed()) return;
        for (Vector3i pos : room.getDoorPositions()) {
            try {
                world.setBlock(pos.x, pos.y, pos.z, doorBlock, 0);
            } catch (Exception e) {
                LOGGER.fine("Failed to seal door at " + pos + ": " + e.getMessage());
            }
        }
        room.setDoorsSealed(true);
    }

    /**
     * Removes door blocks (sets to Empty) at all door positions in the room.
     */
    public void unsealRoom(@Nonnull RoomData room, @Nonnull World world) {
        if (!room.isDoorsSealed()) return;
        for (Vector3i pos : room.getDoorPositions()) {
            try {
                world.setBlock(pos.x, pos.y, pos.z, DungeonConstants.EMPTY_BLOCK, 0);
            } catch (Exception e) {
                LOGGER.fine("Failed to unseal door at " + pos + ": " + e.getMessage());
            }
        }
        room.setDoorsSealed(false);
    }

    /**
     * Seals the room's own doors plus any child room entrance doors.
     * Used for LOCK_ROOM rooms where the player is trapped until clearing.
     */
    public void sealRoomAndChildEntrances(@Nonnull RoomData room, @Nonnull Level level,
                                          @Nonnull World world, @Nonnull String doorBlock) {
        sealRoom(room, world, doorBlock);
        for (RoomData child : room.getChildren()) {
            if (!child.getDoorPositions().isEmpty() && !child.isDoorsSealed()) {
                sealRoom(child, world, doorBlock);
            }
        }
    }

    /**
     * Unseals the room's own doors plus any child room entrance doors.
     */
    public void unsealRoomAndChildEntrances(@Nonnull RoomData room, @Nonnull World world) {
        unsealRoom(room, world);
        for (RoomData child : room.getChildren()) {
            if (child.isDoorsSealed()) {
                unsealRoom(child, world);
            }
        }
    }

    /**
     * Called when a player enters a locked room. Seals if not yet cleared.
     */
    public void onPlayerEnterRoom(@Nonnull RoomData room, @Nonnull Game game,
                                  @Nonnull Level level, @Nonnull World world, @Nonnull String doorBlock) {
        if (!room.isLocked() || room.isCleared() || room.isDoorsSealed()) return;
        if (!hasSealTargets(room)) return;

        sealRoomAndChildEntrances(room, level, world, doorBlock);
    }

    private boolean hasSealTargets(@Nonnull RoomData room) {
        if (!room.getDoorPositions().isEmpty()) {
            return true;
        }

        for (RoomData child : room.getChildren()) {
            if (!child.getDoorPositions().isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Called when a room is cleared. Unseals doors and removes lock door prefab blocks.
     */
    public void onRoomCleared(@Nonnull RoomData room, @Nonnull World world) {
        if (room.isDoorsSealed()) {
            unsealRoomAndChildEntrances(room, world);
        }
        // Remove lock door prefab blocks (pasted at runtime by pasteLockDoor).
        for (Vector3i pos : room.getLockDoorBlockPositions()) {
            try {
                world.setBlock(pos.x, pos.y, pos.z, DungeonConstants.EMPTY_BLOCK, 0);
            } catch (Exception e) {
                LOGGER.fine("Failed to remove lock door block at " + pos + ": " + e.getMessage());
            }
        }
        room.getLockDoorBlockPositions().clear();
    }
}
