package dev.ninesliced.shotcave.inventory;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages a per-player inventory lock that restricts the hotbar to 3 weapon slots (0-2)
 * and blocks all other inventory sections from receiving items.
 */
public final class InventoryLockService {

    /** Maximum number of usable hotbar slots when locked. */
    public static final int MAX_WEAPON_SLOTS = 3;

    private final Set<UUID> lockedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Locks the player's inventory: only hotbar slots 0-2 remain usable,
     * storage/armor/utility reject all input.
     */
    public void lock(@Nonnull Player player, @Nonnull UUID playerId) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null) return;
        Store<EntityStore> store = ref.getStore();

        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp != null) {
            ItemContainer hotbar = hotbarComp.getInventory();
            for (short slot = MAX_WEAPON_SLOTS; slot < hotbar.getCapacity(); slot++) {
                hotbar.setSlotFilter(FilterActionType.ADD, slot, SlotFilter.DENY);
            }
        }

        InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        if (storageComp != null) {
            storageComp.getInventory().setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        }

        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComp != null) {
            armorComp.getInventory().setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        }

        InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        if (utilityComp != null) {
            utilityComp.getInventory().setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        }

        InventoryComponent.Tool toolComp = store.getComponent(ref, InventoryComponent.Tool.getComponentType());
        if (toolComp != null) {
            toolComp.getInventory().setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        }

        InventoryComponent.Backpack backpackComp = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());
        if (backpackComp != null) {
            backpackComp.getInventory().setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        }

        lockedPlayers.add(playerId);
    }

    /**
     * Unlocks the player's inventory, restoring normal filter state.
     */
    public void unlock(@Nonnull Player player, @Nonnull UUID playerId) {
        lockedPlayers.remove(playerId);

        Ref<EntityStore> ref = player.getReference();
        if (ref == null) return;
        Store<EntityStore> store = ref.getStore();

        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp != null) {
            ItemContainer hotbar = hotbarComp.getInventory();
            for (short slot = MAX_WEAPON_SLOTS; slot < hotbar.getCapacity(); slot++) {
                hotbar.setSlotFilter(FilterActionType.ADD, slot, SlotFilter.ALLOW);
            }
        }

        InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        if (storageComp != null) {
            storageComp.getInventory().setGlobalFilter(FilterType.ALLOW_ALL);
        }

        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComp != null) {
            armorComp.getInventory().setGlobalFilter(FilterType.ALLOW_ALL);
        }

        InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        if (utilityComp != null) {
            utilityComp.getInventory().setGlobalFilter(FilterType.ALLOW_ALL);
        }

        InventoryComponent.Tool toolComp = store.getComponent(ref, InventoryComponent.Tool.getComponentType());
        if (toolComp != null) {
            toolComp.getInventory().setGlobalFilter(FilterType.ALLOW_ALL);
        }

        InventoryComponent.Backpack backpackComp = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());
        if (backpackComp != null) {
            backpackComp.getInventory().setGlobalFilter(FilterType.ALLOW_ALL);
        }
    }

    public boolean isLocked(@Nonnull UUID playerId) {
        return lockedPlayers.contains(playerId);
    }

    /**
     * Finds the first empty hotbar slot in the range [0, MAX_WEAPON_SLOTS).
     * Returns -1 if all slots are occupied.
     */
    public static short findEmptyWeaponSlot(@Nonnull ItemContainer hotbar) {
        for (short slot = 0; slot < MAX_WEAPON_SLOTS; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Counts occupied slots in the range [0, MAX_WEAPON_SLOTS).
     */
    public static int countOccupiedWeaponSlots(@Nonnull ItemContainer hotbar) {
        int count = 0;
        for (short slot = 0; slot < MAX_WEAPON_SLOTS; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (!ItemStack.isEmpty(stack)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Removes the locked player on disconnect / cleanup.
     */
    public void remove(@Nonnull UUID playerId) {
        lockedPlayers.remove(playerId);
    }
}
