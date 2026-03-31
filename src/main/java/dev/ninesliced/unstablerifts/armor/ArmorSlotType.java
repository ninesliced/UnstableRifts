package dev.ninesliced.unstablerifts.armor;

import javax.annotation.Nonnull;

public enum ArmorSlotType {
    HEAD(0),
    CHEST(1),
    ARMS(2),
    LEGS(3);

    private final int slotIndex;

    ArmorSlotType(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    @Nonnull
    public static ArmorSlotType fromOrdinal(int ordinal) {
        ArmorSlotType[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return HEAD;
        }
        return values[ordinal];
    }

    @Nonnull
    public static ArmorSlotType fromString(@Nonnull String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return HEAD;
        }
    }

    @Nonnull
    public static ArmorSlotType fromSlotIndex(int slotIndex) {
        for (ArmorSlotType type : values()) {
            if (type.slotIndex == slotIndex) {
                return type;
            }
        }
        return HEAD;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    /**
     * Returns the Hytale-native armor slot name for this slot.
     * Hytale slots: Head(0), Chest(1), Hands(2), Legs(3).
     * Our LEGS maps to Hytale Hands (slot 2) and
     * BOOTS maps to Hytale Legs (slot 3).
     */
    @Nonnull
    public String getHytaleSlotName() {
        return switch (this) {
            case HEAD -> "Head";
            case CHEST -> "Chest";
            case ARMS -> "Hands";
            case LEGS -> "Legs";
        };
    }
}
