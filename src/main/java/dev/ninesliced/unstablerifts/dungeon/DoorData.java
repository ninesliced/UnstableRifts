package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Block component that stores door configuration on a UnstableRifts_Door block.
 * Persists through chunk save/load and prefab save/load.
 */
public class DoorData extends AbstractModeChunkData {

    @Nonnull
    public static final BuilderCodec<DoorData> CODEC = BuilderCodec.builder(DoorData.class, DoorData::new)
            .append(new KeyedCodec<>("Mode", Codec.STRING), DoorData::setMode, DoorData::getMode).add()
            .build();

    private static ComponentType<ChunkStore, DoorData> componentType;

    @Nullable
    public DoorData() {
    }

    public DoorData(@Nullable String mode) {
        super(mode);
    }

    public static ComponentType<ChunkStore, DoorData> getComponentType() {
        return componentType;
    }

    public static void setComponentType(ComponentType<ChunkStore, DoorData> type) {
        componentType = type;
    }

    @Nonnull
    public static DoorMode parseMode(@Nullable String raw) {
        return parseMode(raw, DoorMode.class, DoorMode.ACTIVATOR);
    }

    @Nonnull
    public static String serializeMode(@Nonnull DoorMode mode) {
        return AbstractModeChunkData.serializeMode(mode);
    }

    @Nonnull
    public DoorMode parseMode() {
        return parseMode(this.mode);
    }

    @Override
    @Nullable
    public DoorData clone() {
        return new DoorData(this.mode);
    }
}
