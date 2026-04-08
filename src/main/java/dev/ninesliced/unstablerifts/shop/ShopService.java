package dev.ninesliced.unstablerifts.shop;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventItemMerging;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ninesliced.unstablerifts.armor.ArmorDefinition;
import dev.ninesliced.unstablerifts.armor.ArmorDefinitions;
import dev.ninesliced.unstablerifts.armor.ArmorLootRoller;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.dungeon.Level;
import dev.ninesliced.unstablerifts.dungeon.RoomData;
import dev.ninesliced.unstablerifts.dungeon.RoomType;
import dev.ninesliced.unstablerifts.guns.WeaponDefinition;
import dev.ninesliced.unstablerifts.guns.WeaponDefinitions;
import dev.ninesliced.unstablerifts.guns.WeaponLootRoller;
import dev.ninesliced.unstablerifts.guns.WeaponRarity;
import dev.ninesliced.unstablerifts.pickup.ItemPickupTracker;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages shop state per dungeon game: generates items from block configs,
 * tracks which items have been purchased, and handles buy transactions.
 */
public final class ShopService {

    private static final Logger LOGGER = Logger.getLogger(ShopService.class.getName());
    private static final int INITIAL_DISPLAY_SYNC_PASSES = 4;

    /**
     * Maps game (party UUID) → list of shop room inventories.
     */
    private final Map<UUID, List<ShopRoomInventory>> shopsByGame = new ConcurrentHashMap<>();
    /**
     * Tracks spawned shopkeeper entities so we can clean them up when switching floors.
     */
    private final Map<UUID, List<Ref<EntityStore>>> keepersByGame = new ConcurrentHashMap<>();
    /**
     * Tracks live shop display entity refs so pickup systems can always exclude them,
     * even if component-based filtering misses a race during spawn.
     */
    private final Set<Ref<EntityStore>> displayRefs = ConcurrentHashMap.newKeySet();

    /**
     * Initializes shops for all shop rooms in the current level of a game.
     * Called when the game starts or when a new level is loaded.
     */
    public void initializeShops(@Nonnull Game game) {
        initializeShops(game, null, null);
    }

    /**
     * Initializes shop inventories and optionally spawns merchant NPCs for the active level.
     */
    public void initializeShops(@Nonnull Game game,
                                @Nullable Store<EntityStore> store,
                                @Nullable String shopKeeperMobId) {
        Level level = game.getCurrentLevel();
        if (level == null) {
            clearGame(game.getPartyId(), store);
            return;
        }

        clearGame(game.getPartyId(), store);

        List<ShopRoomInventory> inventories = new ArrayList<>();
        List<Ref<EntityStore>> spawnedKeepers = new ArrayList<>();
        for (RoomData room : level.getRooms()) {
            if (room.getType() != RoomType.SHOP) continue;

            Vector3i keeperPos = room.getShopKeeperPosition();
            if (keeperPos == null) {
                LOGGER.warning("Shop room " + room.getAnchor() + " is missing a shopkeeper marker.");
            } else if (store != null && shopKeeperMobId != null && !shopKeeperMobId.isBlank()) {
                Ref<EntityStore> keeperRef = spawnShopKeeper(store, shopKeeperMobId, keeperPos, room);
                if (keeperRef != null) {
                    spawnedKeepers.add(keeperRef);
                }
            }

            if (room.getShopItemSlots().isEmpty()) {
                LOGGER.warning("Shop room " + room.getAnchor() + " has no shop item slots.");
                continue;
            }

            List<ShopEntry> entries = generateShopEntries(room);
            inventories.add(new ShopRoomInventory(
                    room,
                    entries,
                    room.getShopRefreshCost(),
                    room.getShopRefreshCount()));
        }

        UUID partyId = game.getPartyId();
        shopsByGame.put(partyId, inventories);
        if (store != null) {
            scheduleInitialDisplaySync(partyId, store, INITIAL_DISPLAY_SYNC_PASSES);
        }
        if (!spawnedKeepers.isEmpty()) {
            keepersByGame.put(partyId, spawnedKeepers);
        }

        LOGGER.info("Initialized " + inventories.size() + " shop room(s) for party " + partyId);
    }

    /**
     * Clears shop data for a game.
     */
    public void clearGame(@Nonnull UUID partyId) {
        clearGame(partyId, null);
    }

