package dev.ninesliced.unstablerifts.armor;

import javax.annotation.Nonnull;

public enum ArmorSetAbility {
    NONE("None", 0, "#c9d2dd"),
    BERSERKER("Berserker", 10, "#d4534a"),
    REGENERATION("Regeneration", 10, "#3e9049"),
    GUARDIAN("Guardian", 8, "#2770b7"),
    PURIFICATION("Purification", 20, "#8b339e"),
    SWIFTNESS("Swiftness", 12, "#bb8a2c"),
    WARDEN("Warden", 10, "#bb2f2c");

    @Nonnull
    private final String displayName;
    private final int durationSeconds;
    @Nonnull
    private final String colorHex;

    ArmorSetAbility(@Nonnull String displayName, int durationSeconds, @Nonnull String colorHex) {
        this.displayName = displayName;
        this.durationSeconds = durationSeconds;
        this.colorHex = colorHex;
    }

    @Nonnull
    public static ArmorSetAbility fromOrdinal(int ordinal) {
        ArmorSetAbility[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return NONE;
        }
        return values[ordinal];
    }

    @Nonnull
    public static ArmorSetAbility fromString(@Nonnull String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    /**
     * Duration in game ticks (20 TPS).
     */
    public int getDurationTicks() {
        return durationSeconds * 20;
    }

    @Nonnull
    public String getColorHex() {
        return colorHex;
    }
}
