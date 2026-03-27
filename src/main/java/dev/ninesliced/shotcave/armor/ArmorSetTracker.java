package dev.ninesliced.shotcave.armor;

import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player tracker that monitors equipped armor pieces and caches
 * set completion state. Call {@link #onArmorChanged} whenever the
 * player's armor inventory is modified.
 */
public final class ArmorSetTracker {

    /** setId -> count of equipped pieces for that set */
    private final Map<UUID, Map<String, Integer>> setCountByPlayer = new ConcurrentHashMap<>();

    /**
     * Recalculates set completion for a player based on their current armor slots.
     */
    public void onArmorChanged(@Nonnull UUID playerUuid, @Nonnull ItemContainer armorInv) {
        Map<String, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < 4; slot++) {
            ItemStack stack = armorInv.getItemStack((short) slot);
            if (ItemStack.isEmpty(stack)) continue;
            String setId = ArmorItemMetadata.getSetId(stack);
            if (setId != null) {
                counts.merge(setId, 1, Integer::sum);
            }
        }
        setCountByPlayer.put(playerUuid, counts);
    }

    /**
     * Convenience overload that reads armor from the entity's component.
     */
    public void onArmorChanged(@Nonnull UUID playerUuid, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Armor armorComp = ref.getStore().getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComp != null) {
            onArmorChanged(playerUuid, armorComp.getInventory());
        }
    }

    @Nonnull
    public Map<String, Integer> getSetCounts(@Nonnull UUID playerUuid) {
        return setCountByPlayer.getOrDefault(playerUuid, Map.of());
    }

    /**
     * Returns the set ability if the player has a complete 4/4 set equipped.
     */
    @Nonnull
    public Optional<ArmorSetAbility> getCompleteSetAbility(@Nonnull UUID playerUuid) {
        Map<String, Integer> counts = setCountByPlayer.get(playerUuid);
        if (counts == null) return Optional.empty();

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= 4) {
                return ArmorDefinitions.getBySetId(entry.getKey()).stream()
                        .findFirst()
                        .map(ArmorDefinition::getSetAbility)
                        .filter(a -> a != ArmorSetAbility.NONE);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the set ID with the most pieces if 2+ are equipped (partial set bonus).
     */
    @Nonnull
    public Optional<String> getPartialSetId(@Nonnull UUID playerUuid) {
        Map<String, Integer> counts = setCountByPlayer.get(playerUuid);
        if (counts == null) return Optional.empty();

        String bestSetId = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() >= 2 && entry.getValue() > bestCount) {
                bestSetId = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return Optional.ofNullable(bestSetId);
    }

    /**
     * Returns partial set bonus multiplier: 1.10 (10% bonus) if 2+ pieces match, 1.0 otherwise.
     */
    public float getPartialBonusMultiplier(@Nonnull UUID playerUuid) {
        return getPartialSetId(playerUuid).isPresent() ? 1.10f : 1.0f;
    }

    public void removePlayer(@Nonnull UUID playerUuid) {
        setCountByPlayer.remove(playerUuid);
    }
}
