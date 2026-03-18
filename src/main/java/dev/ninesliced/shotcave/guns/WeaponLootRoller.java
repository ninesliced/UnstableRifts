package dev.ninesliced.shotcave.guns;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolls a random weapon with rarity, optional damage effect, and modifiers,
 * then returns a fully stamped {@link ItemStack} with BSON metadata.
 */
public final class WeaponLootRoller {

    private WeaponLootRoller() {}

    /**
     * Rolls a rarity first, then picks a random weapon available for that rarity
     * from the weighted pool, rolls effect/modifiers, and returns a stamped ItemStack.
     */
    @Nonnull
    public static ItemStack rollRandom() {
        return rollRandom(null);
    }

    /**
     * Rolls a random weapon. If {@code forcedRarity} is non-null the rarity is fixed
     * instead of being rolled from the weighted table.
     */
    @Nonnull
    public static ItemStack rollRandom(@Nullable WeaponRarity forcedRarity) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Step 1: Roll or use forced rarity
        WeaponRarity rarity = forcedRarity != null ? forcedRarity : WeaponRarity.roll(WeaponRarity.BASIC);

        // Step 2: Build weighted pool of weapons eligible for this rarity (minRarity <= rolled)
        List<WeaponDefinition> eligible = new ArrayList<>();
        for (WeaponDefinition def : WeaponDefinitions.getAll()) {
            if (def.getMinRarity().ordinal() <= rarity.ordinal()) {
                for (int i = 0; i < def.getSpawnWeight(); i++) {
                    eligible.add(def);
                }
            }
        }

        if (eligible.isEmpty()) {
            // Fallback: pick any weapon and force the rarity
            List<WeaponDefinition> pool = WeaponDefinitions.getWeightedPool();
            if (pool.isEmpty()) {
                throw new IllegalStateException("No weapons registered in WeaponDefinitions");
            }
            WeaponDefinition def = pool.get(rng.nextInt(pool.size()));
            return rollForWithRarity(def, rarity);
        }

        WeaponDefinition def = eligible.get(rng.nextInt(eligible.size()));
        return rollForWithRarity(def, rarity);
    }

    /**
     * Rolls effect/modifiers for a specific weapon definition with a pre-determined rarity.
     */
    @Nonnull
    private static ItemStack rollForWithRarity(@Nonnull WeaponDefinition def, @Nonnull WeaponRarity rarity) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        DamageEffect effect = rollEffect(def, rarity, rng);
        List<WeaponModifier> modifiers = rollModifiers(def, rarity, rng);
        return stamp(def.getItemId(), rarity, effect, modifiers);
    }

    /**
     * Rolls rarity/effect/modifiers for a specific weapon definition.
     */
    @Nonnull
    public static ItemStack rollFor(@Nonnull WeaponDefinition def) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Roll rarity (clamped to weapon's minimum)
        WeaponRarity rarity = WeaponRarity.roll(def.getMinRarity());

        // Roll damage effect
        DamageEffect effect = rollEffect(def, rarity, rng);

        // Roll modifiers
        List<WeaponModifier> modifiers = rollModifiers(def, rarity, rng);

        // Stamp the ItemStack
        return stamp(def.getItemId(), rarity, effect, modifiers);
    }

    /**
     * Creates a stamped ItemStack with rarity/effect/modifiers baked into BSON metadata.
     */
    @Nonnull
    public static ItemStack stamp(@Nonnull String itemId,
                                   @Nonnull WeaponRarity rarity,
                                   @Nonnull DamageEffect effect,
                                   @Nonnull List<WeaponModifier> modifiers) {
        ItemStack stack = new ItemStack(itemId, 1);
        stack = GunItemMetadata.setRarity(stack, rarity);
        stack = GunItemMetadata.setEffect(stack, effect);
        stack = GunItemMetadata.setModifiers(stack, modifiers);
        return stack;
    }

    @Nonnull
    private static DamageEffect rollEffect(@Nonnull WeaponDefinition def,
                                            @Nonnull WeaponRarity rarity,
                                            @Nonnull ThreadLocalRandom rng) {
        // Locked effects always apply
        if (def.getLockedEffect() != DamageEffect.NONE) {
            return def.getLockedEffect();
        }

        // Roll based on rarity's effect chance
        if (rng.nextDouble() < rarity.getEffectChance()) {
            // Pick a random rollable effect (acid, fire, ice only for random rolls)
            DamageEffect[] rollable = { DamageEffect.ACID, DamageEffect.FIRE, DamageEffect.ICE };
            return rollable[rng.nextInt(rollable.length)];
        }

        return DamageEffect.NONE;
    }

    @Nonnull
    private static List<WeaponModifier> rollModifiers(@Nonnull WeaponDefinition definition,
                                                       @Nonnull WeaponRarity rarity,
                                                       @Nonnull ThreadLocalRandom rng) {
        int count = rarity.getModifierCount();
        if (count <= 0) {
            return List.of();
        }

        List<WeaponModifierType> applicable = WeaponModifierType.getApplicable(definition.getCategory());
        if (definition.getBaseSpread() <= 0.05d) {
            applicable.remove(WeaponModifierType.PRECISION);
        }
        if (definition.getBasePellets() <= 1) {
            applicable.remove(WeaponModifierType.ADDITIONAL_BULLETS);
        }
        if (definition.getBaseKnockback() <= 0.001d) {
            applicable.remove(WeaponModifierType.KNOCKBACK);
        }
        if (applicable.isEmpty()) {
            return List.of();
        }

        List<WeaponModifier> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            WeaponModifierType type = applicable.get(rng.nextInt(applicable.size()));
            double value = type.getMinValue() + rng.nextDouble() * (type.getMaxValue() - type.getMinValue());
            // Round to 2 decimal places
            value = Math.round(value * 100.0) / 100.0;
            result.add(new WeaponModifier(type, value));
        }
        return result;
    }
}
