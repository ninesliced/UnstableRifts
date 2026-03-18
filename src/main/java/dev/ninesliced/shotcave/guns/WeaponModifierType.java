package dev.ninesliced.shotcave.guns;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public enum WeaponModifierType {
    // General (all weapon types)
    MAX_BULLETS(0.10, 0.30, null, "Max Ammo"),
    ATTACK_SPEED(0.20, 0.20, null, "Speed"),
    ADDITIONAL_BULLETS(1.0, 2.0, EnumSet.of(WeaponCategory.LASER, WeaponCategory.BULLET), "Pellets"),

    // Summoning only
    MOB_HEALTH(0.20, 0.50, EnumSet.of(WeaponCategory.SUMMONING), "Mob HP"),
    MOB_LIFETIME(0.20, 0.50, EnumSet.of(WeaponCategory.SUMMONING), "Mob Life"),
    MOB_DAMAGE(0.20, 0.50, EnumSet.of(WeaponCategory.SUMMONING), "Mob Dmg"),

    // Laser + Bullet
    WEAPON_DAMAGE(0.10, 0.30, EnumSet.of(WeaponCategory.LASER, WeaponCategory.BULLET), "Damage"),
    PRECISION(0.30, 0.50, EnumSet.of(WeaponCategory.LASER, WeaponCategory.BULLET), "Precision"),
    KNOCKBACK(0.10, 0.20, EnumSet.of(WeaponCategory.LASER, WeaponCategory.BULLET), "Knockback"),
    MAX_RANGE(0.30, 0.50, EnumSet.of(WeaponCategory.LASER, WeaponCategory.BULLET), "Range");

    private final double minValue;
    private final double maxValue;
    @Nullable
    private final EnumSet<WeaponCategory> applicableCategories;
    @Nonnull
    private final String displayName;

    WeaponModifierType(double minValue, double maxValue,
                       @Nullable EnumSet<WeaponCategory> applicableCategories,
                       @Nonnull String displayName) {
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.applicableCategories = applicableCategories;
        this.displayName = displayName;
    }

    public double getMinValue() {
        return minValue;
    }

    public double getMaxValue() {
        return maxValue;
    }

    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    public boolean appliesTo(@Nonnull WeaponCategory category) {
        return applicableCategories == null || applicableCategories.contains(category);
    }

    /**
     * Returns all modifier types applicable to the given weapon category
     * (general modifiers + category-specific modifiers).
     */
    @Nonnull
    public static List<WeaponModifierType> getApplicable(@Nonnull WeaponCategory category) {
        List<WeaponModifierType> result = new ArrayList<>();
        for (WeaponModifierType type : values()) {
            if (type.appliesTo(category)) {
                result.add(type);
            }
        }
        return result;
    }

    @Nonnull
    public static WeaponModifierType fromOrdinal(int ordinal) {
        WeaponModifierType[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return MAX_BULLETS;
        }
        return values[ordinal];
    }
}
