package dev.ninesliced.shotcave.dungeon.map;

import dev.ninesliced.shotcave.dungeon.Level;
import dev.ninesliced.shotcave.dungeon.RoomData;
import dev.ninesliced.shotcave.dungeon.RoomType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

public final class SmoothedDungeonGrid {

    private static final int PADDING = 4;
    private static final int SMOOTH_PASSES = 3;
    private static final int FILL_THRESHOLD = 4;
    private static final int REMOVE_THRESHOLD = 1;

    private final RoomData[] grid;
    private final int originX, originZ;
    private final int width, height;

    private SmoothedDungeonGrid(RoomData[] grid, int originX, int originZ, int width, int height) {
        this.grid = grid;
        this.originX = originX;
        this.originZ = originZ;
        this.width = width;
        this.height = height;
    }

    @Nullable
    public RoomData getOwner(int wx, int wz) {
        int lx = wx - originX;
        int lz = wz - originZ;
        if (lx < 0 || lz < 0 || lx >= width || lz >= height) return null;
        return grid[lz * width + lx];
    }

    @Nonnull
    public static SmoothedDungeonGrid build(@Nonnull Level level) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (RoomData room : level.getRooms()) {
            if (!room.hasBounds() || room.getType() == RoomType.WALL) continue;
            minX = Math.min(minX, room.getBoundsMinX());
            maxX = Math.max(maxX, room.getBoundsMaxX());
            minZ = Math.min(minZ, room.getBoundsMinZ());
            maxZ = Math.max(maxZ, room.getBoundsMaxZ());
        }

        if (minX > maxX || minZ > maxZ) {
            return new SmoothedDungeonGrid(new RoomData[0], 0, 0, 0, 0);
        }

        int ox = minX - PADDING;
        int oz = minZ - PADDING;
        int w = (maxX - minX + 1) + 2 * PADDING;
        int h = (maxZ - minZ + 1) + 2 * PADDING;

        RoomData[] data = new RoomData[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                RoomData room = level.getBlockOwner(ox + x, oz + z);
                if (room != null && room.getType() != RoomType.WALL) {
                    data[z * w + x] = room;
                }
            }
        }

        for (int pass = 0; pass < SMOOTH_PASSES; pass++) {
            data = smoothPass(data, w, h);
        }

        return new SmoothedDungeonGrid(data, ox, oz, w, h);
    }

    private static RoomData[] smoothPass(RoomData[] grid, int w, int h) {
        RoomData[] next = new RoomData[w * h];
        System.arraycopy(grid, 0, next, 0, grid.length);

        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                RoomData current = grid[z * w + x];
                int roomNeighbors = 0;
                RoomData bestRoom = null;
                int bestCount = 0;
                Map<RoomData, int[]> counts = null;

                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        int nx = x + dx;
                        int nz = z + dz;
                        if (nx >= 0 && nz >= 0 && nx < w && nz < h) {
                            RoomData neighbor = grid[nz * w + nx];
                            if (neighbor != null) {
                                roomNeighbors++;
                                if (current == null) {
                                    if (counts == null) counts = new IdentityHashMap<>();
                                    int[] c = counts.computeIfAbsent(neighbor, k -> new int[1]);
                                    c[0]++;
                                    if (c[0] > bestCount) {
                                        bestCount = c[0];
                                        bestRoom = neighbor;
                                    }
                                }
                            }
                        }
                    }
                }

                if (current == null) {
                    if (roomNeighbors >= FILL_THRESHOLD && bestRoom != null) {
                        next[z * w + x] = bestRoom;
                    }
                } else if (roomNeighbors <= REMOVE_THRESHOLD) {
                    next[z * w + x] = null;
                }
            }
        }
        return next;
    }
}