    /**
     * Clears shop data for a game and optionally despawns tracked merchant NPCs.
     */
    public void clearGame(@Nonnull UUID partyId, @Nullable Store<EntityStore> store) {
        List<ShopRoomInventory> inventories = shopsByGame.remove(partyId);

        List<Ref<EntityStore>> keepers = keepersByGame.remove(partyId);
        if (inventories != null && store == null) {
            for (ShopRoomInventory inventory : inventories) {
                for (ShopEntry entry : inventory.entries()) {
                    untrackDisplayRef(entry.getDisplayRef());
                    entry.setDisplayRef(null);
                }
            }
        }
        if (store == null) {
            return;
        }

        if (inventories != null) {
            for (ShopRoomInventory inventory : inventories) {
                for (ShopEntry entry : inventory.entries()) {
                    removeDisplayItem(entry, store);
                }
            }
        }

        if (keepers == null || keepers.isEmpty()) {
            return;
        }

        for (Ref<EntityStore> keeperRef : keepers) {
            if (keeperRef == null || !keeperRef.isValid()) continue;
            if (keeperRef.getStore() != store) continue;
            try {
                store.removeEntity(keeperRef, RemoveReason.REMOVE);
            } catch (Exception e) {
                LOGGER.log(java.util.logging.Level.FINE, "Failed to remove tracked shopkeeper entity", e);
            }
        }
    }

