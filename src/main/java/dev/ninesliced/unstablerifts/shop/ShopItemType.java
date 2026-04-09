package dev.ninesliced.unstablerifts.shop;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Types of items that can be sold at a shop emplacement.
 */
public enum ShopItemType {
    WEAPON,
    ARMOR,
    AMMO,
    HEAL;

    @Nullable
    public static ShopItemType fromString(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nonnull
    public String getDisplayName() {
        return switch (this) {
            case WEAPON -> "Weapon";
            case ARMOR -> "Armor";
            case AMMO -> "Ammo";
            case HEAL -> "Health";
        };
    }
}
