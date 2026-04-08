package dev.ninesliced.unstablerifts.shop;

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
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import dev.ninesliced.unstablerifts.armor.ArmorDefinition;
import dev.ninesliced.unstablerifts.armor.ArmorDefinitions;
import dev.ninesliced.unstablerifts.guns.WeaponDefinition;
import dev.ninesliced.unstablerifts.guns.WeaponDefinitions;
import dev.ninesliced.unstablerifts.guns.WeaponRarity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Config page for ShopItem blocks — allows level designers to configure item type,
 * price, weapon/armor pool with weights, and rarity range.
 * <p>
 * Uses DropdownBox widgets for weapon/armor/rarity selection.
 */
public final class ShopItemConfigPage extends InteractiveCustomUIPage<ShopItemConfigPage.ConfigEventData> {

    private static final String LAYOUT_PATH = "Pages/UnstableRifts/ShopItemConfig.ui";
    private static final String WEAPON_LIST_PATH = "#WeaponListContainer";
    private static final String ARMOR_LIST_PATH = "#ArmorListContainer";
    private static final String ENTRY_TEMPLATE = "Pages/UnstableRifts/ShopItemEntry.ui";

    private static final ShopItemType[] ITEM_TYPES = ShopItemType.values();
    private static final WeaponRarity[] RARITIES = WeaponRarity.values();

    @Nullable
    private final BlockPosition blockPos;
    private int itemTypeIndex = 0;
    private String price = "10";
    private final List<PoolEntry> weaponEntries = new ArrayList<>();
    private final List<PoolEntry> armorEntries = new ArrayList<>();
    private int minRarityIndex = 0;
    private int maxRarityIndex = RARITIES.length - 1;
    private boolean initialized = false;