    /**
     * Finds the shop room inventory that the player is near (within shopkeeper action range).
     */
    @Nullable
    public ShopRoomInventory findNearbyShop(@Nonnull Game game, @Nonnull Vector3d playerPos) {
        List<ShopRoomInventory> inventories = shopsByGame.get(game.getPartyId());
        if (inventories == null) return null;

        for (ShopRoomInventory inv : inventories) {
            Vector3i keeperPos = inv.room().getShopKeeperPosition();
            if (keeperPos == null) continue;

            double range = inv.room().getShopActionRange();
            double dx = playerPos.x - (keeperPos.x + 0.5);
            double dy = playerPos.y - (keeperPos.y + 1.0);
            double dz = playerPos.z - (keeperPos.z + 0.5);
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= range * range) {
                return inv;
            }
        }
        return null;
    }

    /**
     * Attempts to purchase an item from the shop.
     * Returns the item stack if successful, null if insufficient funds or already sold.
     */
    @Nullable
    public PurchaseResult tryBuy(@Nonnull Game game, @Nonnull ShopRoomInventory inventory, int entryIndex) {
        return tryBuy(game, inventory, entryIndex, null);
    }

    /**
     * Attempts to purchase an item from the shop and optionally removes its
     * pedestal display entity from the active level store.
     */
    @Nullable
    public PurchaseResult tryBuy(@Nonnull Game game,
                                 @Nonnull ShopRoomInventory inventory,
                                 int entryIndex,
                                 @Nullable Store<EntityStore> store) {
        synchronized (inventory) {
            if (entryIndex < 0 || entryIndex >= inventory.entries().size()) return null;

            ShopEntry entry = inventory.entries().get(entryIndex);
            if (entry.isSold()) return null;

            synchronized (game) {
                if (game.getMoney() < entry.getPrice()) return null;
                game.addMoney(-entry.getPrice());
            }

            entry.setSold(true);
            return new PurchaseResult(entry, entry.getItemStack());
        }
    }

    public void removeSoldDisplay(@Nonnull ShopRoomInventory inventory,
                                  int entryIndex,
                                  @Nullable Store<EntityStore> store) {
        ShopEntry entry;
        synchronized (inventory) {
            if (entryIndex < 0 || entryIndex >= inventory.entries().size()) {
                return;
            }
            entry = inventory.entries().get(entryIndex);
        }
        removeDisplayItem(entry, store);
    }

    public void removeSoldDisplay(@Nullable ShopEntry entry, @Nullable Store<EntityStore> store) {
        if (entry == null) {
            return;
        }
        removeDisplayItem(entry, store);
    }

    public boolean refreshOnFirstRoomEntry(@Nonnull Game game,
                                           @Nonnull RoomData room,
                                           @Nullable Store<EntityStore> store) {
        if (room.getType() != RoomType.SHOP) {
            return false;
        }

        List<ShopRoomInventory> inventories = shopsByGame.get(game.getPartyId());
        if (inventories == null || inventories.isEmpty()) {
            return false;
        }

        for (ShopRoomInventory inventory : inventories) {
            if (!isSameRoom(inventory.room(), room)) {
                continue;
            }
            return refreshWithoutCost(inventory, store);
        }
        return false;
    }

    @Nonnull
    public ShopRefreshResult tryRefresh(@Nonnull Game game,
                                        @Nonnull ShopRoomInventory inventory,
                                        @Nullable Store<EntityStore> store) {
        List<ShopEntry> previousEntries;
        List<ShopEntry> refreshedEntries;

        synchronized (inventory) {
            if (inventory.maxRefreshes() <= 0) {
                return ShopRefreshResult.DISABLED;
            }
            if (inventory.refreshesRemaining() <= 0) {
                return ShopRefreshResult.NO_REFRESHES_REMAINING;
            }

            synchronized (game) {
                if (game.getMoney() < inventory.refreshCost()) {
                    return ShopRefreshResult.INSUFFICIENT_FUNDS;
                }
                game.addMoney(-inventory.refreshCost());
            }

            previousEntries = new ArrayList<>(inventory.entries());
            refreshedEntries = generateShopEntries(inventory.room(), previousEntries, true);
            inventory.replaceEntries(refreshedEntries);
            inventory.consumeRefresh();
        }

        if (store != null) {
            for (ShopEntry entry : previousEntries) {
                removeDisplayItem(entry, store);
            }
            spawnDisplayItems(store, inventory.room(), refreshedEntries);
        }

        return ShopRefreshResult.SUCCESS;
    }

    private boolean refreshWithoutCost(@Nonnull ShopRoomInventory inventory,
                                       @Nullable Store<EntityStore> store) {
        List<ShopEntry> previousEntries;
        List<ShopEntry> refreshedEntries;

        synchronized (inventory) {
            if (!inventory.consumeInitialEntryRefresh()) {
                return false;
            }

            previousEntries = new ArrayList<>(inventory.entries());
            refreshedEntries = generateShopEntries(inventory.room(), previousEntries, true);
            inventory.replaceEntries(refreshedEntries);
        }

        if (store != null) {
            purgePrefabDisplayItems(store, inventory.room());
            for (ShopEntry entry : previousEntries) {
                removeDisplayItem(entry, store);
            }
            spawnDisplayItems(store, inventory.room(), refreshedEntries);
        }

        return true;
    }

    @Nonnull
    private List<ShopEntry> generateShopEntries(@Nonnull RoomData room) {
        return generateShopEntries(room, null, false);
    }

    @Nonnull
    private List<ShopEntry> generateShopEntries(@Nonnull RoomData room,
                                                @Nullable List<ShopEntry> previousEntries,
                                                boolean preferDifferentRolls) {
        List<ShopEntry> entries = new ArrayList<>();

        List<RoomData.ShopItemSlot> slots = room.getShopItemSlots();
        for (int i = 0; i < slots.size(); i++) {
            RoomData.ShopItemSlot slot = slots.get(i);
            ItemStack previousItem = previousEntries != null && i < previousEntries.size()
                    ? previousEntries.get(i).getItemStack()
                    : null;
            ItemStack item = preferDifferentRolls
                    ? generateRefreshedItem(slot, previousItem)
                    : generateItem(slot);
            entries.add(new ShopEntry(slot.itemType(), slot.price(), item));
        }

        return entries;
    }

    @Nullable
    private ItemStack generateRefreshedItem(@Nonnull RoomData.ShopItemSlot slot,
                                            @Nullable ItemStack previousItem) {
        ItemStack firstRoll = generateItem(slot);
        if (previousItem == null
                || slot.itemType() == ShopItemType.AMMO
                || slot.itemType() == ShopItemType.HEAL
                || !Objects.equals(firstRoll, previousItem)) {
            return firstRoll;
        }

        ItemStack latestRoll = firstRoll;
        for (int attempt = 0; attempt < 7; attempt++) {
            latestRoll = generateItem(slot);
            if (!Objects.equals(latestRoll, previousItem)) {
                return latestRoll;
            }
        }
        return latestRoll;
    }

    @Nullable
    private ItemStack generateItem(@Nonnull RoomData.ShopItemSlot slot) {
        return switch (slot.itemType()) {
            case WEAPON -> generateWeapon(slot);
            case ARMOR -> generateArmor(slot);
            case AMMO -> new ItemStack("UnstableRifts_Ammo_Item", 1);
            case HEAL -> new ItemStack("UnstableRifts_Heal_Item", 1);
        };
    }

    @Nonnull
    private ItemStack generateWeapon(@Nonnull RoomData.ShopItemSlot slot) {
        WeaponRarity minRarity = slot.minRarity().isBlank()
                ? WeaponRarity.BASIC : WeaponRarity.fromString(slot.minRarity());
        WeaponRarity maxRarity = slot.maxRarity().isBlank()
                ? WeaponRarity.LEGENDARY : WeaponRarity.fromString(slot.maxRarity());

        if (!slot.weapons().isEmpty()) {
            List<String> whitelist = expandWeaponEntries(slot.weapons());
            return WeaponLootRoller.rollFromCrate(minRarity, maxRarity, whitelist);
        }
        return WeaponLootRoller.rollRandom(null);
    }

    @Nonnull
    private ItemStack generateArmor(@Nonnull RoomData.ShopItemSlot slot) {
        WeaponRarity minRarity = slot.minRarity().isBlank()
                ? WeaponRarity.BASIC : WeaponRarity.fromString(slot.minRarity());
        WeaponRarity maxRarity = slot.maxRarity().isBlank()
                ? WeaponRarity.LEGENDARY : WeaponRarity.fromString(slot.maxRarity());

        if (!slot.armors().isEmpty()) {
            List<String> whitelist = expandArmorEntries(slot.armors());
            return ArmorLootRoller.rollFromCrate(minRarity, maxRarity, whitelist);
        }
        return ArmorLootRoller.rollRandom(null);
    }

    /**
     * Expands weapon shop entries into a whitelist of concrete weapon item ids.
     * Each entry id is treated as a weapon display name and resolves to every
     * variant sharing that name; legacy entries that already store an item id
     * are accepted as-is.
     */
    @Nonnull
    private static List<String> expandWeaponEntries(@Nonnull List<ShopItemData.WeightedEntry> entries) {
        List<String> whitelist = new ArrayList<>();
        for (ShopItemData.WeightedEntry e : entries) {
            int weight = Math.max(1, e.weight());
            List<WeaponDefinition> variants = WeaponDefinitions.getByDisplayName(e.id());
            if (!variants.isEmpty()) {
                for (WeaponDefinition def : variants) {
                    for (int i = 0; i < weight; i++) {
                        whitelist.add(def.itemId());
                    }
                }
            } else if (WeaponDefinitions.getById(e.id()) != null) {
                for (int i = 0; i < weight; i++) {
                    whitelist.add(e.id());
                }
            }
        }
        return whitelist;
    }

    /**
     * Expands armor shop entries into a whitelist of concrete armor item ids.
     * Each entry id is treated as a set id and resolves to every piece of that
     * set; legacy entries that already store an item id are accepted as-is.
     */
    @Nonnull
    private static List<String> expandArmorEntries(@Nonnull List<ShopItemData.WeightedEntry> entries) {
        List<String> whitelist = new ArrayList<>();
        for (ShopItemData.WeightedEntry e : entries) {
            int weight = Math.max(1, e.weight());
            List<ArmorDefinition> pieces = ArmorDefinitions.getBySetId(e.id());
            if (!pieces.isEmpty()) {
                for (ArmorDefinition def : pieces) {
                    for (int i = 0; i < weight; i++) {
                        whitelist.add(def.itemId());
                    }
                }
            } else if (ArmorDefinitions.getById(e.id()) != null) {
                for (int i = 0; i < weight; i++) {
                    whitelist.add(e.id());
                }
            }
        }
        return whitelist;
    }

    @Nullable
    private Ref<EntityStore> spawnShopKeeper(@Nonnull Store<EntityStore> store,
                                             @Nonnull String mobId,
                                             @Nonnull Vector3i keeperPos,
                                             @Nonnull RoomData room) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(mobId);
        if (roleIndex < 0 || !npcPlugin.hasRoleName(mobId)) {
            LOGGER.warning("Shopkeeper role '" + mobId + "' was not found for shop room " + room.getAnchor());
            return null;
        }

        try {
            npcPlugin.validateSpawnableRole(mobId);
            npcPlugin.prepareRoleBuilderInfo(roleIndex);
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING,
                    "Shopkeeper role '" + mobId + "' failed validation for shop room " + room.getAnchor(), e);
            return null;
        }

        Vector3d spawnPos = new Vector3d(keeperPos.x + 0.5, keeperPos.y + 1.0, keeperPos.z + 0.5);
        Rotation3f spawnRotation = new Rotation3f(0.0f, room.getShopKeeperYaw(), 0.0f);
        try {
            var result = npcPlugin.spawnNPC(store, mobId, null, spawnPos, spawnRotation);
            Ref<EntityStore> keeperRef = result != null ? result.first() : null;
            if (keeperRef == null || !keeperRef.isValid()) {
                LOGGER.warning("Failed to spawn shopkeeper '" + mobId + "' for shop room "
                        + room.getAnchor() + " at " + spawnPos + " yaw=" + room.getShopKeeperYaw() + " (ref invalid)");
                return null;
            }
            return keeperRef;
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING,
                    "Failed to spawn shopkeeper '" + mobId + "' for shop room " + room.getAnchor(), e);
            return null;
        }
    }

    private void spawnDisplayItems(@Nonnull Store<EntityStore> store,
                                   @Nonnull RoomData room,
                                   @Nonnull List<ShopEntry> entries) {
        ensureDisplayItems(store, room, entries, false);
    }

    private void ensureDisplayItems(@Nonnull Store<EntityStore> store,
                                    @Nonnull RoomData room,
                                    @Nonnull List<ShopEntry> entries,
                                    boolean onlyIfMissing) {
        List<RoomData.ShopItemSlot> slots = room.getShopItemSlots();
        int count = Math.min(slots.size(), entries.size());
        if (slots.size() != entries.size()) {
            LOGGER.warning("Shop room " + room.getAnchor() + " has " + slots.size()
                    + " slot marker(s) but " + entries.size() + " generated entry/entries.");
        }

        for (int i = 0; i < count; i++) {
            ShopEntry entry = entries.get(i);
            if (onlyIfMissing && hasLiveDisplay(entry)) {
                continue;
            }

            ItemStack itemStack = entry.getItemStack();
            if (ItemStack.isEmpty(itemStack)) {
                LOGGER.warning("Shop room " + room.getAnchor()
                        + " generated an empty item for slot " + i + ".");
                continue;
            }

            Ref<EntityStore> displayRef = spawnDisplayItem(
                    store,
                    copyItemStack(itemStack),
                    slots.get(i).position(),
                    room.getAnchor(),
                    i);
            if (displayRef != null) {
                entry.setDisplayRef(displayRef);
                trackDisplayRef(displayRef);
                ItemPickupTracker.untrack(displayRef);
            }
        }
    }

    private void scheduleInitialDisplaySync(@Nonnull UUID partyId,
                                            @Nonnull Store<EntityStore> store,
                                            int passesRemaining) {
        if (passesRemaining <= 0) {
            return;
        }

        store.getExternalData().getWorld().execute(() -> {
            syncInitialDisplays(partyId, store);
            if (passesRemaining > 1) {
                scheduleInitialDisplaySync(partyId, store, passesRemaining - 1);
            }
        });
    }

    private void syncInitialDisplays(@Nonnull UUID partyId,
                                     @Nonnull Store<EntityStore> store) {
        List<ShopRoomInventory> inventories = shopsByGame.get(partyId);
        if (inventories == null || inventories.isEmpty()) {
            return;
        }

        for (ShopRoomInventory inventory : inventories) {
            purgePrefabDisplayItems(store, inventory.room());
            ensureDisplayItems(store, inventory.room(), inventory.entries(), true);
        }
    }

    private void purgePrefabDisplayItems(@Nonnull Store<EntityStore> store,
                                         @Nonnull RoomData room) {
        Query<EntityStore> query = Query.and(
                ItemComponent.getComponentType(),
                TransformComponent.getComponentType(),
                Query.not(ShopDisplayItemComponent.getComponentType()));

        store.forEachChunk(query, (archetypeChunk, commandBuffer) -> {
            for (int i = 0; i < archetypeChunk.size(); i++) {
                TransformComponent transform =
                        archetypeChunk.getComponent(i, TransformComponent.getComponentType());
                if (transform == null) {
                    continue;
                }

                Vector3d pos = transform.getPosition();
                if (!isNearAnyShopSlot(pos, room)) {
                    continue;
                }

                Ref<EntityStore> ref = archetypeChunk.getReferenceTo(i);
                ItemPickupTracker.untrack(ref);
                commandBuffer.removeEntity(ref, RemoveReason.REMOVE);
            }
        });
    }

    private boolean isNearAnyShopSlot(@Nonnull Vector3d position, @Nonnull RoomData room) {
        for (RoomData.ShopItemSlot slot : room.getShopItemSlots()) {
            Vector3i slotPos = slot.position();
            double centerX = slotPos.x + 0.5;
            double centerZ = slotPos.z + 0.5;
            double dx = Math.abs(position.x - centerX);
            double dy = position.y - slotPos.y;
            double dz = Math.abs(position.z - centerZ);

            if (dx <= 0.85 && dz <= 0.85 && dy >= -0.25 && dy <= 2.25) {
                return true;
            }
        }
        return false;
    }

    private boolean isSameRoom(@Nonnull RoomData first, @Nonnull RoomData second) {
        if (first == second) {
            return true;
        }
        return Objects.equals(first.getAnchor(), second.getAnchor());
    }

    private boolean hasLiveDisplay(@Nonnull ShopEntry entry) {
        Ref<EntityStore> displayRef = entry.getDisplayRef();
        if (displayRef == null || !displayRef.isValid()) {
            entry.setDisplayRef(null);
            return false;
        }

        try {
            Store<EntityStore> displayStore = displayRef.getStore();
            if (displayStore.getComponent(displayRef, ShopDisplayItemComponent.getComponentType()) == null) {
                entry.setDisplayRef(null);
                untrackDisplayRef(displayRef);
                return false;
            }
        } catch (IllegalStateException e) {
            entry.setDisplayRef(null);
            untrackDisplayRef(displayRef);
            return false;
        }

        trackDisplayRef(displayRef);
        ItemPickupTracker.untrack(displayRef);
        return true;
    }

    @Nullable
    private Ref<EntityStore> spawnDisplayItem(@Nonnull Store<EntityStore> store,
                                              @Nonnull ItemStack itemStack,
                                              @Nonnull Vector3i slotPos,
                                              @Nonnull Vector3i roomAnchor,
                                              int slotIndex) {
        Vector3d spawnPos = new Vector3d(slotPos.x + 0.5, slotPos.y + 0.2, slotPos.z + 0.5);
        Holder<EntityStore> holder = ItemComponent.generateItemDrop(
                store, itemStack, spawnPos, Rotation3f.ZERO, 0.0f, 0.0f, 0.0f);
        if (holder == null) {
            return null;
        }

        holder.tryRemoveComponent(DespawnComponent.getComponentType());
        holder.tryRemoveComponent(Velocity.getComponentType());
        holder.tryRemoveComponent(PhysicsValues.getComponentType());
        holder.addComponent(PreventPickup.getComponentType(), PreventPickup.INSTANCE);
        holder.addComponent(PreventItemMerging.getComponentType(), PreventItemMerging.INSTANCE);
        holder.addComponent(
                ShopDisplayItemComponent.getComponentType(),
                new ShopDisplayItemComponent(roomAnchor, slotIndex));

        Ref<EntityStore> displayRef = store.addEntity(holder, AddReason.SPAWN);
        return displayRef;
    }

    private void removeDisplayItem(@Nonnull ShopEntry entry, @Nullable Store<EntityStore> store) {
        Ref<EntityStore> displayRef = entry.getDisplayRef();
        if (displayRef == null || !displayRef.isValid()) {
            untrackDisplayRef(displayRef);
            entry.setDisplayRef(null);
            return;
        }

        untrackDisplayRef(displayRef);
        Store<EntityStore> displayStore = displayRef.getStore();
        try {
            displayStore.getExternalData().getWorld().execute(() -> {
                if (!displayRef.isValid()) {
                    entry.setDisplayRef(null);
                    return;
                }
                ItemPickupTracker.untrack(displayRef);
                displayStore.removeEntity(displayRef, RemoveReason.REMOVE);
                entry.setDisplayRef(null);
            });
        } catch (Exception e) {
            if (store != null && displayStore != store) {
                LOGGER.fine("Removing sold shop display from its owning store instead of the active caller store.");
            }
            LOGGER.log(java.util.logging.Level.FINE, "Failed to remove tracked shop display entity", e);
        }
    }

    public boolean shouldRemoveDisplay(@Nonnull Game game,
                                       @Nonnull Ref<EntityStore> ref,
                                       @Nonnull ShopDisplayItemComponent displayComponent) {
        if (!isTrackedDisplayRef(ref)) {
            return true;
        }

        List<ShopRoomInventory> inventories = shopsByGame.get(game.getPartyId());
        if (inventories == null || inventories.isEmpty()) {
            return true;
        }

        int slotIndex = displayComponent.getSlotIndex();
        if (slotIndex < 0) {
            return true;
        }

        for (ShopRoomInventory inventory : inventories) {
            if (!displayComponent.matchesRoom(inventory.room().getAnchor())) {
                continue;
            }

            synchronized (inventory) {
                if (slotIndex >= inventory.entries().size()) {
                    return true;
                }

                ShopEntry entry = inventory.entries().get(slotIndex);
                Ref<EntityStore> trackedRef = entry.getDisplayRef();
                if (entry.isSold()) {
                    return true;
                }
                if (trackedRef == null || !trackedRef.isValid()) {
                    return true;
                }
                return !trackedRef.equals(ref);
            }
        }

        return true;
    }

    public boolean isTrackedDisplayRef(@Nullable Ref<EntityStore> ref) {
        return ref != null && displayRefs.contains(ref);
    }

    public void untrackDisplayRef(@Nullable Ref<EntityStore> ref) {
        if (ref != null) {
            displayRefs.remove(ref);
        }
    }

    private void trackDisplayRef(@Nonnull Ref<EntityStore> ref) {
        displayRefs.add(ref);
    }

    @Nonnull
    private ItemStack copyItemStack(@Nonnull ItemStack itemStack) {
        @SuppressWarnings("deprecation")
        BsonDocument metadata = itemStack.getMetadata();
        ItemStack copy = new ItemStack(
                itemStack.getItemId(),
                itemStack.getQuantity(),
                itemStack.getDurability(),
                itemStack.getMaxDurability(),
                metadata);
        copy.setOverrideDroppedItemAnimation(itemStack.getOverrideDroppedItemAnimation());
        return copy;
    }

    /**
     * A shop room's generated inventory.
     */
    public enum ShopRefreshResult {
        SUCCESS,
        DISABLED,
        NO_REFRESHES_REMAINING,
        INSUFFICIENT_FUNDS
    }

    public record PurchaseResult(@Nonnull ShopEntry entry, @Nullable ItemStack itemStack) {
    }

    public static final class ShopRoomInventory {
        @Nonnull
        private final RoomData room;
        @Nonnull
        private final List<ShopEntry> entries;
        private final int refreshCost;
        private final int maxRefreshes;
        private int refreshesRemaining;
        private boolean initialEntryRefreshPending = true;

        public ShopRoomInventory(@Nonnull RoomData room,
                                 @Nonnull List<ShopEntry> entries,
                                 int refreshCost,
                                 int maxRefreshes) {
            this.room = room;
            this.entries = new ArrayList<>(entries);
            this.refreshCost = Math.max(0, refreshCost);
            this.maxRefreshes = Math.max(0, maxRefreshes);
            this.refreshesRemaining = this.maxRefreshes;
        }

        @Nonnull
        public RoomData room() {
            return room;
        }

        @Nonnull
        public List<ShopEntry> entries() {
            return entries;
        }

        public int refreshCost() {
            return refreshCost;
        }

        public int maxRefreshes() {
            return maxRefreshes;
        }

        public int refreshesRemaining() {
            return refreshesRemaining;
        }

        public boolean hasRefreshesConfigured() {
            return maxRefreshes > 0;
        }

        private void replaceEntries(@Nonnull List<ShopEntry> refreshedEntries) {
            entries.clear();
            entries.addAll(refreshedEntries);
        }

        private void consumeRefresh() {
            if (refreshesRemaining > 0) {
                refreshesRemaining--;
            }
        }

        private boolean consumeInitialEntryRefresh() {
            if (!initialEntryRefreshPending) {
                return false;
            }
            initialEntryRefreshPending = false;
            return true;
        }
    }
}
