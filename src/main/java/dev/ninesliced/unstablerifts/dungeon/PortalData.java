package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Block component that stores portal configuration on a UnstableRifts_Portal block.
 * Persists through chunk save/load and prefab save/load.
 */
public class PortalData extends AbstractModeChunkData {

    @Nonnull
    public static final BuilderCodec<PortalData> CODEC = BuilderCodec.builder(PortalData.class, PortalData::new)
            .append(new KeyedCodec<>("Mode", Codec.STRING), PortalData::setMode, PortalData::getMode).add()
            .build();

    private static ComponentType<ChunkStore, PortalData> componentType;

    public PortalData() {
    }

    public PortalData(@Nullable String mode) {
        super(mode);
    }

    public static ComponentType<ChunkStore, PortalData> getComponentType() {
        return componentType;
    }

    public static void setComponentType(ComponentType<ChunkStore, PortalData> type) {
        componentType = type;
    }

    @Nonnull
    public static PortalMode parseMode(@Nullable String raw) {
        return parseMode(raw, PortalMode.class, PortalMode.NEXT_LEVEL);
    }

    @Nonnull
    public static String serializeMode(@Nonnull PortalMode mode) {
        return AbstractModeChunkData.serializeMode(mode);
    }

    @Nonnull
    public PortalMode parseMode() {
        return parseMode(this.mode);
    }

    @Override
    @Nullable
    public PortalData clone() {
        return new PortalData(this.mode);
    }
}
