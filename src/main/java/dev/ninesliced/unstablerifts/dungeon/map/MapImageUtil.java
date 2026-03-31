package dev.ninesliced.unstablerifts.dungeon.map;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.BitFieldArr;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MapImageUtil {

    private MapImageUtil() {
    }

    @Nonnull
    public static MapImage fromRawPixels(int width, int height, @Nonnull int[] pixels) {
        int pixelCount = width * height;
        if (pixelCount <= 0 || pixels.length < pixelCount) {
            return new MapImage(width, height, null, (byte) 0, null);
        }

        Map<Integer, Integer> colorToIndex = new LinkedHashMap<>();
        for (int i = 0; i < pixelCount; i++) {
            colorToIndex.computeIfAbsent(pixels[i], ignored -> colorToIndex.size());
        }

        int[] palette = new int[colorToIndex.size()];
        for (Map.Entry<Integer, Integer> entry : colorToIndex.entrySet()) {
            palette[entry.getValue()] = entry.getKey();
        }

        int bitsPerIndex = calculateBitsRequired(Math.max(1, palette.length));
        BitFieldArr indices = new BitFieldArr(bitsPerIndex, pixelCount);
        for (int i = 0; i < pixelCount; i++) {
            indices.set(i, colorToIndex.get(pixels[i]));
        }

        return new MapImage(width, height, palette, (byte) bitsPerIndex, indices.get());
    }

    private static int calculateBitsRequired(int colorCount) {
        if (colorCount <= 16) return 4;
        if (colorCount <= 256) return 8;
        if (colorCount <= 4096) return 12;
        return 16;
    }
}
