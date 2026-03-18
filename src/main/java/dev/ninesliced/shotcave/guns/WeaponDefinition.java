package dev.ninesliced.shotcave.guns;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable definition of a weapon loaded from JSON.
 * Holds base stats, category, locked effect, minimum rarity, and spawn weight.
 */
public final class WeaponDefinition {

    @Nonnull private final String itemId;
    @Nonnull private final String displayName;
    @Nonnull private final WeaponCategory category;
    @Nonnull private final DamageEffect lockedEffect;
    @Nonnull private final WeaponRarity minRarity;
    private final int spawnWeight;
    private final float baseDamage;
    private final float baseCooldown;
    private final int baseMaxAmmo;
    private final int baseRange;
    private final double baseSpread;
    private final int basePellets;
    private final float baseKnockback;
    private final int baseMobHealth;
    private final int baseMobDamage;
    private final int baseMobLifetime;

    public WeaponDefinition(@Nonnull String itemId,
                            @Nonnull String displayName,
                            @Nonnull WeaponCategory category,
                            @Nonnull DamageEffect lockedEffect,
                            @Nonnull WeaponRarity minRarity,
                            int spawnWeight,
                            float baseDamage,
                            float baseCooldown,
                            int baseMaxAmmo,
                            int baseRange,
                            double baseSpread,
                            int basePellets,
                            float baseKnockback,
                            int baseMobHealth,
                            int baseMobDamage,
                            int baseMobLifetime) {
        this.itemId = itemId;
        this.displayName = displayName;
        this.category = category;
        this.lockedEffect = lockedEffect;
        this.minRarity = minRarity;
        this.spawnWeight = spawnWeight;
        this.baseDamage = baseDamage;
        this.baseCooldown = baseCooldown;
        this.baseMaxAmmo = baseMaxAmmo;
        this.baseRange = baseRange;
        this.baseSpread = baseSpread;
        this.basePellets = basePellets;
        this.baseKnockback = baseKnockback;
        this.baseMobHealth = baseMobHealth;
        this.baseMobDamage = baseMobDamage;
        this.baseMobLifetime = baseMobLifetime;
    }

    @Nonnull
    public String getItemId() {
        return itemId;
    }

    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    @Nonnull
    public WeaponCategory getCategory() {
        return category;
    }

    @Nonnull
    public DamageEffect getLockedEffect() {
        return lockedEffect;
    }

    @Nonnull
    public WeaponRarity getMinRarity() {
        return minRarity;
    }

    public int getSpawnWeight() {
        return spawnWeight;
    }

    public float getBaseDamage() {
        return baseDamage;
    }

    public float getBaseCooldown() {
        return baseCooldown;
    }

    public int getBaseMaxAmmo() {
        return baseMaxAmmo;
    }

    public int getBaseRange() {
        return baseRange;
    }

    public double getBaseSpread() {
        return baseSpread;
    }

    public int getBasePellets() {
        return basePellets;
    }

    public float getBaseKnockback() {
        return baseKnockback;
    }

    public int getBaseMobHealth() {
        return baseMobHealth;
    }

    public int getBaseMobDamage() {
        return baseMobDamage;
    }

    public int getBaseMobLifetime() {
        return baseMobLifetime;
    }
}
