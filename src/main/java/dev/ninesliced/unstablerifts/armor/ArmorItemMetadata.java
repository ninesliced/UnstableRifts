package dev.ninesliced.unstablerifts.armor;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import dev.ninesliced.unstablerifts.guns.WeaponRarity;
import org.bson.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ArmorItemMetadata {
    public static final String RARITY_KEY = "SC_ArmorRarity";
    public static final String MODIFIERS_KEY = "SC_ArmorMods";
    public static final String SET_KEY = "SC_ArmorSet";

    private ArmorItemMetadata() {
    }

    // ── Rarity ─────────────────────────────────────────────────────────

    @Nonnull
    public static WeaponRarity getRarity(@Nonnull ItemStack stack) {
        return WeaponRarity.fromOrdinal(getInt(stack, RARITY_KEY, 0));
    }

    @Nonnull
    public static ItemStack setRarity(@Nonnull ItemStack stack, @Nonnull WeaponRarity rarity) {
        return stack.withMetadata(RARITY_KEY, new BsonInt32(rarity.ordinal()));
    }

    // ── Set ID ─────────────────────────────────────────────────────────

    @Nullable
    public static String getSetId(@Nonnull ItemStack stack) {
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null) return null;
        BsonValue val = metadata.get(SET_KEY);
        if (val == null || !val.isString()) return null;
        return val.asString().getValue();
    }

    @Nonnull
    public static ItemStack setSetId(@Nonnull ItemStack stack, @Nonnull String setId) {
        return stack.withMetadata(SET_KEY, new BsonString(setId));
    }

    // ── Modifiers ──────────────────────────────────────────────────────

    @Nonnull
    public static List<ArmorModifier> getModifiers(@Nonnull ItemStack stack) {
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null) return Collections.emptyList();
        BsonValue val = metadata.get(MODIFIERS_KEY);
        if (val == null || !val.isArray()) return Collections.emptyList();
        BsonArray arr = val.asArray();
        List<ArmorModifier> mods = new ArrayList<>(arr.size());
        for (BsonValue entry : arr) {
            if (entry.isDocument()) {
                mods.add(ArmorModifier.fromBsonDocument(entry.asDocument()));
            }
        }
        return mods;
    }

    @Nonnull
    public static ItemStack setModifiers(@Nonnull ItemStack stack, @Nonnull List<ArmorModifier> modifiers) {
        BsonArray arr = new BsonArray();
        for (ArmorModifier mod : modifiers) {
            arr.add(mod.toBsonDocument());
        }
        return stack.withMetadata(MODIFIERS_KEY, arr);
    }

    /**
     * Returns the total modifier bonus for the given type from this armor piece.
     */
    public static double getModifierBonus(@Nonnull ItemStack stack, @Nonnull ArmorModifierType type) {
        double total = 0.0;
        for (ArmorModifier mod : getModifiers(stack)) {
            if (mod.type() == type) {
                total += mod.rolledValue();
            }
        }
        return total;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static int getInt(@Nonnull ItemStack stack, @Nonnull String key, int fallback) {
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null) return fallback;
        BsonValue value = metadata.get(key);
        if (value == null || !value.isNumber()) return fallback;
        return value.asNumber().intValue();
    }
}
