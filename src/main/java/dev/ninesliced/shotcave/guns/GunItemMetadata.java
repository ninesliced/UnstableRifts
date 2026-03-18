package dev.ninesliced.shotcave.guns;

import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GunItemMetadata {
    public static final String AMMO_KEY = "Shotcave_Ammo";
    public static final String MAX_AMMO_KEY = "Shotcave_MaxAmmo";
    public static final String RARITY_KEY = "SC_Rarity";
    public static final String EFFECT_KEY = "SC_Effect";
    public static final String MODIFIERS_KEY = "SC_Mods";

    /**
     * ThreadLocal damage multiplier set before forking to the damage interaction.
     * ShotcaveDamageCalculator reads this since it has no InteractionContext access.
     */
    public static final ThreadLocal<Double> DAMAGE_MULTIPLIER = ThreadLocal.withInitial(() -> 1.0);

    private GunItemMetadata() {
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

    // ── Damage Effect ──────────────────────────────────────────────────

    @Nonnull
    public static DamageEffect getEffect(@Nonnull ItemStack stack) {
        return DamageEffect.fromOrdinal(getInt(stack, EFFECT_KEY, 0));
    }

    @Nonnull
    public static ItemStack setEffect(@Nonnull ItemStack stack, @Nonnull DamageEffect effect) {
        return stack.withMetadata(EFFECT_KEY, new BsonInt32(effect.ordinal()));
    }

    // ── Modifiers ──────────────────────────────────────────────────────

    @Nonnull
    public static List<WeaponModifier> getModifiers(@Nonnull ItemStack stack) {
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null) {
            return Collections.emptyList();
        }
        BsonValue val = metadata.get(MODIFIERS_KEY);
        if (val == null || !val.isArray()) {
            return Collections.emptyList();
        }
        BsonArray arr = val.asArray();
        List<WeaponModifier> mods = new ArrayList<>(arr.size());
        for (BsonValue entry : arr) {
            if (entry.isDocument()) {
                mods.add(WeaponModifier.fromBsonDocument(entry.asDocument()));
            }
        }
        return mods;
    }

    @Nonnull
    public static ItemStack setModifiers(@Nonnull ItemStack stack, @Nonnull List<WeaponModifier> modifiers) {
        BsonArray arr = new BsonArray();
        for (WeaponModifier mod : modifiers) {
            arr.add(mod.toBsonDocument());
        }
        return stack.withMetadata(MODIFIERS_KEY, arr);
    }

    /**
     * Returns the total modifier multiplier for the given type from the held item.
     * e.g., if two WEAPON_DAMAGE modifiers rolled +0.15 and +0.25, returns 0.40.
     */
    public static double getModifierBonus(@Nonnull ItemStack stack, @Nonnull WeaponModifierType type) {
        double total = 0.0;
        for (WeaponModifier mod : getModifiers(stack)) {
            if (mod.type() == type) {
                total += mod.rolledValue();
            }
        }
        return total;
    }

    // ── Ammo ───────────────────────────────────────────────────────────

    public static int getBaseMaxAmmo(@Nonnull ItemStack stack, int fallback) {
        int storedMaxAmmo = getInt(stack, MAX_AMMO_KEY, fallback);
        return storedMaxAmmo > 0 ? storedMaxAmmo : fallback;
    }

    public static int getEffectiveMaxAmmo(@Nonnull ItemStack stack, int baseMaxAmmo) {
        if (baseMaxAmmo <= 0) {
            return baseMaxAmmo;
        }

        double bulletBonus = getModifierBonus(stack, WeaponModifierType.MAX_BULLETS);
        return Math.max(1, (int) Math.round(baseMaxAmmo * (1.0 + bulletBonus)));
    }

    @Nonnull
    public static ItemStack ensureAmmo(@Nonnull ItemStack stack, int maxAmmo) {
        return ensureAmmo(stack, maxAmmo, maxAmmo);
    }

    @Nonnull
    public static ItemStack ensureAmmo(@Nonnull ItemStack stack, int baseMaxAmmo, int effectiveMaxAmmo) {
        ItemStack out = stack;
        int normalizedBaseMaxAmmo = Math.max(1, baseMaxAmmo);
        int normalizedEffectiveMaxAmmo = Math.max(normalizedBaseMaxAmmo, effectiveMaxAmmo);
        int currentMaxAmmo = getInt(out, MAX_AMMO_KEY, normalizedBaseMaxAmmo);
        int currentAmmo = getInt(out, AMMO_KEY, normalizedEffectiveMaxAmmo);

        if (currentMaxAmmo != normalizedBaseMaxAmmo) {
            out = out.withMetadata(MAX_AMMO_KEY, new BsonInt32(normalizedBaseMaxAmmo));
            currentMaxAmmo = normalizedBaseMaxAmmo;
        }
        if (!hasInt(out, AMMO_KEY) || currentAmmo < 0 || currentAmmo > normalizedEffectiveMaxAmmo) {
            int clampedAmmo = Math.max(0, Math.min(currentAmmo, normalizedEffectiveMaxAmmo));
            out = out.withMetadata(AMMO_KEY, new BsonInt32(clampedAmmo));
        }

        return out;
    }

    // ── Low-level helpers ──────────────────────────────────────────────

    public static boolean hasInt(@Nonnull ItemStack stack, @Nonnull String key) {
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null) {
            return false;
        }
        BsonValue value = metadata.get(key);
        return value != null && value.isNumber();
    }

    public static int getInt(@Nonnull ItemStack stack, @Nonnull String key, int fallback) {
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null) {
            return fallback;
        }

        BsonValue value = metadata.get(key);
        if (value == null || !value.isNumber()) {
            return fallback;
        }

        return value.asNumber().intValue();
    }

    @Nonnull
    public static ItemStack setInt(@Nonnull ItemStack stack, @Nonnull String key, int value) {
        return stack.withMetadata(key, new BsonInt32(value));
    }


    @Nonnull
    public static ItemStack applyHeldItem(@Nonnull InteractionContext context, @Nonnull ItemStack updatedStack) {
        ItemStack current = context.getHeldItem();
        if (current == null) {
            return updatedStack;
        }

        ItemContainer container = context.getHeldItemContainer();
        if (container != null) {
            container.replaceItemStackInSlot((short) context.getHeldItemSlot(), current, updatedStack);
        }
        context.setHeldItem(updatedStack);
        return updatedStack;
    }
}
