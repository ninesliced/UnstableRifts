package dev.ninesliced.unstablerifts.guns;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Immutable definition of a weapon loaded from JSON.
 * Holds base stats, category, locked effect, minimum rarity, and spawn weight.
 */
public record WeaponDefinition(@Nonnull String itemId, @Nonnull String displayName, @Nonnull WeaponCategory category,
                               @Nonnull DamageEffect lockedEffect, boolean effectLocked,
                               @Nonnull List<DamageEffect> pelletEffects,
                               @Nonnull WeaponRarity minRarity, @Nonnull WeaponRarity maxRarity, int spawnWeight,
                               float baseDamage, float baseCooldown, int baseMaxAmmo, int baseRange, double baseSpread,
                               int basePellets, float baseKnockback, int baseMobHealth, int baseMobDamage,
                               int baseMobLifetime, int basePrecision) {

    /**
     * Returns the explicit precision override from JSON, or -1 if not set
     * (meaning precision should be derived from spread).
     */
    @Override
    public int basePrecision() {
        return basePrecision;
    }
}
