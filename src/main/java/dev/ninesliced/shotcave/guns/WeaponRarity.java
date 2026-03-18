package dev.ninesliced.shotcave.guns;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

public enum WeaponRarity {
    BASIC(0.60, 0.00, 0, null, "#c9d2dd"),
    RARE(0.20, 0.05, 1, "Drop_Rare", "#2770b7"),
    EPIC(0.10, 0.20, 2, "Drop_Epic", "#8b339e"),
    LEGENDARY(0.07, 0.50, 3, "Drop_Legendary", "#bb8a2c"),
    UNIQUE(0.03, 1.00, 4, "Drop_Legendary", "#bb2f2c");

    private final double spawnChance;
    private final double effectChance;
    private final int modifierCount;
    @Nullable
    private final String glowEffectId;
    @Nonnull
    private final String colorHex;

    WeaponRarity(double spawnChance, double effectChance, int modifierCount,
                 @Nullable String glowEffectId, @Nonnull String colorHex) {
        this.spawnChance = spawnChance;
        this.effectChance = effectChance;
        this.modifierCount = modifierCount;
        this.glowEffectId = glowEffectId;
        this.colorHex = colorHex;
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
}
