package dev.ninesliced.shotcave.inventory;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;

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
    @SuppressWarnings("removal")
    public void lock(@Nonnull Player player, @Nonnull UUID playerId) {
        Inventory inventory = player.getInventory();
        if (inventory == null) return;

        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar != null) {
            for (short slot = MAX_WEAPON_SLOTS; slot < hotbar.getCapacity(); slot++) {
                hotbar.setSlotFilter(FilterActionType.ADD, slot, SlotFilter.DENY);
            }
        }

        ItemContainer storage = inventory.getStorage();
        if (storage != null) {
            storage.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        }

        ItemContainer armor = inventory.getArmor();
        if (armor != null) {
            armor.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        }

        ItemContainer utility = inventory.getUtility();
        if (utility != null) {
            utility.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        }

        ItemContainer tools = inventory.getTools();
        if (tools != null) {
            tools.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        }

        ItemContainer backpack = inventory.getBackpack();
        if (backpack != null) {
            backpack.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        }

        lockedPlayers.add(playerId);
    }

    /**
     * Unlocks the player's inventory, restoring normal filter state.
     */
    @SuppressWarnings("removal")
    public void unlock(@Nonnull Player player, @Nonnull UUID playerId) {
        lockedPlayers.remove(playerId);

        Inventory inventory = player.getInventory();
        if (inventory == null) return;

        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar != null) {
            for (short slot = MAX_WEAPON_SLOTS; slot < hotbar.getCapacity(); slot++) {
                hotbar.setSlotFilter(FilterActionType.ADD, slot, SlotFilter.ALLOW);
            }
        }

        ItemContainer storage = inventory.getStorage();
        if (storage != null) {
            storage.setGlobalFilter(FilterType.ALLOW_ALL);
        }

        ItemContainer armor = inventory.getArmor();
        if (armor != null) {
            armor.setGlobalFilter(FilterType.ALLOW_ALL);
        }

        ItemContainer utility = inventory.getUtility();
        if (utility != null) {
            utility.setGlobalFilter(FilterType.ALLOW_ALL);
        }

        ItemContainer tools = inventory.getTools();
        if (tools != null) {
            tools.setGlobalFilter(FilterType.ALLOW_ALL);
        }

        ItemContainer backpack = inventory.getBackpack();
        if (backpack != null) {
            backpack.setGlobalFilter(FilterType.ALLOW_ALL);
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
