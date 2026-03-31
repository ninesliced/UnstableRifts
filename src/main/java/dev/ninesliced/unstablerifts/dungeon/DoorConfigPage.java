package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Door configuration UI page for level designers.
 * Shows the current door mode and persists it through DoorData on UnstableRifts_Door.
 */
public final class DoorConfigPage extends AbstractModeConfigPage<DoorMode> {

    private static final String LAYOUT_PATH = "Pages/UnstableRifts/DoorConfig.ui";
    private static final String DOOR_BLOCK_ID = MarkerType.DOOR.getBlockName();

    public DoorConfigPage(@Nonnull PlayerRef playerRef, @Nullable BlockPosition blockPos) {
        super(playerRef, blockPos);
    }

    @Nonnull
    private static DoorMode parseLegacyBlockMode(@Nullable String blockId) {
        if ("UnstableRifts_Door_Key".equals(blockId)) {
            return DoorMode.KEY;
        }
        if ("UnstableRifts_Door_Activator".equals(blockId)) {
            return DoorMode.ACTIVATOR;
        }
        return DoorMode.ACTIVATOR;
    }

    @Override
    @Nonnull
    protected String getLayoutPath() {
        return LAYOUT_PATH;
    }

    @Override
    protected void bindModeButtons(@Nonnull UIEventBuilder events) {
        bindModeButton(events, "#KeyDoorBtn", DoorMode.KEY);
        bindModeButton(events, "#ActivatorDoorBtn", DoorMode.ACTIVATOR);
    }

    @Override
    @Nonnull
    protected DoorMode defaultMode() {
        return DoorMode.ACTIVATOR;
    }

    @Override
    @Nonnull
    protected DoorMode parseMode(@Nullable String rawMode) {
        return DoorData.parseMode(rawMode);
    }

    @Override
    @Nullable
    protected DoorMode readStoredMode(@Nonnull Holder<ChunkStore> holder) {
        DoorData data = holder.getComponent(DoorData.getComponentType());
        return data != null ? data.parseMode() : null;
    }

    @Override
    @Nonnull
    protected DoorMode readLegacyMode(@Nullable BlockType blockType) {
        return blockType != null ? parseLegacyBlockMode(blockType.getId()) : DoorMode.ACTIVATOR;
    }

    @Override
    @Nonnull
    protected String getMarkerBlockId() {
        return DOOR_BLOCK_ID;
    }

    @Override
    protected void storeMode(@Nonnull Holder<ChunkStore> holder, @Nonnull DoorMode mode) {
        holder.putComponent(DoorData.getComponentType(), new DoorData(DoorData.serializeMode(mode)));
    }

    @Override
    @Nonnull
    protected String getNotificationMessage(@Nonnull DoorMode mode) {
        return "Door mode set to: " + mode.name();
    }

    @Override
    @Nonnull
    protected String getNotificationKey() {
        return "door_config";
    }
}
