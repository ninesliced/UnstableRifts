package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Block component that stores room configuration on a UnstableRifts_Room_Config block.
 * Persists through chunk save/load and prefab save/load.
 */
public class RoomConfigData implements Component<ChunkStore> {

    @Nonnull
    public static final BuilderCodec<RoomConfigData> CODEC = BuilderCodec.builder(RoomConfigData.class, RoomConfigData::new)
            .append(new KeyedCodec<>("LockRoom", Codec.STRING), (d, v) -> d.lockRoom = v, d -> d.lockRoom).add()
            .append(new KeyedCodec<>("MobClearActivator", Codec.STRING), (d, v) -> d.mobClearActivator = v, d -> d.mobClearActivator).add()
            .append(new KeyedCodec<>("MobClearUnlockPercent", Codec.STRING), (d, v) -> d.mobClearUnlockPercent = v, d -> d.mobClearUnlockPercent).add()
            .append(new KeyedCodec<>("EnterTitle", Codec.STRING), (d, v) -> d.enterTitle = v, d -> d.enterTitle).add()
            .append(new KeyedCodec<>("EnterSubtitle", Codec.STRING), (d, v) -> d.enterSubtitle = v, d -> d.enterSubtitle).add()
            .append(new KeyedCodec<>("UnlockTitle", Codec.STRING), (d, v) -> d.unlockTitle = v, d -> d.unlockTitle).add()
            .append(new KeyedCodec<>("UnlockSubtitle", Codec.STRING), (d, v) -> d.unlockSubtitle = v, d -> d.unlockSubtitle).add()
            .append(new KeyedCodec<>("ExitTitle", Codec.STRING), (d, v) -> d.exitTitle = v, d -> d.exitTitle).add()
            .append(new KeyedCodec<>("ExitSubtitle", Codec.STRING), (d, v) -> d.exitSubtitle = v, d -> d.exitSubtitle).add()
            .build();

    private static ComponentType<ChunkStore, RoomConfigData> componentType;

    @Nullable private String lockRoom;
    @Nullable private String mobClearActivator;
    @Nullable private String mobClearUnlockPercent;
    @Nullable private String enterTitle;
    @Nullable private String enterSubtitle;
    @Nullable private String unlockTitle;
    @Nullable private String unlockSubtitle;
    @Nullable private String exitTitle;
    @Nullable private String exitSubtitle;

    public RoomConfigData() {
    }

    public RoomConfigData(@Nullable String lockRoom,
                          @Nullable String mobClearActivator,
                          @Nullable String mobClearUnlockPercent,
                          @Nullable String enterTitle,
                          @Nullable String enterSubtitle,
                          @Nullable String unlockTitle,
                          @Nullable String unlockSubtitle,
                          @Nullable String exitTitle,
                          @Nullable String exitSubtitle) {
        this.lockRoom = lockRoom;
        this.mobClearActivator = mobClearActivator;
        this.mobClearUnlockPercent = mobClearUnlockPercent;
        this.enterTitle = enterTitle;
        this.enterSubtitle = enterSubtitle;
        this.unlockTitle = unlockTitle;
        this.unlockSubtitle = unlockSubtitle;
        this.exitTitle = exitTitle;
        this.exitSubtitle = exitSubtitle;
    }

    public static ComponentType<ChunkStore, RoomConfigData> getComponentType() {
        return componentType;
    }

    public static void setComponentType(ComponentType<ChunkStore, RoomConfigData> type) {
        componentType = type;
    }

    public boolean isLockRoom() {
        return "true".equalsIgnoreCase(lockRoom);
    }

    public boolean hasMobClearUnlockPercentConfigured() {
        return isConfigured(mobClearUnlockPercent) || isConfigured(mobClearActivator);
    }

    public int getMobClearUnlockPercent() {
        if (isConfigured(mobClearUnlockPercent)) {
            return clampPercent(parseIntSafe(mobClearUnlockPercent, 0));
        }
        if (isConfigured(mobClearActivator)) {
            return "true".equalsIgnoreCase(mobClearActivator) ? 100 : 0;
        }
        return 0;
    }

    @Deprecated
    public boolean isMobClearActivator() {
        return getMobClearUnlockPercent() > 0;
    }

    @Nonnull
    public String getEnterTitle() {
        return enterTitle != null ? enterTitle : "";
    }

    @Nonnull
    public String getEnterSubtitle() {
        return enterSubtitle != null ? enterSubtitle : "";
    }

    @Nonnull
    public String getUnlockTitle() {
        return unlockTitle != null ? unlockTitle : "";
    }

    @Nonnull
    public String getUnlockSubtitle() {
        return unlockSubtitle != null ? unlockSubtitle : "";
    }

    @Nonnull
    public String getExitTitle() {
        return exitTitle != null ? exitTitle : "";
    }

    @Nonnull
    public String getExitSubtitle() {
        return exitSubtitle != null ? exitSubtitle : "";
    }

    @Override
    @Nullable
    public Component<ChunkStore> clone() {
        return new RoomConfigData(this.lockRoom, this.mobClearActivator, this.mobClearUnlockPercent,
                this.enterTitle, this.enterSubtitle,
                this.unlockTitle, this.unlockSubtitle,
                this.exitTitle, this.exitSubtitle);
    }

    private static boolean isConfigured(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static int parseIntSafe(@Nullable String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
