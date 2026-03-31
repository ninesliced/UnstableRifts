package dev.ninesliced.unstablerifts.guns;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

public enum WeaponRarity {
    BASIC(0.45, 0.00, 0, null, "#c9d2dd", "Common", 0.0f),
    UNCOMMON(0.25, 0.05, 1, "Drop_Uncommon", "#3e9049", "Uncommon", 0.05f),
    RARE(0.15, 0.10, 2, "Drop_Rare", "#2770b7", "Rare", 0.1f),
    EPIC(0.08, 0.20, 3, "Drop_Epic", "#8b339e", "Epic", 0.2f),
    LEGENDARY(0.05, 0.50, 4, "Drop_Legendary", "#bb8a2c", "Legendary", 0.35f),
    UNIQUE(0.02, 1.00, 5, "Drop_Unique", "#bb2f2c", "Developer", 0.5f);

    private final double spawnChance;
    private final double effectChance;
    private final int modifierCount;
    @Nullable
    private final String glowEffectId;
    @Nonnull
    private final String colorHex;
    /**
     * Hytale ItemQuality asset name — resolved at runtime via {@code ItemQuality.getAssetMap()}.
     */
    @Nonnull
    private final String qualityName;
    /**
     * Extra seconds added to effect DoT duration for this rarity tier.
     */
    private final float effectDurationBonus;

    WeaponRarity(double spawnChance, double effectChance, int modifierCount,
                 @Nullable String glowEffectId, @Nonnull String colorHex, @Nonnull String qualityName,
                 float effectDurationBonus) {
        this.spawnChance = spawnChance;
        this.effectChance = effectChance;
        this.modifierCount = modifierCount;
        this.glowEffectId = glowEffectId;
        this.colorHex = colorHex;
        this.qualityName = qualityName;
        this.effectDurationBonus = effectDurationBonus;
    }

    /**
     * Rolls a random rarity using weighted probabilities, clamped to the given minimum.
     * Probabilities above the minimum are renormalized so they still sum to 1.
     */
    @Nonnull
    public static WeaponRarity roll(@Nonnull WeaponRarity minimum) {
        WeaponRarity[] values = values();
        int minOrdinal = minimum.ordinal();

        double totalWeight = 0.0;
        for (WeaponRarity r : values) {
            if (r.ordinal() >= minOrdinal) {
                totalWeight += r.spawnChance;
            }
        }

        if (totalWeight <= 0.0) {
            return minimum;
        }

        double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (WeaponRarity r : values) {
            if (r.ordinal() >= minOrdinal) {
                cumulative += r.spawnChance;
                if (roll < cumulative) {
                    return r;
                }
            }
        }

        return values[values.length - 1];
    }

    @Nonnull
    public static WeaponRarity fromOrdinal(int ordinal) {
        WeaponRarity[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return BASIC;
        }
        return values[ordinal];
    }

    @Nonnull
    public static WeaponRarity fromString(@Nonnull String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BASIC;
        }
    }

    public double getSpawnChance() {
        return spawnChance;
    }

    public double getEffectChance() {
        return effectChance;
    }

    public int getModifierCount() {
        return modifierCount;
    }

    @Nullable
    public String getGlowEffectId() {
        return glowEffectId;
    }

    @Nonnull
    public String getColorHex() {
        return colorHex;
    }

    /**
     * Returns the Hytale {@link com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality}
     * asset name (e.g. "Common", "Rare", "Epic", "Legendary").
     * Resolved at runtime via {@code ItemQuality.getAssetMap().getIndexOrDefault(...)}.
     */
    @Nonnull
    public String getQualityName() {
        return qualityName;
    }

    /**
     * Returns the extra seconds added to effect DoT duration for this rarity tier.
     */
    public float getEffectDurationBonus() {
        return effectDurationBonus;
    }
}
