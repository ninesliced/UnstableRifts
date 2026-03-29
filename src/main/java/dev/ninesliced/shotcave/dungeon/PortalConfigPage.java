package dev.ninesliced.shotcave.dungeon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Portal configuration UI page for level designers.
 * Persists the selected PortalMode through PortalData on Shotcave_Portal.
 */
public final class PortalConfigPage extends InteractiveCustomUIPage<PortalConfigPage.PortalEventData> {

    private static final String LAYOUT_PATH = "Pages/Shotcave/PortalConfig.ui";
    private static final String PORTAL_BLOCK_ID = MarkerType.PORTAL.getBlockName();

    @Nullable
    private final BlockPosition blockPos;

    public PortalConfigPage(@Nonnull PlayerRef playerRef, @Nullable BlockPosition blockPos) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PortalEventData.CODEC);
        this.blockPos = blockPos;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        ui.append(LAYOUT_PATH);

        PortalMode currentMode = loadFromBlockEntity(store);
        ui.set("#CurrentModeLabel.Text", "Current mode: " + currentMode.name());

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#NextLevelPortalBtn",
                new EventData().put(PortalEventData.KEY_MODE, PortalMode.NEXT_LEVEL.name()),
                false
        );
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#ClosestExitPortalBtn",
                new EventData().put(PortalEventData.KEY_MODE, PortalMode.CLOSEST_EXIT.name()),
                false
        );
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull PortalEventData data) {
        PortalMode mode = PortalData.parseMode(data.mode);
        saveToBlockEntity(store, mode);

        try {
            NotificationUtil.sendNotification(
                    this.playerRef.getPacketHandler(),
                    Message.raw("Portal mode set to: " + mode.name()),
                    null,
                    "portal_config");
        } catch (Exception e) {
            // Best-effort
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    @Nonnull
    private PortalMode loadFromBlockEntity(@Nonnull Store<EntityStore> store) {
        if (blockPos == null) {
            return PortalMode.NEXT_LEVEL;
        }

        World world = store.getExternalData().getWorld();
        if (world == null) {
            return PortalMode.NEXT_LEVEL;
        }

        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockPos.x, blockPos.z);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
        if (chunk == null) {
            return PortalMode.NEXT_LEVEL;
        }

        Holder<ChunkStore> holder = chunk.getBlockComponentHolder(blockPos.x, blockPos.y, blockPos.z);
        if (holder != null) {
            PortalData data = holder.getComponent(PortalData.getComponentType());
            if (data != null) {
                return data.parseMode();
            }
        }

        BlockType blockType = chunk.getBlockType(blockPos.x, blockPos.y, blockPos.z);
        return blockType != null && PORTAL_BLOCK_ID.equals(blockType.getId())
                ? PortalMode.NEXT_LEVEL
                : PortalMode.NEXT_LEVEL;
    }

    private void saveToBlockEntity(@Nonnull Store<EntityStore> store, @Nonnull PortalMode mode) {
        if (blockPos == null) {
            return;
        }

        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockPos.x, blockPos.z);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
        if (chunk == null) {
            return;
        }

        int rotation = chunk.getRotationIndex(blockPos.x, blockPos.y, blockPos.z);
        BlockType blockType = chunk.getBlockType(blockPos.x, blockPos.y, blockPos.z);
        if (blockType == null || !PORTAL_BLOCK_ID.equals(blockType.getId())) {
            world.setBlock(blockPos.x, blockPos.y, blockPos.z, PORTAL_BLOCK_ID, rotation);
            blockType = chunk.getBlockType(blockPos.x, blockPos.y, blockPos.z);
        }
        if (blockType == null) {
            return;
        }

        PortalData portalData = new PortalData(PortalData.serializeMode(mode));
        Holder<ChunkStore> holder = ChunkStore.REGISTRY.newHolder();
        holder.putComponent(PortalData.getComponentType(), portalData);
        chunk.setState(blockPos.x, blockPos.y, blockPos.z, blockType, rotation, holder);
    }

    public static final class PortalEventData {
        static final String KEY_MODE = "Mode";
        static final BuilderCodec<PortalEventData> CODEC = BuilderCodec.<PortalEventData>builder(PortalEventData.class, PortalEventData::new)
                .append(new KeyedCodec<>(KEY_MODE, Codec.STRING), (d, v) -> d.mode = v, d -> d.mode).add()
                .build();

        @Nullable
        private String mode;
    }
}
