package dev.ninesliced.unstablerifts.armor;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Static helper to aggregate total armor stats from all equipped pieces.
 * Called by the damage pipeline, roll system, revive system, etc.
 */
public final class ArmorStatResolver {

    private ArmorStatResolver() {
    }

    /**
     * Sums base protection across all equipped armor pieces.
     */
    public static float getTotalProtection(@Nonnull List<ItemStack> armorSlots) {
        float total = 0;
        for (ItemStack stack : armorSlots) {
            if (ItemStack.isEmpty(stack)) continue;
            ArmorDefinition def = ArmorDefinitions.getById(stack.getItemId());
            if (def != null) {
                total += def.baseProtection();
            }
        }
        return total;
    }

    /**
     * Sums a specific modifier bonus across all equipped armor pieces' BSON metadata.
     */
    public static double getTotalModifierBonus(@Nonnull List<ItemStack> armorSlots,
                                               @Nonnull ArmorModifierType type) {
        double total = 0;
        for (ItemStack stack : armorSlots) {
            if (ItemStack.isEmpty(stack)) continue;
            total += ArmorItemMetadata.getModifierBonus(stack, type);
        }
        return total;
    }

    public static double getTotalModifierBonus(@Nonnull ItemContainer armorInventory,
                                               @Nonnull ArmorModifierType type) {
        double total = 0;
        for (int slot = 0; slot < armorInventory.getCapacity(); slot++) {
            ItemStack stack = armorInventory.getItemStack((short) slot);
            if (ItemStack.isEmpty(stack)) continue;
            total += ArmorItemMetadata.getModifierBonus(stack, type);
        }
        return total;
    }

    public static double getTotalAmmoCapacityBonus(@Nonnull ItemContainer armorInventory) {
        return getTotalModifierBonus(armorInventory, ArmorModifierType.AMMO_CAPACITY);
    }

    /**
     * Calculates protection as a damage reduction fraction using diminishing returns.
     * Formula: protection / (protection + 50)
     * E.g., 50 protection = 50% reduction, 100 protection = 66.7% reduction.
     */
    public static float getProtectionReduction(float totalProtection) {
        if (totalProtection <= 0) return 0.0f;
        return totalProtection / (totalProtection + 50.0f);
    }

    /**
     * Returns the close damage reduction from base stats summed across all armor pieces.
     */
    public static float getTotalCloseDmgReduce(@Nonnull List<ItemStack> armorSlots) {
        float total = 0;
        for (ItemStack stack : armorSlots) {
            if (ItemStack.isEmpty(stack)) continue;
            ArmorDefinition def = ArmorDefinitions.getById(stack.getItemId());
            if (def != null) {
                total += def.baseCloseDmgReduce();
            }
        }
        return total;
    }

    /**
     * Returns the far damage reduction from base stats summed across all armor pieces.
     */
    public static float getTotalFarDmgReduce(@Nonnull List<ItemStack> armorSlots) {
        float total = 0;
        for (ItemStack stack : armorSlots) {
            if (ItemStack.isEmpty(stack)) continue;
            ArmorDefinition def = ArmorDefinitions.getById(stack.getItemId());
            if (def != null) {
                total += def.baseFarDmgReduce();
            }
        }
        return total;
    }

    /**
     * Returns the total spike damage from base stats summed across all armor pieces.
     */
    public static float getTotalSpikeDamage(@Nonnull List<ItemStack> armorSlots) {
        float total = 0;
        for (ItemStack stack : armorSlots) {
            if (ItemStack.isEmpty(stack)) continue;
            ArmorDefinition def = ArmorDefinitions.getById(stack.getItemId());
            if (def != null) {
                total += def.baseSpikeDamage();
            }
        }
        return total;
    }

    /**
     * Returns the total life boost from base stats + modifier bonuses.
     */
    public static float getTotalLifeBoost(@Nonnull List<ItemStack> armorSlots) {
        float base = 0;
        for (ItemStack stack : armorSlots) {
            if (ItemStack.isEmpty(stack)) continue;
            ArmorDefinition def = ArmorDefinitions.getById(stack.getItemId());
            if (def != null) {
                base += def.baseLifeBoost();
            }
        }
        double modBonus = getTotalModifierBonus(armorSlots, ArmorModifierType.LIFE_BOOST);
        return base + (float) (base * modBonus);
    }

    /**
     * Returns the total speed boost from base stats + modifier bonuses.
     */
    public static float getTotalSpeedBoost(@Nonnull List<ItemStack> armorSlots) {
        float base = 0;
        for (ItemStack stack : armorSlots) {
            if (ItemStack.isEmpty(stack)) continue;
            ArmorDefinition def = ArmorDefinitions.getById(stack.getItemId());
            if (def != null) {
                base += def.baseSpeedBoost();
            }
        }
        double modBonus = getTotalModifierBonus(armorSlots, ArmorModifierType.SPEED_BOOST);
        return base + (float) modBonus;
    }
}
