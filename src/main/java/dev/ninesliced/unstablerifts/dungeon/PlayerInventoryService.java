package dev.ninesliced.unstablerifts.dungeon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.guns.GunItemMetadata;
import dev.ninesliced.unstablerifts.inventory.InventoryLockService;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages player inventory persistence: save, restore, clear, and serialize
 * operations for dungeon entry/exit and death/revive flows.
 */
public final class PlayerInventoryService {

    private static final Logger LOGGER = Logger.getLogger(PlayerInventoryService.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final UnstableRifts plugin;

    /**
     * Temporary inventory snapshots saved when a player dies so that ghosts
     * cannot use weapons. Restored on player-revive; discarded on level-change revive.
     */
    private final Map<UUID, InventorySaveData> deathInventorySnapshots = new ConcurrentHashMap<>();

    public PlayerInventoryService(@Nonnull UnstableRifts plugin) {
        this.plugin = plugin;
    }

    // ────────────────────────────────────────────────
    //  File path resolution
    // ────────────────────────────────────────────────

    @Nonnull
    public Path getPrimarySaveFilePath(@Nonnull UUID playerId) {
        return plugin.getDataDirectory()
                .toAbsolutePath()
                .normalize()
                .resolve("saves")
                .resolve(playerId + ".json");
    }

    @Nonnull
    public Path getUniverseSaveFilePath(@Nonnull UUID playerId) {
        Universe universe = Universe.get();
        Path root = universe != null
                ? universe.getPath().toAbsolutePath().normalize().resolve("UnstableRifts")
                : plugin.getDataDirectory().toAbsolutePath().normalize();
        return root.resolve("saves").resolve(playerId + ".json");
    }

    @Nonnull
    public Path getHomeSaveFilePath(@Nonnull UUID playerId) {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return getPrimarySaveFilePath(playerId);
        }
        return Path.of(userHome)
                .resolve(".unstablerifts")
                .resolve("saves")
                .resolve(playerId + ".json");
    }

    public boolean hasSavedInventoryFile(@Nonnull UUID playerId) {
        Path primary = getPrimarySaveFilePath(playerId);
        if (Files.exists(primary)) return true;

        Path universe = getUniverseSaveFilePath(playerId);
        if (!universe.equals(primary) && Files.exists(universe)) return true;

        Path home = getHomeSaveFilePath(playerId);
        return !home.equals(primary) && Files.exists(home);
    }

    @Nonnull
    public Path findExistingSaveFilePath(@Nonnull UUID playerId) {
        Path primary = getPrimarySaveFilePath(playerId);
        if (Files.exists(primary)) return primary;

        Path universe = getUniverseSaveFilePath(playerId);
        if (!universe.equals(primary) && Files.exists(universe)) return universe;

        Path home = getHomeSaveFilePath(playerId);
        if (!home.equals(primary) && Files.exists(home)) return home;

        return primary;
    }

    public void deleteSavedInventoryFiles(@Nonnull UUID playerId) {
        Path primary = getPrimarySaveFilePath(playerId);
        Path universe = getUniverseSaveFilePath(playerId);
        Path home = getHomeSaveFilePath(playerId);
        try {
            Files.deleteIfExists(primary);
            if (!universe.equals(primary)) Files.deleteIfExists(universe);
            if (!home.equals(primary)) Files.deleteIfExists(home);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to delete saved inventory for " + playerId, e);
        }
    }

    // ────────────────────────────────────────────────
    //  Inventory save / restore
    // ────────────────────────────────────────────────

