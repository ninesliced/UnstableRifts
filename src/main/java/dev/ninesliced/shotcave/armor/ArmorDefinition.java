package dev.ninesliced.shotcave.armor;

import dev.ninesliced.shotcave.guns.WeaponRarity;

import javax.annotation.Nonnull;

/**
 * Immutable definition of an armor piece loaded from JSON.
 * Holds base stats, slot type, set identity, rarity bounds, and spawn weight.
 */
public final class ArmorDefinition {

    @Nonnull private final String itemId;
    @Nonnull private final String displayName;
    @Nonnull private final String setId;
    @Nonnull private final ArmorSlotType slotType;
    @Nonnull private final ArmorSetAbility setAbility;
    @Nonnull private final WeaponRarity minRarity;
    @Nonnull private final WeaponRarity maxRarity;
    private final int spawnWeight;
    private final float baseProtection;
    private final float baseKnockback;
    private final float baseSpeedBoost;
    private final float baseCloseDmgReduce;
    private final float baseFarDmgReduce;
    private final float baseSpikeDamage;
    private final float baseLifeBoost;

    public ArmorDefinition(@Nonnull String itemId,
                           @Nonnull String displayName,
                           @Nonnull String setId,
                           @Nonnull ArmorSlotType slotType,
                           @Nonnull ArmorSetAbility setAbility,
                           @Nonnull WeaponRarity minRarity,
                           @Nonnull WeaponRarity maxRarity,
                           int spawnWeight,
                           float baseProtection,
                           float baseKnockback,
                           float baseSpeedBoost,
                           float baseCloseDmgReduce,
                           float baseFarDmgReduce,
                           float baseSpikeDamage,
                           float baseLifeBoost) {
        this.itemId = itemId;
        this.displayName = displayName;
        this.setId = setId;
        this.slotType = slotType;
        this.setAbility = setAbility;
        this.minRarity = minRarity;
        this.maxRarity = maxRarity;
        this.spawnWeight = spawnWeight;
        this.baseProtection = baseProtection;
        this.baseKnockback = baseKnockback;
        this.baseSpeedBoost = baseSpeedBoost;
        this.baseCloseDmgReduce = baseCloseDmgReduce;
        this.baseFarDmgReduce = baseFarDmgReduce;
        this.baseSpikeDamage = baseSpikeDamage;
        this.baseLifeBoost = baseLifeBoost;
    }

    @Nonnull public String getItemId() { return itemId; }
    @Nonnull public String getDisplayName() { return displayName; }
    @Nonnull public String getSetId() { return setId; }
    @Nonnull public ArmorSlotType getSlotType() { return slotType; }
    @Nonnull public ArmorSetAbility getSetAbility() { return setAbility; }
    @Nonnull public WeaponRarity getMinRarity() { return minRarity; }
    @Nonnull public WeaponRarity getMaxRarity() { return maxRarity; }
    public int getSpawnWeight() { return spawnWeight; }
    public float getBaseProtection() { return baseProtection; }
    public float getBaseKnockback() { return baseKnockback; }
    public float getBaseSpeedBoost() { return baseSpeedBoost; }
    public float getBaseCloseDmgReduce() { return baseCloseDmgReduce; }
    public float getBaseFarDmgReduce() { return baseFarDmgReduce; }
    public float getBaseSpikeDamage() { return baseSpikeDamage; }
    public float getBaseLifeBoost() { return baseLifeBoost; }
}
