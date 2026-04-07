package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.logging.Logger;

/**
 * Central door management: seals and unseals rooms by placing/removing door blocks
 * at the positions recorded by {@link MarkerType#DOOR} markers in the prefab.
 */
public final class DoorService {

    private static final Logger LOGGER = Logger.getLogger(DoorService.class.getName());
    public static final double KEY_INTERACTION_RADIUS = 5.0;

    public DoorService() {
    }

    @Nullable
    public KeyDoorTarget findKeyDoorTarget(@Nonnull Level level, @Nonnull Vector3i blockPos) {
        for (RoomData trackingRoom : level.getRooms()) {
            for (RoomData.TrackedDoorBlock trackedDoorBlock : trackingRoom.getTrackedDoorBlocks()) {
                if (trackedDoorBlock.mode() != DoorMode.KEY) {
                    continue;
                }

                Vector3i trackedPos = trackedDoorBlock.position();
                if (trackedPos.x != blockPos.x || trackedPos.y != blockPos.y || trackedPos.z != blockPos.z) {
                    continue;
                }

                return new KeyDoorTarget(
                        trackingRoom,
                        trackedDoorBlock.targetRoom(),
                        trackedDoorBlock.sourceDoorMarker() != null
                                ? new Vector3i(trackedDoorBlock.sourceDoorMarker())
                                : new Vector3i(blockPos));
            }
        }

        for (RoomData room : level.getRooms()) {
            if (!room.isLocked() || !room.isDoorsSealed()) {
                continue;
            }

            for (Vector3i doorPos : room.getDoorPositions(DoorMode.KEY)) {
                if (doorPos.x == blockPos.x
                        && doorPos.y == blockPos.y
                        && doorPos.z == blockPos.z
                        && hasTrackedKeyDoorForMarker(level, room, doorPos)) {
                    return new KeyDoorTarget(room, room, new Vector3i(blockPos));
                }
            }
        }

        return null;
    }

    @Nullable
    public NearbyKeyDoor findNearbyKeyDoor(@Nonnull Level level,
                                           @Nonnull Vector3d playerPos,
                                           double interactionRadius) {
        double interactionRadiusSq = interactionRadius * interactionRadius;
        NearbyKeyDoor nearestDoor = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (RoomData trackingRoom : level.getRooms()) {
            for (RoomData.TrackedDoorBlock trackedDoorBlock : trackingRoom.getTrackedDoorBlocks()) {
                if (trackedDoorBlock.mode() != DoorMode.KEY) {
                    continue;
                }

                Vector3i doorBlockPos = trackedDoorBlock.position();
                double dx = playerPos.x - (doorBlockPos.x + 0.5d);
                double dy = playerPos.y - (doorBlockPos.y + 0.5d);
                double dz = playerPos.z - (doorBlockPos.z + 0.5d);
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > interactionRadiusSq || distSq >= nearestDistSq) {
                    continue;
                }

                nearestDistSq = distSq;
                nearestDoor = new NearbyKeyDoor(
                        new KeyDoorTarget(
                                trackingRoom,
                                trackedDoorBlock.targetRoom(),
                                trackedDoorBlock.sourceDoorMarker() != null
                                        ? new Vector3i(trackedDoorBlock.sourceDoorMarker())
                                        : new Vector3i(doorBlockPos)),
                        new Vector3i(doorBlockPos));
            }
        }

        return nearestDoor;
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
        room.getTrackedDoorBlocks().clear();
    }

    public boolean unlockKeyDoor(@Nonnull Level level,
                                 @Nonnull World world,
                                 @Nonnull KeyDoorTarget target) {
        RoomData targetRoom = target.targetRoom();
        removeTrackedKeyDoorBlocks(level, world, targetRoom, target.sourceDoorMarker());

        if (hasRemainingKeyDoorBlocks(level, targetRoom)) {
            return true;
        }

        if (targetRoom.isLocked() && !targetRoom.isCleared()) {
            return true;
        }

        if (targetRoom.isDoorsSealed()) {
            unsealRoomAndChildEntrances(targetRoom, world);
        }
        removeTrackedDoorBlocks(level, world, targetRoom, trackedDoorBlock -> trackedDoorBlock.mode() != DoorMode.KEY);
        return true;
    }

    public boolean hasRemainingKeyDoors(@Nonnull Level level,
                                        @Nonnull RoomData targetRoom) {
        return hasRemainingKeyDoorBlocks(level, targetRoom);
    }

    private boolean hasRemainingKeyDoorBlocks(@Nonnull Level level,
                                              @Nonnull RoomData targetRoom) {
        for (RoomData trackingRoom : level.getRooms()) {
            for (RoomData.TrackedDoorBlock trackedDoorBlock : trackingRoom.getTrackedDoorBlocks()) {
                if (trackedDoorBlock.mode() == DoorMode.KEY && trackedDoorBlock.targetRoom() == targetRoom) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasTrackedKeyDoorForMarker(@Nonnull Level level,
                                               @Nonnull RoomData targetRoom,
                                               @Nonnull Vector3i sourceDoorMarker) {
        for (RoomData trackingRoom : level.getRooms()) {
            for (RoomData.TrackedDoorBlock trackedDoorBlock : trackingRoom.getTrackedDoorBlocks()) {
                if (trackedDoorBlock.mode() != DoorMode.KEY || trackedDoorBlock.targetRoom() != targetRoom) {
                    continue;
                }

                if (sameDoorMarker(trackedDoorBlock.sourceDoorMarker(), sourceDoorMarker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void removeTrackedKeyDoorBlocks(@Nonnull Level level,
                                            @Nonnull World world,
                                            @Nonnull RoomData targetRoom,
                                            @Nullable Vector3i sourceDoorMarker) {
        removeTrackedDoorBlocks(level, world, targetRoom, trackedDoorBlock ->
                trackedDoorBlock.mode() == DoorMode.KEY
                        && sameDoorMarker(trackedDoorBlock.sourceDoorMarker(), sourceDoorMarker));
    }

    private void removeTrackedDoorBlocks(@Nonnull Level level,
                                         @Nonnull World world,
                                         @Nonnull RoomData targetRoom,
                                         @Nonnull java.util.function.Predicate<RoomData.TrackedDoorBlock> shouldRemove) {
        for (RoomData trackingRoom : level.getRooms()) {
            Iterator<RoomData.TrackedDoorBlock> iterator = trackingRoom.getTrackedDoorBlocks().iterator();
            while (iterator.hasNext()) {
                RoomData.TrackedDoorBlock trackedDoorBlock = iterator.next();
                if (trackedDoorBlock.targetRoom() != targetRoom || !shouldRemove.test(trackedDoorBlock)) {
                    continue;
                }

                Vector3i pos = trackedDoorBlock.position();
                try {
                    world.setBlock(pos.x, pos.y, pos.z, DungeonConstants.EMPTY_BLOCK, 0);
                } catch (Exception e) {
                    LOGGER.fine("Failed to remove tracked door block at " + pos + ": " + e.getMessage());
                }

                iterator.remove();
                trackingRoom.getLockDoorBlockPositions().remove(pos);
            }
        }
    }

    private boolean sameDoorMarker(@Nullable Vector3i a, @Nullable Vector3i b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.x == b.x && a.y == b.y && a.z == b.z;
    }

    public record KeyDoorTarget(@Nonnull RoomData trackingRoom,
                                @Nonnull RoomData targetRoom,
                                @Nonnull Vector3i sourceDoorMarker) {
    }

    public record NearbyKeyDoor(@Nonnull KeyDoorTarget target,
                                @Nonnull Vector3i blockPosition) {
    }
}
