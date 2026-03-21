package dev.ninesliced.shotcave.dungeon.map;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import dev.ninesliced.shotcave.dungeon.Level;
import dev.ninesliced.shotcave.dungeon.RoomData;
import dev.ninesliced.shotcave.dungeon.RoomType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public final class DungeonMapRenderer {

    public static final int MAP_CHUNK_SIZE = 32;
    private static final int BLEND_RADIUS = 3;

    private static final int COLOR_TRANSPARENT = 0x00000000;

    private static final int COLOR_SPAWN     = 0x4ade80FF;
    private static final int COLOR_CORRIDOR  = 0x6b7d94FF;
    private static final int COLOR_CHALLENGE = 0x60a5faFF;
    private static final int COLOR_TREASURE  = 0xfbbf24FF;
    private static final int COLOR_SHOP      = 0xa78bfaFF;
    private static final int COLOR_BOSS      = 0xef4444FF;

    private static final int COLOR_SPAWN_DIM     = 0x2d7a4dFF;
    private static final int COLOR_CORRIDOR_DIM  = 0x3d4754FF;
    private static final int COLOR_CHALLENGE_DIM = 0x3a6399FF;
    private static final int COLOR_TREASURE_DIM  = 0x977316FF;
    private static final int COLOR_SHOP_DIM      = 0x655499FF;
    private static final int COLOR_BOSS_DIM      = 0x8f2929FF;

    private DungeonMapRenderer() {
    }

    private static int roomColor(@Nonnull RoomData room) {
        boolean dim = room.isCleared();
        return switch (room.getType()) {
            case SPAWN     -> dim ? COLOR_SPAWN_DIM     : COLOR_SPAWN;
            case CORRIDOR  -> dim ? COLOR_CORRIDOR_DIM  : COLOR_CORRIDOR;
            case CHALLENGE -> dim ? COLOR_CHALLENGE_DIM : COLOR_CHALLENGE;
            case TREASURE  -> dim ? COLOR_TREASURE_DIM  : COLOR_TREASURE;
            case SHOP      -> dim ? COLOR_SHOP_DIM      : COLOR_SHOP;
            case BOSS      -> dim ? COLOR_BOSS_DIM      : COLOR_BOSS;
            case WALL      -> COLOR_TRANSPARENT;
        };
    }

    @Nullable
    public static MapImage renderChunk(int chunkX, int chunkZ, @Nonnull SmoothedDungeonGrid grid) {
        int[] pixels = new int[MAP_CHUNK_SIZE * MAP_CHUNK_SIZE];
        int baseX = chunkX * MAP_CHUNK_SIZE;
        int baseZ = chunkZ * MAP_CHUNK_SIZE;
        boolean hasContent = false;

        for (int pz = 0; pz < MAP_CHUNK_SIZE; pz++) {
            for (int px = 0; px < MAP_CHUNK_SIZE; px++) {
                int color = blendNeighborhood(grid, baseX + px, baseZ + pz);
                pixels[pz * MAP_CHUNK_SIZE + px] = color;
                if ((color & 0xFF) != 0) hasContent = true;
            }
        }

        return hasContent ? MapImageUtil.fromRawPixels(MAP_CHUNK_SIZE, MAP_CHUNK_SIZE, pixels) : null;
    }

    private static int blendNeighborhood(@Nonnull SmoothedDungeonGrid grid, int wx, int wz) {
        RoomData center = grid.getOwner(wx, wz);
        if (center != null && center.getType() != RoomType.WALL
                && grid.getOwner(wx - 1, wz) == center
                && grid.getOwner(wx + 1, wz) == center
                && grid.getOwner(wx, wz - 1) == center
                && grid.getOwner(wx, wz + 1) == center) {
            return roomColor(center);
        }

        int roomWeightSum = 0;
        int totalWeightSum = 0;
        int sumR = 0, sumG = 0, sumB = 0;

        for (int dz = -BLEND_RADIUS; dz <= BLEND_RADIUS; dz++) {
            for (int dx = -BLEND_RADIUS; dx <= BLEND_RADIUS; dx++) {
                int weight = BLEND_RADIUS + 1 - Math.max(Math.abs(dx), Math.abs(dz));
                if (weight <= 0) continue;

                totalWeightSum += weight;

                RoomData room = grid.getOwner(wx + dx, wz + dz);
                if (room != null && room.getType() != RoomType.WALL) {
                    int c = roomColor(room);
                    sumR += ((c >> 24) & 0xFF) * weight;
                    sumG += ((c >> 16) & 0xFF) * weight;
                    sumB += ((c >> 8) & 0xFF) * weight;
                    roomWeightSum += weight;
                }
            }
        }

        if (roomWeightSum == 0) return COLOR_TRANSPARENT;

        int r = sumR / roomWeightSum;
        int g = sumG / roomWeightSum;
        int b = sumB / roomWeightSum;
        int a = 255 * roomWeightSum / totalWeightSum;

        return (r << 24) | (g << 16) | (b << 8) | a;
    }

    @Nonnull
    public static Map<Long, MapImage> renderAll(@Nonnull Level level, @Nonnull SmoothedDungeonGrid grid) {
        int minCX = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE;
        int minCZ = Integer.MAX_VALUE, maxCZ = Integer.MIN_VALUE;

        for (RoomData room : level.getRooms()) {
            if (!room.hasBounds() || room.getType() == RoomType.WALL) continue;
            minCX = Math.min(minCX, room.getBoundsMinX() >> 5);
            maxCX = Math.max(maxCX, room.getBoundsMaxX() >> 5);
            minCZ = Math.min(minCZ, room.getBoundsMinZ() >> 5);
            maxCZ = Math.max(maxCZ, room.getBoundsMaxZ() >> 5);
        }

        Map<Long, MapImage> chunks = new HashMap<>();
        if (minCX > maxCX || minCZ > maxCZ) return chunks;

        for (int cz = minCZ; cz <= maxCZ; cz++) {
            for (int cx = minCX; cx <= maxCX; cx++) {
                MapImage image = renderChunk(cx, cz, grid);
                if (image != null) chunks.put(packChunk(cx, cz), image);
            }
        }

        return chunks;
    }

    @Nonnull
    public static Map<Long, MapImage> renderChunksForRoom(@Nonnull SmoothedDungeonGrid grid, @Nonnull RoomData room) {
        Map<Long, MapImage> updated = new HashMap<>();
        if (!room.hasBounds()) return updated;

        int minCX = (room.getBoundsMinX() >> 5) - 1;
        int maxCX = (room.getBoundsMaxX() >> 5) + 1;
        int minCZ = (room.getBoundsMinZ() >> 5) - 1;
        int maxCZ = (room.getBoundsMaxZ() >> 5) + 1;

        for (int cz = minCZ; cz <= maxCZ; cz++) {
            for (int cx = minCX; cx <= maxCX; cx++) {
                MapImage image = renderChunk(cx, cz, grid);
                if (image != null) updated.put(packChunk(cx, cz), image);
            }
        }

        return updated;
    }

    public static long packChunk(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int unpackChunkX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackChunkZ(long packed) {
        return (int) packed;
    }
}
