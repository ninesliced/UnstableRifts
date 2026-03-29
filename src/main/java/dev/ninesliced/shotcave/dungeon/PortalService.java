package dev.ninesliced.shotcave.dungeon;

import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Manages portal block placement and removal in dungeon rooms,
 * and provides collision detection and destination resolution for portal teleportation.
 */
public final class PortalService {

    private static final Logger LOGGER = Logger.getLogger(PortalService.class.getName());
    private static final String PORTAL_BLOCK = "Shotcave_Dungeon_Portal";

    public PortalService() {
    }

    /**
     * Places portal blocks at all portal positions in the room.
     * No-op if already spawned.
     */
    public void spawnPortal(@Nonnull RoomData room, @Nonnull World world) {
        if (room.isPortalSpawned()) return;

        // If no portal positions were marked in the prefab, use a fallback position
        // based on the room's bounds (center) or anchor ONLY for boss rooms.
        if (room.getPortals().isEmpty()) {
            if (room.getType() == RoomType.BOSS) {
                Vector3i fallback;
                if (room.hasBounds()) {
                    fallback = new Vector3i(
                        (room.getBoundsMinX() + room.getBoundsMaxX()) / 2,
                        room.getBoundsMinY() + 1,
                        (room.getBoundsMinZ() + room.getBoundsMaxZ()) / 2
                    );
                } else {
                    Vector3i anchor = room.getAnchor();
                    fallback = new Vector3i(anchor.x + 5, anchor.y + 1, anchor.z + 5);
                }
                room.addPortal(fallback, PortalMode.NEXT_LEVEL);
                LOGGER.info("Room " + room.getType() + " at " + room.getAnchor() + " has no portal markers. Using fallback at " + fallback);
            } else {
                return;
            }
        }

        for (RoomData.PortalMarker portal : room.getPortals()) {
            Vector3i pos = portal.position();
            try {
                world.setBlock(pos.x, pos.y, pos.z, PORTAL_BLOCK, 0);
            } catch (Exception e) {
                LOGGER.warning("Failed to place portal at " + pos + ": " + e.getMessage());
            }
        }
        room.setPortalSpawned(true);
        room.setPortalSpawnedAt(System.currentTimeMillis());
    }

    /**
     * Removes portal blocks (sets to Empty) at all portal positions in the room.
     */
    public void removePortal(@Nonnull RoomData room, @Nonnull World world) {
        if (!room.isPortalSpawned()) return;

        for (Vector3i pos : room.getPortalPositions()) {
            try {
                world.setBlock(pos.x, pos.y, pos.z, "Empty", 0);
            } catch (Exception e) {
                LOGGER.warning("Failed to remove portal at " + pos + ": " + e.getMessage());
            }
        }
        room.setPortalSpawned(false);
        room.setPortalSpawnedAt(0L);
    }

    /**
     * Returns true if the level currently has at least one spawned portal block.
     */
    public boolean hasActivePortals(@Nonnull Level level) {
        for (RoomData room : level.getRooms()) {
            if (room.isPortalSpawned() && !room.getPortals().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the active portal a player is standing on, if any.
     * Checks exact Y and Y-1 to account for player feet vs block placement.
     */
    @Nullable
    public ActivePortal getActivePortalAt(@Nonnull Level level, int bx, int by, int bz) {
        for (RoomData room : level.getRooms()) {
            if (!room.isPortalSpawned()) {
                continue;
            }
            RoomData.PortalMarker portal = room.findPortalAt(bx, by, bz);
            if (portal != null) {
                return new ActivePortal(room, portal, room.getPortalSpawnedAt());
            }
        }
        return null;
    }

    /**
     * Returns true while the player is still within the portal's immediate area.
     * Used to force players to step away from a freshly spawned portal
     * before re-entering can trigger the level transition.
     */
    public boolean isPlayerNearPortal(@Nonnull Level level, @Nonnull Vector3d playerPos, double horizontalRadius) {
        double maxDistanceSq = horizontalRadius * horizontalRadius;
        for (RoomData room : level.getRooms()) {
            if (!room.isPortalSpawned()) {
                continue;
            }
            for (RoomData.PortalMarker portal : room.getPortals()) {
                Vector3i pos = portal.position();
                double centerX = pos.x + 0.5;
                double centerZ = pos.z + 0.5;
                double dx = playerPos.x - centerX;
                double dz = playerPos.z - centerZ;
                double dy = Math.abs(playerPos.y - (pos.y + 1.0));
                if (dx * dx + dz * dz <= maxDistanceSq && dy <= 2.0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Walks up the room parent chain until it finds portal exit markers.
     * If the resolved room has multiple exits, one is chosen at random.
     */
    @Nullable
    public Vector3i resolveClosestExitDestination(@Nonnull RoomData sourceRoom) {
        for (RoomData cursor = sourceRoom; cursor != null; cursor = cursor.getParent()) {
            List<Vector3i> exits = cursor.getPortalExitPositions();
            if (!exits.isEmpty()) {
                return exits.get(ThreadLocalRandom.current().nextInt(exits.size()));
            }
        }
        return null;
    }

    public record ActivePortal(@Nonnull RoomData room,
                               @Nonnull RoomData.PortalMarker portal,
                               long activatedAt) {
        public long activationToken() {
            Vector3i pos = portal.position();
            return activatedAt ^ (31L * pos.x) ^ (131L * pos.y) ^ (521L * pos.z);
        }
    }
}