    public void savePlayerInventory(@Nonnull UUID playerId, @Nonnull Player player,
                                    @Nullable Game game) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null) return;
        Store<EntityStore> store = ref.getStore();

        Path savePath = getPrimarySaveFilePath(playerId);
        try {
            Files.createDirectories(savePath.getParent());

            InventorySaveData saveData = snapshotInventory(ref, store);
            String json = GSON.toJson(saveData);
            Files.writeString(savePath, json, StandardCharsets.UTF_8);

            if (game != null) {
                game.getSavedInventoryPaths().put(playerId, savePath.toString());
            }

            LOGGER.info("Saved inventory for player " + playerId + " to " + savePath.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "CRITICAL: Failed to save inventory for " + playerId, e);
        }
    }

    public void restorePlayerInventory(@Nonnull UUID playerId, @Nonnull Player player,
                                       boolean deleteAfterRestore) {
        try {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null) return;
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            LoadedInventorySave loadedSave = loadInventorySave(playerId, ref, store);
            if (loadedSave == null || loadedSave.data == null) {
                LOGGER.warning("No saved inventory found for " + playerId
                        + " fileSnapshot=" + hasSavedInventoryFile(playerId));
                return;
            }
            InventorySaveData saveData = loadedSave.data;

            plugin.getInventoryLockService().unlock(player, playerId);
            clearPlayerInventory(player);

            restoreFromSnapshot(ref, store, saveData);

            InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            if (hotbarComp != null && saveData.activeHotbarSlot >= 0
                    && saveData.activeHotbarSlot < hotbarComp.getInventory().getCapacity()) {
                hotbarComp.setActiveSlot(saveData.activeHotbarSlot);
            }

            syncInventoryAndSelectedSlots(playerRef, ref, store);

            if (deleteAfterRestore) {
                deleteSavedInventoryFiles(playerId);
            }

            LOGGER.info("Restored inventory for player " + playerId + " from " + loadedSave.source
                    + " deleteAfterRestore=" + deleteAfterRestore);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to restore inventory for " + playerId, e);
        }
    }

    public void clearPlayerInventory(@Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null) return;
        Store<EntityStore> store = ref.getStore();

        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        InventoryComponent.Tool toolComp = store.getComponent(ref, InventoryComponent.Tool.getComponentType());
        InventoryComponent.Backpack backpackComp = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());

        if (hotbarComp != null) clearContainer(hotbarComp.getInventory());
        if (storageComp != null) clearContainer(storageComp.getInventory());
        if (armorComp != null) clearContainer(armorComp.getInventory());
        if (utilityComp != null) clearContainer(utilityComp.getInventory());
        if (toolComp != null) clearContainer(toolComp.getInventory());
        if (backpackComp != null) clearContainer(backpackComp.getInventory());
    }

    /**
     * Clears inventory and syncs the change to the client.
     */
    public void clearAndSync(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        plugin.getInventoryLockService().unlock(player, playerRef.getUuid());
        clearPlayerInventory(player);
        Ref<EntityStore> ref = player.getReference();
        if (ref != null) {
            syncInventoryAndSelectedSlots(playerRef, ref, ref.getStore());
        }
    }

    public boolean shouldPreserveSavedInventoryDuringCleanup(@Nonnull UUID playerId,
                                                             @Nonnull Player player,
                                                             @Nullable Game game) {
        if (!hasSavedInventoryFile(playerId)) return false;
        if (player.wasRemoved()) return true;
        World currentWorld = player.getWorld();
        return currentWorld == null || (game != null && currentWorld == game.getInstanceWorld());
    }

    // ────────────────────────────────────────────────
    //  Equipment
    // ────────────────────────────────────────────────

    public void giveStartEquipment(@Nonnull PlayerRef playerRef, @Nonnull Player player,
                                   @Nonnull DungeonConfig config) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null) return;
        Store<EntityStore> store = ref.getStore();

        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp == null) return;

        List<String> equipment = config.getStartEquipment();
        ItemContainer hotbar = hotbarComp.getInventory();
        if (hotbar.getCapacity() <= 0) return;

        byte preferredSlot = getPreferredHotbarSlot(hotbarComp);
        boolean placedPrimaryItem = false;
        short nextFallbackSlot = 0;

        for (String itemId : equipment) {
            if (itemId == null || itemId.isBlank()) continue;

            short targetSlot;
            if (!placedPrimaryItem) {
                targetSlot = preferredSlot;
                placedPrimaryItem = true;
            } else {
                while (nextFallbackSlot < hotbar.getCapacity() && nextFallbackSlot == preferredSlot) {
                    nextFallbackSlot++;
                }
                if (nextFallbackSlot >= hotbar.getCapacity()) break;
                targetSlot = nextFallbackSlot++;
            }

            try {
                ItemStack item = createStartEquipmentItem(itemId);
                if (item == null) {
                    LOGGER.warning("Start equipment item creation returned null: " + itemId);
                    continue;
                }
                hotbar.setItemStackForSlot(targetSlot, item, false);
            } catch (Exception e) {
                LOGGER.warning("Failed to give start equipment item: " + itemId + " - " + e.getMessage());
            }
        }

        if (hotbarComp.getActiveSlot() != preferredSlot) {
            hotbarComp.setActiveSlot(preferredSlot);
        }

        syncInventoryAndSelectedSlots(playerRef, ref, store);
    }

    public void syncInventoryAndSelectedSlots(@Nullable PlayerRef playerRef,
                                              @Nonnull Ref<EntityStore> ref,
                                              @Nonnull Store<EntityStore> store) {
        if (playerRef == null || !playerRef.isValid()) return;

        InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        InventoryComponent.Tool toolComp = store.getComponent(ref, InventoryComponent.Tool.getComponentType());
        InventoryComponent.Backpack backpackComp = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());

        playerRef.getPacketHandler().writeNoCache(new UpdatePlayerInventory(
                storageComp != null ? storageComp.getInventory().toPacket() : null,
                armorComp != null ? armorComp.getInventory().toPacket() : null,
                hotbarComp != null ? hotbarComp.getInventory().toPacket() : null,
                utilityComp != null ? utilityComp.getInventory().toPacket() : null,
                toolComp != null ? toolComp.getInventory().toPacket() : null,
                backpackComp != null ? backpackComp.getInventory().toPacket() : null
        ));
        playerRef.getPacketHandler().writeNoCache(new SetActiveSlot(-1,
                hotbarComp != null ? hotbarComp.getActiveSlot() : (byte) 0));
    }

    // ────────────────────────────────────────────────
    //  Disconnect / reconnect inventory snapshots
    // ────────────────────────────────────────────────

    /**
     * Creates an in-memory snapshot of the player's current inventory without
     * clearing it. Used on disconnect so the dungeon inventory can be restored
     * on reconnect.
     *
     * @return the snapshot, or null if the player reference is invalid.
     */
    @Nullable
    public InventorySaveData snapshotCurrentInventory(@Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return null;
        Store<EntityStore> store = ref.getStore();
        return snapshotInventory(ref, store);
    }

    /**
     * Restores a player's inventory from an in-memory dungeon snapshot (e.g. after
     * reconnecting). The caller is responsible for clearing the inventory beforehand.
     */
    public void restoreDungeonInventory(@Nonnull UUID playerId,
                                        @Nonnull Player player,
                                        @Nonnull InventorySaveData snapshot) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        Store<EntityStore> store = ref.getStore();

        restoreFromSnapshot(ref, store, snapshot);

        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp != null && snapshot.activeHotbarSlot >= 0
                && snapshot.activeHotbarSlot < hotbarComp.getInventory().getCapacity()) {
            hotbarComp.setActiveSlot(snapshot.activeHotbarSlot);
        }

        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        syncInventoryAndSelectedSlots(playerRef, ref, store);
    }

    // ────────────────────────────────────────────────
    //  Death inventory snapshots
    // ────────────────────────────────────────────────

    /**
     * Saves the player's current inventory into a temporary snapshot, then
     * clears the inventory so the dead/ghost player cannot use weapons.
     */
    public void saveAndClearDeathInventory(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        Store<EntityStore> store = ref.getStore();

        InventorySaveData snapshot = snapshotInventory(ref, store);
        deathInventorySnapshots.put(playerId, snapshot);

        plugin.getInventoryLockService().unlock(player, playerId);
        clearPlayerInventory(player);
        syncInventoryAndSelectedSlots(playerRef, ref, store);
    }

    /**
     * Restores the player's inventory from the death snapshot.
     */
    public void restoreDeathInventory(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        InventorySaveData snapshot = deathInventorySnapshots.remove(playerId);
        if (snapshot == null) return;

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        Store<EntityStore> store = ref.getStore();

        clearPlayerInventory(player);
        restoreFromSnapshot(ref, store, snapshot);
        syncInventoryAndSelectedSlots(playerRef, ref, store);
        plugin.getInventoryLockService().lock(player, playerId);
    }

    public void discardDeathInventory(@Nonnull UUID playerId) {
        deathInventorySnapshots.remove(playerId);
    }

    public void clearDeathSnapshots() {
        deathInventorySnapshots.clear();
    }

    public void removeDeathSnapshot(@Nonnull UUID playerId) {
        deathInventorySnapshots.remove(playerId);
    }

    /**
     * Returns true when the player's live inventory still contains items that
     * should never survive a clean dungeon entry.
     */
    public boolean hasUnexpectedDungeonEntryInventory(@Nonnull Ref<EntityStore> ref,
                                                      @Nonnull Store<EntityStore> store) {
        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp != null && hasItemsBeyondWeaponSlots(hotbarComp.getInventory())) {
            return true;
        }

        InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        if (storageComp != null && hasAnyItems(storageComp.getInventory())) {
            return true;
        }

        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComp != null && hasAnyItems(armorComp.getInventory())) {
            return true;
        }

        InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        if (utilityComp != null && hasAnyItems(utilityComp.getInventory())) {
            return true;
        }

        InventoryComponent.Tool toolComp = store.getComponent(ref, InventoryComponent.Tool.getComponentType());
        if (toolComp != null && hasAnyItems(toolComp.getInventory())) {
            return true;
        }

        InventoryComponent.Backpack backpackComp = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());
        return backpackComp != null && hasAnyItems(backpackComp.getInventory());
    }

    // ────────────────────────────────────────────────
    //  Internal helpers
    // ────────────────────────────────────────────────

    private InventorySaveData snapshotInventory(@Nonnull Ref<EntityStore> ref,
                                                @Nonnull Store<EntityStore> store) {
        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        InventoryComponent.Tool toolComp = store.getComponent(ref, InventoryComponent.Tool.getComponentType());
        InventoryComponent.Backpack backpackComp = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());

        InventorySaveData data = new InventorySaveData();
        data.hotbarItems = hotbarComp != null ? serializeContainer(hotbarComp.getInventory()) : null;
        data.storageItems = storageComp != null ? serializeContainer(storageComp.getInventory()) : null;
        data.armorItems = armorComp != null ? serializeContainer(armorComp.getInventory()) : null;
        data.utilityItems = utilityComp != null ? serializeContainer(utilityComp.getInventory()) : null;
        data.toolItems = toolComp != null ? serializeContainer(toolComp.getInventory()) : null;
        data.backpackItems = backpackComp != null ? serializeContainer(backpackComp.getInventory()) : null;
        data.activeHotbarSlot = hotbarComp != null ? hotbarComp.getActiveSlot() : 0;
        return data;
    }

    private void restoreFromSnapshot(@Nonnull Ref<EntityStore> ref,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull InventorySaveData saveData) {
        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        InventoryComponent.Tool toolComp = store.getComponent(ref, InventoryComponent.Tool.getComponentType());
        InventoryComponent.Backpack backpackComp = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());

        if (hotbarComp != null) restoreContainer(hotbarComp.getInventory(), saveData.hotbarItems);
        if (storageComp != null) restoreContainer(storageComp.getInventory(), saveData.storageItems);
        if (armorComp != null) restoreContainer(armorComp.getInventory(), saveData.armorItems);
        if (utilityComp != null) restoreContainer(utilityComp.getInventory(), saveData.utilityItems);
        if (toolComp != null) restoreContainer(toolComp.getInventory(), saveData.toolItems);
        if (backpackComp != null) restoreContainer(backpackComp.getInventory(), saveData.backpackItems);
    }

    private boolean hasItemsBeyondWeaponSlots(@Nonnull ItemContainer container) {
        for (short slot = InventoryLockService.MAX_WEAPON_SLOTS; slot < container.getCapacity(); slot++) {
            if (!ItemStack.isEmpty(container.getItemStack(slot))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyItems(@Nonnull ItemContainer container) {
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            if (!ItemStack.isEmpty(container.getItemStack(slot))) {
                return true;
            }
        }
        return false;
    }

    private void clearContainer(@Nonnull ItemContainer container) {
        for (short i = 0; i < container.getCapacity(); i++) {
            container.removeItemStackFromSlot(i);
        }
    }

    @Nonnull
    private SavedItemStack[] serializeContainer(@Nonnull ItemContainer container) {
        SavedItemStack[] items = new SavedItemStack[container.getCapacity()];
        for (short i = 0; i < container.getCapacity(); i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                SavedItemStack saved = new SavedItemStack();
                saved.itemId = stack.getItemId();
                saved.quantity = stack.getQuantity();
                saved.durability = stack.getDurability();
                saved.maxDurability = stack.getMaxDurability();
                saved.overrideDroppedItemAnimation = stack.getOverrideDroppedItemAnimation();
                BsonDocument metadata = stack.getMetadata();
                saved.metadataJson = metadata != null ? metadata.toJson() : null;
                items[i] = saved;
            }
        }
        return items;
    }

    private void restoreContainer(@Nonnull ItemContainer container, @Nullable SavedItemStack[] items) {
        if (items == null) return;
        for (short i = 0; i < Math.min(items.length, container.getCapacity()); i++) {
            SavedItemStack saved = items[i];
            if (saved != null && saved.itemId != null && !saved.itemId.isEmpty()) {
                try {
                    BsonDocument metadata = saved.metadataJson != null && !saved.metadataJson.isBlank()
                            ? BsonDocument.parse(saved.metadataJson)
                            : null;
                    ItemStack restored = new ItemStack(
                            saved.itemId,
                            Math.max(1, saved.quantity),
                            saved.durability,
                            saved.maxDurability,
                            metadata
                    );
                    restored.setOverrideDroppedItemAnimation(saved.overrideDroppedItemAnimation);
                    container.setItemStackForSlot(i, restored, false);
                } catch (Exception e) {
                    LOGGER.warning("Failed to restore item '" + saved.itemId + "' at slot " + i);
                }
            }
        }
    }

    @Nullable
    private LoadedInventorySave loadInventorySave(@Nonnull UUID playerId,
                                                  @Nonnull Ref<EntityStore> ref,
                                                  @Nonnull Store<EntityStore> store) throws IOException {
        Path savePath = findExistingSaveFilePath(playerId);
        if (Files.exists(savePath)) {
            String json = Files.readString(savePath, StandardCharsets.UTF_8);
            return new LoadedInventorySave("file:" + savePath, GSON.fromJson(json, InventorySaveData.class));
        }
        return null;
    }

    @Nullable
    private ItemStack createStartEquipmentItem(@Nonnull String itemId) {
        try {
            return GunItemMetadata.initializeFullAmmo(new ItemStack(itemId, 1));
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Invalid start equipment item id: " + itemId);
            return null;
        }
    }

    private byte getPreferredHotbarSlot(@Nonnull InventoryComponent.Hotbar hotbarComp) {
        byte activeSlot = hotbarComp.getActiveSlot();
        if (activeSlot >= 0 && activeSlot < InventoryLockService.MAX_WEAPON_SLOTS) {
            return activeSlot;
        }
        return 0;
    }

    // ────────────────────────────────────────────────
    //  Data structures
    // ────────────────────────────────────────────────

    static final class InventorySaveData {
        SavedItemStack[] hotbarItems;
        SavedItemStack[] storageItems;
        SavedItemStack[] armorItems;
        SavedItemStack[] utilityItems;
        SavedItemStack[] toolItems;
        SavedItemStack[] backpackItems;
        byte activeHotbarSlot;
    }

    static final class SavedItemStack {
        String itemId;
        int quantity;
        double durability;
        double maxDurability;
        boolean overrideDroppedItemAnimation;
        String metadataJson;
    }

    private record LoadedInventorySave(String source, InventorySaveData data) {
        private LoadedInventorySave(@Nonnull String source, @Nonnull InventorySaveData data) {
            this.source = source;
            this.data = data;
        }
    }
}