    public ShopItemConfigPage(@Nonnull PlayerRef playerRef, @Nullable BlockPosition blockPos) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ConfigEventData.CODEC);
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

        buildAll(ui, events);
    }

    private void buildAll(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events) {
        buildCommonFields(ui, events);
        buildRarityDropdowns(ui, events);
        buildWeaponList(ui, events);
        buildArmorList(ui, events);
    }

    private void buildCommonFields(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events) {
        ui.set("#ItemTypeLabel.Text", ITEM_TYPES[itemTypeIndex].name());
        ui.set("#PriceInput.Value", price);

        boolean showWeaponFields = ITEM_TYPES[itemTypeIndex] == ShopItemType.WEAPON;
        boolean showArmorFields = ITEM_TYPES[itemTypeIndex] == ShopItemType.ARMOR;
        ui.set("#WeaponSection.Visible", showWeaponFields);
        ui.set("#ArmorSection.Visible", showArmorFields);
        ui.set("#RaritySection.Visible", showWeaponFields || showArmorFields);

        // Item type cycling (prev/next — only 4 values)
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ItemTypePrevBtn",
                new EventData().put(ConfigEventData.KEY_ACTION, "TYPE_PREV"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ItemTypeNextBtn",
                new EventData().put(ConfigEventData.KEY_ACTION, "TYPE_NEXT"), false);

        // Price input
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PriceInput",
                new EventData().put(ConfigEventData.KEY_PRICE_INPUT, "#PriceInput.Value"), false);

        // Add entry buttons
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddWeaponBtn",
                new EventData().put(ConfigEventData.KEY_ACTION, "ADD_WEAPON"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddArmorBtn",
                new EventData().put(ConfigEventData.KEY_ACTION, "ADD_ARMOR"), false);

        // Save / Close
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveBtn",
                new EventData().put(ConfigEventData.KEY_ACTION, "SAVE"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseBtn",
                new EventData().put(ConfigEventData.KEY_ACTION, "CLOSE"), false);
    }

    private void buildRarityDropdowns(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events) {
        List<DropdownEntryInfo> rarityOptions = new ArrayList<>();
        for (WeaponRarity rarity : RARITIES) {
            rarityOptions.add(new DropdownEntryInfo(LocalizableString.fromString(rarity.name()), rarity.name()));
        }

        ui.set("#MinRarityDropdown.Entries", rarityOptions);
        ui.set("#MinRarityDropdown.Value", RARITIES[minRarityIndex].name());
        ui.set("#MaxRarityDropdown.Entries", rarityOptions);
        ui.set("#MaxRarityDropdown.Value", RARITIES[maxRarityIndex].name());

        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#MinRarityDropdown",
                new EventData()
                        .put(ConfigEventData.KEY_ACTION, "MIN_RARITY_CHANGE")
                        .put(ConfigEventData.KEY_VALUE, "#MinRarityDropdown.Value"),
                false);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#MaxRarityDropdown",
                new EventData()
                        .put(ConfigEventData.KEY_ACTION, "MAX_RARITY_CHANGE")
                        .put(ConfigEventData.KEY_VALUE, "#MaxRarityDropdown.Value"),
                false);
    }

    private void buildWeaponList(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events) {
        ui.clear(WEAPON_LIST_PATH);

        List<DropdownEntryInfo> weaponOptions = buildWeaponDropdownEntries();

        for (int i = 0; i < weaponEntries.size(); i++) {
            PoolEntry entry = weaponEntries.get(i);
            String itemPath = WEAPON_LIST_PATH + "[" + i + "]";

            ui.append(WEAPON_LIST_PATH, ENTRY_TEMPLATE);

            ui.set(itemPath + " #EntryDropdown.Entries", weaponOptions);
            ui.set(itemPath + " #EntryDropdown.Value", entry.id);
            ui.set(itemPath + " #WeightLabel.Text", String.valueOf(entry.weight));

            events.addEventBinding(CustomUIEventBindingType.ValueChanged, itemPath + " #EntryDropdown",
                    new EventData()
                            .put(ConfigEventData.KEY_ACTION, "WEAPON_CHANGE")
                            .put(ConfigEventData.KEY_INDEX, String.valueOf(i))
                            .put(ConfigEventData.KEY_VALUE, itemPath + " #EntryDropdown.Value"),
                    false);
            events.addEventBinding(CustomUIEventBindingType.Activating, itemPath + " #WeightMinusBtn",
                    new EventData().put(ConfigEventData.KEY_ACTION, "WEAPON_WEIGHT_MINUS")
                            .put(ConfigEventData.KEY_INDEX, String.valueOf(i)), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, itemPath + " #WeightPlusBtn",
                    new EventData().put(ConfigEventData.KEY_ACTION, "WEAPON_WEIGHT_PLUS")
                            .put(ConfigEventData.KEY_INDEX, String.valueOf(i)), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, itemPath + " #RemoveBtn",
                    new EventData().put(ConfigEventData.KEY_ACTION, "WEAPON_REMOVE")
                            .put(ConfigEventData.KEY_INDEX, String.valueOf(i)), false);
        }
    }

    private void buildArmorList(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events) {
        ui.clear(ARMOR_LIST_PATH);

        List<DropdownEntryInfo> armorOptions = buildArmorDropdownEntries();

        for (int i = 0; i < armorEntries.size(); i++) {
            PoolEntry entry = armorEntries.get(i);
            String itemPath = ARMOR_LIST_PATH + "[" + i + "]";

            ui.append(ARMOR_LIST_PATH, ENTRY_TEMPLATE);

            ui.set(itemPath + " #EntryDropdown.Entries", armorOptions);
            ui.set(itemPath + " #EntryDropdown.Value", entry.id);
            ui.set(itemPath + " #WeightLabel.Text", String.valueOf(entry.weight));

            events.addEventBinding(CustomUIEventBindingType.ValueChanged, itemPath + " #EntryDropdown",
                    new EventData()
                            .put(ConfigEventData.KEY_ACTION, "ARMOR_CHANGE")
                            .put(ConfigEventData.KEY_INDEX, String.valueOf(i))
                            .put(ConfigEventData.KEY_VALUE, itemPath + " #EntryDropdown.Value"),
                    false);
            events.addEventBinding(CustomUIEventBindingType.Activating, itemPath + " #WeightMinusBtn",
                    new EventData().put(ConfigEventData.KEY_ACTION, "ARMOR_WEIGHT_MINUS")
                            .put(ConfigEventData.KEY_INDEX, String.valueOf(i)), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, itemPath + " #WeightPlusBtn",
                    new EventData().put(ConfigEventData.KEY_ACTION, "ARMOR_WEIGHT_PLUS")
                            .put(ConfigEventData.KEY_INDEX, String.valueOf(i)), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, itemPath + " #RemoveBtn",
                    new EventData().put(ConfigEventData.KEY_ACTION, "ARMOR_REMOVE")
                            .put(ConfigEventData.KEY_INDEX, String.valueOf(i)), false);
        }
    }

    @Nonnull
    private List<DropdownEntryInfo> buildWeaponDropdownEntries() {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        // One entry per unique display name — selecting it adds every variant
        // sharing that name (e.g., the four elemental Musket variants).
        for (String name : WeaponDefinitions.getDistinctDisplayNames()) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(name), name));
        }
        return entries;
    }

    @Nonnull
    private List<DropdownEntryInfo> buildArmorDropdownEntries() {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        // One entry per armor set — selecting a set covers all of its pieces
        // instead of forcing the designer to add helmet/chest/legs/boots one by one.
        for (String setId : ArmorDefinitions.getDistinctSetIds()) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(formatSetLabel(setId)), setId));
        }
        return entries;
    }

    @Nonnull
    private static String formatSetLabel(@Nonnull String setId) {
        if (setId.isEmpty()) return setId;
        return Character.toUpperCase(setId.charAt(0)) + setId.substring(1) + " Set";
    }

    /**
     * Resolves a stored weapon entry id to its current group key (display name).
     * Accepts a display name as-is, or converts a legacy item id to its display name.
     */
    @Nonnull
    private static String resolveWeaponGroupKey(@Nonnull String stored) {
        WeaponDefinition byId = WeaponDefinitions.getById(stored);
        if (byId != null) return byId.displayName();
        return stored;
    }

    /**
     * Resolves a stored armor entry id to its current group key (set id).
     * Accepts a set id as-is, or converts a legacy item id to its set id.
     */
    @Nonnull
    private static String resolveArmorGroupKey(@Nonnull String stored) {
        ArmorDefinition byId = ArmorDefinitions.getById(stored);
        if (byId != null) return byId.setId();
        return stored;
    }

    /**
     * Adds {@code weight} to the existing entry for {@code key}, or appends a
     * new entry if none yet exists. Used to dedupe legacy entries that stored
     * each set piece / weapon variant individually.
     */
    private static void mergePoolEntry(@Nonnull List<PoolEntry> entries, @Nonnull String key, int weight) {
        for (PoolEntry existing : entries) {
            if (existing.id.equals(key)) {
                existing.weight += weight;
                return;
            }
        }
        entries.add(new PoolEntry(key, weight));
    }

    // ── Block load/save ──

    private void loadFromBlock(@Nonnull Store<EntityStore> store) {
        if (blockPos == null) return;
        World world = store.getExternalData().getWorld();
        if (world == null) return;

        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockPos.x, blockPos.z);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
        if (chunk == null) return;

        Holder<ChunkStore> holder = chunk.getBlockComponentHolder(blockPos.x, blockPos.y, blockPos.z);
        if (holder == null) return;

        ShopItemData data = holder.getComponent(ShopItemData.getComponentType());
        if (data == null) return;

        ShopItemType type = data.parseItemType();
        if (type != null) itemTypeIndex = type.ordinal();

        String p = data.getPrice();
        if (p != null && !p.isBlank()) price = p;

        // Migrate legacy entries that stored individual item IDs into the new
        // grouped form (display name for weapons, set ID for armors).
        for (ShopItemData.WeightedEntry e : data.parseWeaponEntries()) {
            String groupKey = resolveWeaponGroupKey(e.id());
            mergePoolEntry(weaponEntries, groupKey, e.weight());
        }
        for (ShopItemData.WeightedEntry e : data.parseArmorEntries()) {
            String groupKey = resolveArmorGroupKey(e.id());
            mergePoolEntry(armorEntries, groupKey, e.weight());
        }

        String min = data.getMinRarity();
        if (min != null && !min.isBlank()) minRarityIndex = WeaponRarity.fromString(min).ordinal();
        String max = data.getMaxRarity();
        if (max != null && !max.isBlank()) maxRarityIndex = WeaponRarity.fromString(max).ordinal();
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

        List<ShopItemData.WeightedEntry> wEntries = new ArrayList<>();
        for (PoolEntry e : weaponEntries) {
            if (!e.id.isBlank()) wEntries.add(new ShopItemData.WeightedEntry(e.id, e.weight));
        }
        List<ShopItemData.WeightedEntry> aEntries = new ArrayList<>();
        for (PoolEntry e : armorEntries) {
            if (!e.id.isBlank()) aEntries.add(new ShopItemData.WeightedEntry(e.id, e.weight));
        }

        ShopItemData data = new ShopItemData(
                ITEM_TYPES[itemTypeIndex].name(),
                price,
                ShopItemData.serializeWeightedEntries(wEntries),
                ShopItemData.serializeWeightedEntries(aEntries),
                RARITIES[minRarityIndex].name(),
                RARITIES[maxRarityIndex].name()
        );

        Holder<ChunkStore> holder = ChunkStore.REGISTRY.newHolder();
        holder.putComponent(ShopItemData.getComponentType(), data);
        chunk.setState(blockPos.x, blockPos.y, blockPos.z, blockType, rotation, holder);
    }

    // ── Event handling ──

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull ConfigEventData data) {
        if (data.priceInput != null) price = data.priceInput;

        String action = data.action;
        if (action == null) return;

        int index = data.index != null ? parseIntSafe(data.index, -1) : -1;

        switch (action) {
            case "CLOSE" -> {
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) player.getPageManager().setPage(ref, store, Page.None);
                return;
            }
            case "TYPE_PREV" -> itemTypeIndex = (itemTypeIndex - 1 + ITEM_TYPES.length) % ITEM_TYPES.length;
            case "TYPE_NEXT" -> itemTypeIndex = (itemTypeIndex + 1) % ITEM_TYPES.length;

            // Dropdown selection changes — update server state, no full refresh needed
            case "MIN_RARITY_CHANGE" -> {
                if (data.value != null) {
                    for (int i = 0; i < RARITIES.length; i++) {
                        if (RARITIES[i].name().equals(data.value)) {
                            minRarityIndex = i;
                            if (minRarityIndex > maxRarityIndex) maxRarityIndex = minRarityIndex;
                            break;
                        }
                    }
                }
                return;
            }
            case "MAX_RARITY_CHANGE" -> {
                if (data.value != null) {
                    for (int i = 0; i < RARITIES.length; i++) {
                        if (RARITIES[i].name().equals(data.value)) {
                            maxRarityIndex = i;
                            if (maxRarityIndex < minRarityIndex) minRarityIndex = maxRarityIndex;
                            break;
                        }
                    }
                }
                return;
            }
            case "WEAPON_CHANGE" -> {
                if (index >= 0 && index < weaponEntries.size() && data.value != null) {
                    weaponEntries.get(index).id = data.value;
                }
                return;
            }
            case "ARMOR_CHANGE" -> {
                if (index >= 0 && index < armorEntries.size() && data.value != null) {
                    armorEntries.get(index).id = data.value;
                }
                return;
            }

            // Weapon pool management
            case "ADD_WEAPON" -> {
                List<String> names = WeaponDefinitions.getDistinctDisplayNames();
                if (!names.isEmpty()) {
                    weaponEntries.add(new PoolEntry(names.get(0), 1));
                }
            }
            case "WEAPON_REMOVE" -> {
                if (index >= 0 && index < weaponEntries.size()) weaponEntries.remove(index);
            }
            case "WEAPON_WEIGHT_MINUS" -> {
                if (index >= 0 && index < weaponEntries.size()) {
                    weaponEntries.get(index).weight = Math.max(1, weaponEntries.get(index).weight - 1);
                }
            }
            case "WEAPON_WEIGHT_PLUS" -> {
                if (index >= 0 && index < weaponEntries.size()) weaponEntries.get(index).weight++;
            }

            // Armor pool management
            case "ADD_ARMOR" -> {
                List<String> setIds = ArmorDefinitions.getDistinctSetIds();
                if (!setIds.isEmpty()) {
                    armorEntries.add(new PoolEntry(setIds.get(0), 1));
                }
            }
            case "ARMOR_REMOVE" -> {
                if (index >= 0 && index < armorEntries.size()) armorEntries.remove(index);
            }
            case "ARMOR_WEIGHT_MINUS" -> {
                if (index >= 0 && index < armorEntries.size()) {
                    armorEntries.get(index).weight = Math.max(1, armorEntries.get(index).weight - 1);
                }
            }
            case "ARMOR_WEIGHT_PLUS" -> {
                if (index >= 0 && index < armorEntries.size()) armorEntries.get(index).weight++;
            }

            case "SAVE" -> {
                saveToBlock(store);
                try {
                    StringBuilder summary = new StringBuilder("Shop item config saved:\n");
                    summary.append("  Type: ").append(ITEM_TYPES[itemTypeIndex].name()).append('\n');
                    summary.append("  Price: ").append(price);
                    NotificationUtil.sendNotification(
                            this.playerRef.getPacketHandler(),
                            Message.raw(summary.toString()),
                            null,
                            "shopitem_config");
                } catch (Exception ignored) { }
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) player.getPageManager().setPage(ref, store, Page.None);
                return;
            }
        }

        refreshPage();
    }

    private void refreshPage() {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        buildAll(ui, events);
        sendUpdate(ui, events, false);
    }

    private static int parseIntSafe(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static final class PoolEntry {
        String id;
        int weight;
        PoolEntry(String id, int weight) { this.id = id; this.weight = weight; }
    }

    public static final class ConfigEventData {
        static final String KEY_ACTION = "Action";
        static final String KEY_INDEX = "Index";
        static final String KEY_PRICE_INPUT = "@PriceInput";
        static final String KEY_VALUE = "@Value";

        static final BuilderCodec<ConfigEventData> CODEC = BuilderCodec.builder(ConfigEventData.class, ConfigEventData::new)
                .append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .append(new KeyedCodec<>(KEY_INDEX, Codec.STRING), (d, v) -> d.index = v, d -> d.index).add()
                .append(new KeyedCodec<>(KEY_PRICE_INPUT, Codec.STRING), (d, v) -> d.priceInput = v, d -> d.priceInput).add()
                .append(new KeyedCodec<>(KEY_VALUE, Codec.STRING), (d, v) -> d.value = v, d -> d.value).add()
                .build();

        @Nullable private String action;
        @Nullable private String index;
        @Nullable private String priceInput;
        @Nullable private String value;
    }
}
