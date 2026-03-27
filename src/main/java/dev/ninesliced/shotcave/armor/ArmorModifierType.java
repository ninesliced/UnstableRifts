package dev.ninesliced.shotcave.armor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public enum ArmorModifierType {
    // Core stats — applicable to specific slot types
    PROTECTION(0.05, 0.20, null, "Protection"),
    CLOSE_DMG_REDUCE(0.05, 0.15, EnumSet.of(ArmorSlotType.CHEST, ArmorSlotType.ARMS), "Close Def"),
    FAR_DMG_REDUCE(0.05, 0.15, EnumSet.of(ArmorSlotType.CHEST, ArmorSlotType.ARMS, ArmorSlotType.HEAD), "Far Def"),
    KNOCKBACK_ENEMIES(0.10, 0.30, EnumSet.of(ArmorSlotType.CHEST, ArmorSlotType.ARMS), "Knockback"),
    SPIKE_DAMAGE(0.05, 0.15, EnumSet.of(ArmorSlotType.CHEST, ArmorSlotType.ARMS), "Spike Dmg"),
    SPEED_BOOST(0.05, 0.15, EnumSet.of(ArmorSlotType.LEGS), "Speed"),
    LIFE_BOOST(0.10, 0.30, EnumSet.of(ArmorSlotType.CHEST), "Max HP"),

    // Cross-system synergy modifiers
    AMMO_CAPACITY(0.05, 0.15, null, "Ammo Cap"),
    ROLL_DISTANCE(0.10, 0.25, EnumSet.of(ArmorSlotType.LEGS), "Roll Dist"),
    REVIVE_SPEED(0.10, 0.20, EnumSet.of(ArmorSlotType.HEAD, ArmorSlotType.CHEST), "Revive Spd"),
    COIN_MAGNET(0.20, 0.40, EnumSet.of(ArmorSlotType.LEGS), "Coin Range"),
    EFFECT_RESISTANCE(0.10, 0.25, EnumSet.of(ArmorSlotType.HEAD), "Effect Res");

    private final double minValue;
    private final double maxValue;
    @Nullable
    private final EnumSet<ArmorSlotType> applicableSlots;
    @Nonnull
    private final String displayName;

    ArmorModifierType(double minValue, double maxValue,
                      @Nullable EnumSet<ArmorSlotType> applicableSlots,
                      @Nonnull String displayName) {
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.applicableSlots = applicableSlots;
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

    public boolean appliesTo(@Nonnull ArmorSlotType slotType) {
        return applicableSlots == null || applicableSlots.contains(slotType);
    }

    @Nonnull
    public static List<ArmorModifierType> getApplicable(@Nonnull ArmorSlotType slotType) {
        List<ArmorModifierType> result = new ArrayList<>();
        for (ArmorModifierType type : values()) {
            if (type.appliesTo(slotType)) {
                result.add(type);
            }
        }
        return result;
    }

    @Nonnull
    public static ArmorModifierType fromOrdinal(int ordinal) {
        ArmorModifierType[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return PROTECTION;
        }
        return values[ordinal];
    }
}
