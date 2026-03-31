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
import java.util.ArrayList;
import java.util.List;

/**
 * Mob spawner configuration UI page for level designers.
 * Dynamic list of mob entries with text input for mob ID, weight, and remove button.
 * Saves configuration as a MobSpawnerData block component for prefab persistence.
 */
public final class MobSpawnerConfigPage extends InteractiveCustomUIPage<MobSpawnerConfigPage.SpawnerEventData> {

    private static final String LAYOUT_PATH = "Pages/UnstableRifts/MobSpawnerConfig.ui";
    private static final String LIST_PATH = "#MobListContainer";
    private static final String ENTRY_TEMPLATE = "Pages/UnstableRifts/MobSpawnerEntry.ui";

    @Nullable
    private final BlockPosition blockPos;
    private final List<MobEntry> entries = new ArrayList<>();
    private int spawnCount = 1;
    private boolean initialized = false;

    public MobSpawnerConfigPage(@Nonnull PlayerRef playerRef, @Nullable BlockPosition blockPos) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, SpawnerEventData.CODEC);
        this.blockPos = blockPos;
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        ui.append(LAYOUT_PATH);

        if (!initialized) {
            loadFromBlockEntity(store);
            if (entries.isEmpty()) {
                entries.add(new MobEntry("", "1"));
            }
            initialized = true;
        }

        ui.set("#SpawnCountLabel.Text", String.valueOf(spawnCount));

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#SpawnCountMinusBtn",
                new EventData().put(SpawnerEventData.KEY_ACTION, "COUNT_MINUS"),
                false
        );
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#SpawnCountPlusBtn",
                new EventData().put(SpawnerEventData.KEY_ACTION, "COUNT_PLUS"),
                false
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#AddEntryBtn",
                new EventData().put(SpawnerEventData.KEY_ACTION, "ADD"),
                false
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#SaveBtn",
                new EventData().put(SpawnerEventData.KEY_ACTION, "SAVE"),
                false
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CloseBtn",
                new EventData().put(SpawnerEventData.KEY_ACTION, "CLOSE"),
                false
        );

        buildEntryList(ui, events);
    }

    private void buildEntryList(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events) {
        ui.clear(LIST_PATH);

        for (int i = 0; i < entries.size(); i++) {
            MobEntry entry = entries.get(i);
            String itemPath = LIST_PATH + "[" + i + "]";

            ui.append(LIST_PATH, ENTRY_TEMPLATE);

            ui.set(itemPath + " #MobIdInput.Value", entry.mobId);
            ui.set(itemPath + " #WeightLabel.Text", entry.weight);

            events.addEventBinding(
                    CustomUIEventBindingType.ValueChanged,
                    itemPath + " #MobIdInput",
                    new EventData()
                            .put(SpawnerEventData.KEY_MOB_INPUT, itemPath + " #MobIdInput.Value")
                            .put(SpawnerEventData.KEY_INDEX, String.valueOf(i)),
                    false
            );

            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    itemPath + " #RemoveBtn",
                    new EventData()
                            .put(SpawnerEventData.KEY_ACTION, "REMOVE")
                            .put(SpawnerEventData.KEY_INDEX, String.valueOf(i)),
                    false
            );

            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    itemPath + " #WeightMinusBtn",
                    new EventData()
                            .put(SpawnerEventData.KEY_ACTION, "WEIGHT_MINUS")
                            .put(SpawnerEventData.KEY_INDEX, String.valueOf(i)),
                    false
            );
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    itemPath + " #WeightPlusBtn",
                    new EventData()
                            .put(SpawnerEventData.KEY_ACTION, "WEIGHT_PLUS")
                            .put(SpawnerEventData.KEY_INDEX, String.valueOf(i)),
                    false
            );
        }
    }

    private void refreshList() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        ui.set("#SpawnCountLabel.Text", String.valueOf(spawnCount));
        buildEntryList(ui, events);
        sendUpdate(ui, events, false);
    }

    private void loadFromBlockEntity(@Nonnull Store<EntityStore> store) {
        if (blockPos == null) return;
        World world = store.getExternalData().getWorld();
        if (world == null) return;

        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockPos.x, blockPos.z);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
        if (chunk == null) return;

        Holder<ChunkStore> holder = chunk.getBlockComponentHolder(blockPos.x, blockPos.y, blockPos.z);
        if (holder == null) return;

        MobSpawnerData data = holder.getComponent(MobSpawnerData.getComponentType());
        if (data == null) return;

        entries.clear();
        for (MobSpawnerData.MobEntryData e : data.parseEntries()) {
            entries.add(new MobEntry(e.mobId(), String.valueOf(e.weight())));
        }
        spawnCount = data.parseSpawnCount();
    }

    private void saveToBlockEntity(@Nonnull Store<EntityStore> store) {
        if (blockPos == null) return;
        World world = store.getExternalData().getWorld();
        if (world == null) return;

        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockPos.x, blockPos.z);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
        if (chunk == null) return;

        BlockType blockType = chunk.getBlockType(blockPos.x, blockPos.y, blockPos.z);
        if (blockType == null) return;
        int rotation = chunk.getRotationIndex(blockPos.x, blockPos.y, blockPos.z);

        List<MobSpawnerData.MobEntryData> dataEntries = new ArrayList<>();
        for (MobEntry e : entries) {
            if (!e.mobId.isBlank()) {
                dataEntries.add(new MobSpawnerData.MobEntryData(e.mobId, parseIntSafe(e.weight, 1)));
            }
        }

        MobSpawnerData data = new MobSpawnerData(
                MobSpawnerData.serializeEntries(dataEntries),
                String.valueOf(spawnCount)
        );

        Holder<ChunkStore> holder = ChunkStore.REGISTRY.newHolder();
        holder.putComponent(MobSpawnerData.getComponentType(), data);
        chunk.setState(blockPos.x, blockPos.y, blockPos.z, blockType, rotation, holder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull SpawnerEventData data) {
        if (data.mobInput != null && data.index != null) {
            int idx = parseIntSafe(data.index, -1);
            if (idx >= 0 && idx < entries.size()) {
                entries.get(idx).mobId = data.mobInput;
            }
        }

        String action = data.action;
        if (action == null) return;

        int index = -1;
        if (data.index != null) {
            index = parseIntSafe(data.index, -1);
        }

        switch (action) {
            case "CLOSE" -> {
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
                return;
            }
            case "ADD" -> {
                entries.add(new MobEntry("", "1"));
            }
            case "REMOVE" -> {
                if (index >= 0 && index < entries.size()) {
                    entries.remove(index);
                }
            }
            case "WEIGHT_MINUS" -> {
                if (index >= 0 && index < entries.size()) {
                    MobEntry entry = entries.get(index);
                    int w = parseIntSafe(entry.weight, 1);
                    entry.weight = String.valueOf(Math.max(1, w - 1));
                }
            }
            case "WEIGHT_PLUS" -> {
                if (index >= 0 && index < entries.size()) {
                    MobEntry entry = entries.get(index);
                    int w = parseIntSafe(entry.weight, 1);
                    entry.weight = String.valueOf(w + 1);
                }
            }
            case "COUNT_MINUS" -> {
                spawnCount = Math.max(1, spawnCount - 1);
            }
            case "COUNT_PLUS" -> {
                spawnCount++;
            }
            case "SAVE" -> {
                saveToBlockEntity(store);

                StringBuilder summary = new StringBuilder("Spawner config saved:\n");
                for (MobEntry e : entries) {
                    if (!e.mobId.isBlank()) {
                        summary.append("  ").append(e.mobId).append(" (w:").append(e.weight).append(")\n");
                    }
                }
                summary.append("Spawn count: ").append(spawnCount);

                try {
                    NotificationUtil.sendNotification(
                            this.playerRef.getPacketHandler(),
                            Message.raw(summary.toString()),
                            null,
                            "spawner_config");
                } catch (Exception ignored) {
                }

                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
                return;
            }
        }

        refreshList();
    }

    private static final class MobEntry {
        String mobId;
        String weight;

        MobEntry(String mobId, String weight) {
            this.mobId = mobId;
            this.weight = weight;
        }
    }

    public static final class SpawnerEventData {
        static final String KEY_ACTION = "Action";
        static final String KEY_INDEX = "Index";
        static final String KEY_MOB_INPUT = "@MobInput";

        static final BuilderCodec<SpawnerEventData> CODEC = BuilderCodec.builder(SpawnerEventData.class, SpawnerEventData::new)
                .append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .append(new KeyedCodec<>(KEY_INDEX, Codec.STRING), (d, v) -> d.index = v, d -> d.index).add()
                .append(new KeyedCodec<>(KEY_MOB_INPUT, Codec.STRING), (d, v) -> d.mobInput = v, d -> d.mobInput).add()
                .build();

        @Nullable
        private String action;
        @Nullable
        private String index;
        @Nullable
        private String mobInput;
    }
}
