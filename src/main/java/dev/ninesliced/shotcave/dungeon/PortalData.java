package dev.ninesliced.shotcave.dungeon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Block component that stores portal configuration on a Shotcave_Portal block.
 * Persists through chunk save/load and prefab save/load.
 */
public class PortalData implements Component<ChunkStore> {

    @Nonnull
    public static final BuilderCodec<PortalData> CODEC = BuilderCodec.<PortalData>builder(PortalData.class, PortalData::new)
            .append(new KeyedCodec<>("Mode", Codec.STRING), (d, v) -> d.mode = v, d -> d.mode).add()
            .build();

    private static ComponentType<ChunkStore, PortalData> componentType;

    @Nullable
    private String mode;

    public PortalData() {
    }

    public PortalData(@Nullable String mode) {
        this.mode = mode;
    }

    public static ComponentType<ChunkStore, PortalData> getComponentType() {
        return componentType;
    }

    public static void setComponentType(ComponentType<ChunkStore, PortalData> type) {
        componentType = type;
    }

    @Nullable
    public String getMode() {
        return mode;
    }

    @Nonnull
    public PortalMode parseMode() {
        return parseMode(this.mode);
    }

    @Nonnull
    public static PortalMode parseMode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return PortalMode.NEXT_LEVEL;
        }

        try {
            return PortalMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PortalMode.NEXT_LEVEL;
        }
    }

    @Nonnull
    public static String serializeMode(@Nonnull PortalMode mode) {
        return mode.name();
    }

    @Override
    @Nullable
    public Component<ChunkStore> clone() {
        return new PortalData(this.mode);
    }
}
