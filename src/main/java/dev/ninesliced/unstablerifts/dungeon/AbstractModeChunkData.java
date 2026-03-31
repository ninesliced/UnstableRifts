package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Shared base for chunk-backed marker components that store a single enum mode string.
 */
public abstract class AbstractModeChunkData implements Component<ChunkStore> {

    @Nullable
    protected String mode;

    protected AbstractModeChunkData() {
    }

    protected AbstractModeChunkData(@Nullable String mode) {
        this.mode = mode;
    }

    @Nonnull
    protected static <T extends Enum<T>> T parseMode(@Nullable String raw,
                                                     @Nonnull Class<T> enumType,
                                                     @Nonnull T defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return defaultValue;
        }
    }

    @Nonnull
    protected static <T extends Enum<T>> String serializeMode(@Nonnull T mode) {
        return mode.name();
    }

    @Nullable
    public String getMode() {
        return mode;
    }

    protected void setMode(@Nullable String mode) {
        this.mode = mode;
    }

    @Override
    @Nonnull
    public abstract AbstractModeChunkData clone();
}
