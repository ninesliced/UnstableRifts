package dev.ninesliced.unstablerifts.dungeon;

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
 * Shared base for simple marker-config pages that persist a single enum-like mode
 * into a chunk block component.
 */
public abstract class AbstractModeConfigPage<M extends Enum<M>>
        extends InteractiveCustomUIPage<AbstractModeConfigPage.ModeEventData> {

    @Nullable
    private final BlockPosition blockPos;

    protected AbstractModeConfigPage(@Nonnull PlayerRef playerRef, @Nullable BlockPosition blockPos) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ModeEventData.CODEC);
        this.blockPos = blockPos;
    }

    @Override
    public final void build(@Nonnull Ref<EntityStore> ref,
                            @Nonnull UICommandBuilder ui,
                            @Nonnull UIEventBuilder events,
                            @Nonnull Store<EntityStore> store) {
        ui.append(getLayoutPath());
        ui.set("#CurrentModeLabel.Text", "Current mode: " + loadFromBlockEntity(store).name());
        bindModeButtons(events);
    }

    @Override
    public final void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                      @Nonnull Store<EntityStore> store,
                                      @Nonnull ModeEventData data) {
        M mode = parseMode(data.mode);
        saveToBlockEntity(store, mode);

        try {
            NotificationUtil.sendNotification(
                    this.playerRef.getPacketHandler(),
                    Message.raw(getNotificationMessage(mode)),
                    null,
                    getNotificationKey());
        } catch (Exception ignored) {
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    protected final void bindModeButton(@Nonnull UIEventBuilder events,
                                        @Nonnull String selector,
                                        @Nonnull M mode) {
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector,
                new EventData().put(ModeEventData.KEY_MODE, mode.name()),
                false
        );
    }

    @Nonnull
    private M loadFromBlockEntity(@Nonnull Store<EntityStore> store) {
        WorldChunk chunk = getChunk(store);
        if (chunk == null || blockPos == null) {
            return defaultMode();
        }

        Holder<ChunkStore> holder = chunk.getBlockComponentHolder(blockPos.x, blockPos.y, blockPos.z);
        if (holder != null) {
            M storedMode = readStoredMode(holder);
            if (storedMode != null) {
                return storedMode;
            }
        }

        return readLegacyMode(chunk.getBlockType(blockPos.x, blockPos.y, blockPos.z));
    }

    private void saveToBlockEntity(@Nonnull Store<EntityStore> store, @Nonnull M mode) {
        WorldChunk chunk = getChunk(store);
        if (chunk == null || blockPos == null) {
            return;
        }

        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        int rotation = chunk.getRotationIndex(blockPos.x, blockPos.y, blockPos.z);
        BlockType blockType = chunk.getBlockType(blockPos.x, blockPos.y, blockPos.z);
        if (blockType == null || !getMarkerBlockId().equals(blockType.getId())) {
            world.setBlock(blockPos.x, blockPos.y, blockPos.z, getMarkerBlockId(), rotation);
            blockType = chunk.getBlockType(blockPos.x, blockPos.y, blockPos.z);
        }
        if (blockType == null) {
            return;
        }

        Holder<ChunkStore> holder = ChunkStore.REGISTRY.newHolder();
        storeMode(holder, mode);
        chunk.setState(blockPos.x, blockPos.y, blockPos.z, blockType, rotation, holder);
    }

    @Nullable
    private WorldChunk getChunk(@Nonnull Store<EntityStore> store) {
        if (blockPos == null) {
            return null;
        }

        World world = store.getExternalData().getWorld();
        if (world == null) {
            return null;
        }

        return world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(blockPos.x, blockPos.z));
    }

    @Nonnull
    protected abstract String getLayoutPath();

    protected abstract void bindModeButtons(@Nonnull UIEventBuilder events);

    @Nonnull
    protected abstract M defaultMode();

    @Nonnull
    protected abstract M parseMode(@Nullable String rawMode);

    @Nullable
    protected abstract M readStoredMode(@Nonnull Holder<ChunkStore> holder);

    @Nonnull
    protected abstract M readLegacyMode(@Nullable BlockType blockType);

    @Nonnull
    protected abstract String getMarkerBlockId();

    protected abstract void storeMode(@Nonnull Holder<ChunkStore> holder, @Nonnull M mode);

    @Nonnull
    protected abstract String getNotificationMessage(@Nonnull M mode);

    @Nonnull
    protected abstract String getNotificationKey();

    public static final class ModeEventData {
        static final String KEY_MODE = "Mode";
        static final BuilderCodec<ModeEventData> CODEC = BuilderCodec.builder(ModeEventData.class, ModeEventData::new)
                .append(new KeyedCodec<>(KEY_MODE, Codec.STRING), (d, v) -> d.mode = v, d -> d.mode).add()
                .build();

        @Nullable
        private String mode;
    }
}
