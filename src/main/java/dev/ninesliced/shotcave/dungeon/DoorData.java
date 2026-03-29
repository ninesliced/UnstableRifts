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
 * Block component that stores door configuration on a Shotcave_Door block.
 * Persists through chunk save/load and prefab save/load.
 */
public class DoorData implements Component<ChunkStore> {

    @Nonnull
    public static final BuilderCodec<DoorData> CODEC = BuilderCodec.<DoorData>builder(DoorData.class, DoorData::new)
            .append(new KeyedCodec<>("Mode", Codec.STRING), (d, v) -> d.mode = v, d -> d.mode).add()
            .build();

    private static ComponentType<ChunkStore, DoorData> componentType;

    @Nullable
    private String mode;

    public DoorData() {
    }

    public DoorData(@Nullable String mode) {
        this.mode = mode;
    }

    public static ComponentType<ChunkStore, DoorData> getComponentType() {
        return componentType;
    }

    public static void setComponentType(ComponentType<ChunkStore, DoorData> type) {
        componentType = type;
    }

    @Nullable
    public String getMode() {
        return mode;
    }

    @Nonnull
    public DoorMode parseMode() {
        return parseMode(this.mode);
    }

    @Nonnull
    public static DoorMode parseMode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return DoorMode.ACTIVATOR;
        }

        try {
            return DoorMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DoorMode.ACTIVATOR;
        }
    }

    @Nonnull
    public static String serializeMode(@Nonnull DoorMode mode) {
        return mode.name();
    }

    @Override
    @Nullable
    public Component<ChunkStore> clone() {
        return new DoorData(this.mode);
    }
}
