package dev.ninesliced.unstablerifts.armor;

import dev.ninesliced.unstablerifts.guns.WeaponRarity;

import javax.annotation.Nonnull;

/**
 * Immutable definition of an armor piece loaded from JSON.
 * Holds base stats, slot type, set identity, rarity bounds, and spawn weight.
 */
public record ArmorDefinition(@Nonnull String itemId, @Nonnull String displayName, @Nonnull String setId,
                              @Nonnull ArmorSlotType slotType, @Nonnull ArmorSetAbility setAbility,
                              @Nonnull WeaponRarity minRarity, @Nonnull WeaponRarity maxRarity, int spawnWeight,
                              float baseProtection, float baseKnockback, float baseSpeedBoost, float baseCloseDmgReduce,
                              float baseFarDmgReduce, float baseSpikeDamage, float baseLifeBoost) {

}
