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
 * Room configuration UI page for level designers.
 * Allows configuring: lock on enter, mob-clear unlock percentage,
 * and three configurable messages (enter, unlock, exit) each with title + subtitle.
 */
public final class RoomConfigPage extends InteractiveCustomUIPage<RoomConfigPage.RoomConfigEventData> {

    private static final String LAYOUT_PATH = "Pages/UnstableRifts/RoomConfig.ui";

    @Nullable
    private final BlockPosition blockPos;
    private boolean lockRoom = false;
    private String mobClearUnlockPercent = "0";
    private String enterTitle = "";
    private String enterSubtitle = "";
    private String unlockTitle = "";
    private String unlockSubtitle = "";
    private String exitTitle = "";
    private String exitSubtitle = "";
    private boolean initialized = false;

    public RoomConfigPage(@Nonnull PlayerRef playerRef, @Nullable BlockPosition blockPos) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, RoomConfigEventData.CODEC);
        this.blockPos = blockPos;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        ui.append(LAYOUT_PATH);

        if (!initialized) {
            loadFromBlock(store);
            initialized = true;
        }

        applyUIState(ui);
        bindEvents(events);
    }

    private void applyUIState(@Nonnull UICommandBuilder ui) {
        ui.set("#LockRoomToggle.Text", lockRoom ? "ON" : "OFF");
        ui.set("#MobClearPercentInput.Value", mobClearUnlockPercent);
        ui.set("#EnterTitleInput.Value", enterTitle);
        ui.set("#EnterSubtitleInput.Value", enterSubtitle);
        ui.set("#UnlockTitleInput.Value", unlockTitle);
        ui.set("#UnlockSubtitleInput.Value", unlockSubtitle);
        ui.set("#ExitTitleInput.Value", exitTitle);
        ui.set("#ExitSubtitleInput.Value", exitSubtitle);
    }

    private void bindEvents(@Nonnull UIEventBuilder events) {
        events.addEventBinding(
                CustomUIEventBindingType.Activating, "#LockRoomToggleBtn",
                new EventData().put(RoomConfigEventData.KEY_ACTION, "TOGGLE_LOCK"), false);
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged, "#MobClearPercentInput",
                new EventData()
                        .put(RoomConfigEventData.KEY_ACTION, "MOB_CLEAR_PERCENT")
                        .put(RoomConfigEventData.KEY_VALUE, "#MobClearPercentInput.Value"), false);

        // Enter message
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged, "#EnterTitleInput",
                new EventData()
                        .put(RoomConfigEventData.KEY_ACTION, "ENTER_TITLE")
                        .put(RoomConfigEventData.KEY_VALUE, "#EnterTitleInput.Value"), false);
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged, "#EnterSubtitleInput",
                new EventData()
                        .put(RoomConfigEventData.KEY_ACTION, "ENTER_SUBTITLE")
                        .put(RoomConfigEventData.KEY_VALUE, "#EnterSubtitleInput.Value"), false);

        // Unlock message
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged, "#UnlockTitleInput",
                new EventData()
                        .put(RoomConfigEventData.KEY_ACTION, "UNLOCK_TITLE")
                        .put(RoomConfigEventData.KEY_VALUE, "#UnlockTitleInput.Value"), false);
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged, "#UnlockSubtitleInput",
                new EventData()
                        .put(RoomConfigEventData.KEY_ACTION, "UNLOCK_SUBTITLE")
                        .put(RoomConfigEventData.KEY_VALUE, "#UnlockSubtitleInput.Value"), false);

        // Exit message
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged, "#ExitTitleInput",
                new EventData()
                        .put(RoomConfigEventData.KEY_ACTION, "EXIT_TITLE")
                        .put(RoomConfigEventData.KEY_VALUE, "#ExitTitleInput.Value"), false);
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged, "#ExitSubtitleInput",
                new EventData()
                        .put(RoomConfigEventData.KEY_ACTION, "EXIT_SUBTITLE")
                        .put(RoomConfigEventData.KEY_VALUE, "#ExitSubtitleInput.Value"), false);

        // Save / Close
        events.addEventBinding(
                CustomUIEventBindingType.Activating, "#SaveBtn",
                new EventData().put(RoomConfigEventData.KEY_ACTION, "SAVE"), false);
        events.addEventBinding(
                CustomUIEventBindingType.Activating, "#CloseBtn",
                new EventData().put(RoomConfigEventData.KEY_ACTION, "CLOSE"), false);
    }

    private void loadFromBlock(@Nonnull Store<EntityStore> store) {
        if (blockPos == null) return;
        World world = store.getExternalData().getWorld();
        if (world == null) return;

        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockPos.x, blockPos.z);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
        if (chunk == null) return;

        Holder<ChunkStore> holder = chunk.getBlockComponentHolder(blockPos.x, blockPos.y, blockPos.z);
        if (holder == null) return;

        RoomConfigData data = holder.getComponent(RoomConfigData.getComponentType());
        if (data == null) return;

        lockRoom = data.isLockRoom();
        mobClearUnlockPercent = String.valueOf(data.getMobClearUnlockPercent());
        enterTitle = data.getEnterTitle();
        enterSubtitle = data.getEnterSubtitle();
        unlockTitle = data.getUnlockTitle();
        unlockSubtitle = data.getUnlockSubtitle();
        exitTitle = data.getExitTitle();
        exitSubtitle = data.getExitSubtitle();
    }

    private void saveToBlock(@Nonnull Store<EntityStore> store) {
        if (blockPos == null) return;
        World world = store.getExternalData().getWorld();
        if (world == null) return;

        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockPos.x, blockPos.z);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
        if (chunk == null) return;

        BlockType blockType = chunk.getBlockType(blockPos.x, blockPos.y, blockPos.z);
        if (blockType == null) return;
        int rotation = chunk.getRotationIndex(blockPos.x, blockPos.y, blockPos.z);

        mobClearUnlockPercent = normalizeMobClearUnlockPercent(mobClearUnlockPercent);
        RoomConfigData data = new RoomConfigData(
                String.valueOf(lockRoom),
                null,
                mobClearUnlockPercent,
                enterTitle, enterSubtitle,
                unlockTitle, unlockSubtitle,
                exitTitle, exitSubtitle
        );

        Holder<ChunkStore> holder = ChunkStore.REGISTRY.newHolder();
        holder.putComponent(RoomConfigData.getComponentType(), data);
        chunk.setState(blockPos.x, blockPos.y, blockPos.z, blockType, rotation, holder);
    }

    private void refresh() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        applyUIState(ui);
        bindEvents(events);
        sendUpdate(ui, events, false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull RoomConfigEventData data) {
        String action = data.action;
        if (action == null) return;

        switch (action) {
            case "TOGGLE_LOCK" -> { lockRoom = !lockRoom; refresh(); }
            case "MOB_CLEAR_PERCENT" -> { if (data.value != null) mobClearUnlockPercent = data.value; }
            case "ENTER_TITLE" -> { if (data.value != null) enterTitle = data.value; }
            case "ENTER_SUBTITLE" -> { if (data.value != null) enterSubtitle = data.value; }
            case "UNLOCK_TITLE" -> { if (data.value != null) unlockTitle = data.value; }
            case "UNLOCK_SUBTITLE" -> { if (data.value != null) unlockSubtitle = data.value; }
            case "EXIT_TITLE" -> { if (data.value != null) exitTitle = data.value; }
            case "EXIT_SUBTITLE" -> { if (data.value != null) exitSubtitle = data.value; }
            case "SAVE" -> {
                saveToBlock(store);
                try {
                    NotificationUtil.sendNotification(
                            this.playerRef.getPacketHandler(),
                            Message.raw("Room config saved."),
                            null,
                            "room_config");
                } catch (Exception ignored) {
                }
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
            }
            case "CLOSE" -> {
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
            }
        }
    }

    @Nonnull
    private static String normalizeMobClearUnlockPercent(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "0";
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            return String.valueOf(Math.max(0, Math.min(100, parsed)));
        } catch (NumberFormatException ignored) {
            return "0";
        }
    }

    public static final class RoomConfigEventData {
        static final String KEY_ACTION = "Action";
        static final String KEY_VALUE = "@Value";

        static final BuilderCodec<RoomConfigEventData> CODEC = BuilderCodec.builder(RoomConfigEventData.class, RoomConfigEventData::new)
                .append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .append(new KeyedCodec<>(KEY_VALUE, Codec.STRING), (d, v) -> d.value = v, d -> d.value).add()
                .build();

        @Nullable private String action;
        @Nullable private String value;
    }
}
