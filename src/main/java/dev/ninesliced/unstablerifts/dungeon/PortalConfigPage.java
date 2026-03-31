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
 * Portal configuration UI page for level designers.
 * Persists the selected PortalMode through PortalData on UnstableRifts_Portal.
 */
public final class PortalConfigPage extends AbstractModeConfigPage<PortalMode> {

    private static final String LAYOUT_PATH = "Pages/UnstableRifts/PortalConfig.ui";
    private static final String PORTAL_BLOCK_ID = MarkerType.PORTAL.getBlockName();

    public PortalConfigPage(@Nonnull PlayerRef playerRef, @Nullable BlockPosition blockPos) {
        super(playerRef, blockPos);
    }

    @Override
    @Nonnull
    protected String getLayoutPath() {
        return LAYOUT_PATH;
    }

    @Override
    protected void bindModeButtons(@Nonnull UIEventBuilder events) {
        bindModeButton(events, "#NextLevelPortalBtn", PortalMode.NEXT_LEVEL);
        bindModeButton(events, "#ClosestExitPortalBtn", PortalMode.CLOSEST_EXIT);
    }

    @Override
    @Nonnull
    protected PortalMode defaultMode() {
        return PortalMode.NEXT_LEVEL;
    }

    @Override
    @Nonnull
    protected PortalMode parseMode(@Nullable String rawMode) {
        return PortalData.parseMode(rawMode);
    }

    @Override
    @Nullable
    protected PortalMode readStoredMode(@Nonnull Holder<ChunkStore> holder) {
        PortalData data = holder.getComponent(PortalData.getComponentType());
        return data != null ? data.parseMode() : null;
    }

    @Override
    @Nonnull
    protected PortalMode readLegacyMode(@Nullable BlockType blockType) {
        return PortalMode.NEXT_LEVEL;
    }

    @Override
    @Nonnull
    protected String getMarkerBlockId() {
        return PORTAL_BLOCK_ID;
    }

    @Override
    protected void storeMode(@Nonnull Holder<ChunkStore> holder, @Nonnull PortalMode mode) {
        holder.putComponent(PortalData.getComponentType(), new PortalData(PortalData.serializeMode(mode)));
    }

    @Override
    @Nonnull
    protected String getNotificationMessage(@Nonnull PortalMode mode) {
        return "Portal mode set to: " + mode.name();
    }

    @Override
    @Nonnull
    protected String getNotificationKey() {
        return "portal_config";
    }
}
