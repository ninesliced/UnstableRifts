package dev.ninesliced.shotcave.guns;

import javax.annotation.Nonnull;

public enum WeaponCategory {
    LASER,
    BULLET,
    SUMMONING,
    MELEE;

    @Nonnull
    public static WeaponCategory fromOrdinal(int ordinal) {
        WeaponCategory[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return LASER;
        }
        return values[ordinal];
    }

    @Nonnull
    public static WeaponCategory fromString(@Nonnull String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return LASER;
        }
    }
}
