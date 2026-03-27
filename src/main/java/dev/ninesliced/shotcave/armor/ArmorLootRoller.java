package dev.ninesliced.shotcave.armor;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import dev.ninesliced.shotcave.guns.WeaponRarity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolls a random armor piece with rarity and modifiers,
 * then returns a fully stamped {@link ItemStack} with BSON metadata.
 */
public final class ArmorLootRoller {

    private ArmorLootRoller() {}

    @Nonnull
    public static ItemStack rollRandom() {
        return rollRandom(null);
    }

    @Nonnull
    public static ItemStack rollRandom(@Nullable WeaponRarity forcedRarity) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        WeaponRarity rarity = forcedRarity != null ? forcedRarity : WeaponRarity.roll(WeaponRarity.BASIC);

        List<ArmorDefinition> eligible = new ArrayList<>();
        for (ArmorDefinition def : ArmorDefinitions.getAll()) {
            if (def.getMinRarity().ordinal() <= rarity.ordinal()) {
                for (int i = 0; i < def.getSpawnWeight(); i++) {
                    eligible.add(def);
                }
            }
        }

        if (eligible.isEmpty()) {
            List<ArmorDefinition> pool = ArmorDefinitions.getWeightedPool();
            if (pool.isEmpty()) {
                throw new IllegalStateException("No armors registered in ArmorDefinitions");
            }
            ArmorDefinition def = pool.get(rng.nextInt(pool.size()));
            return rollForWithRarity(def, rarity);
        }

        ArmorDefinition def = eligible.get(rng.nextInt(eligible.size()));
        return rollForWithRarity(def, rarity);
    }

    @Nonnull
    private static ItemStack rollForWithRarity(@Nonnull ArmorDefinition def, @Nonnull WeaponRarity rarity) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (rarity.ordinal() > def.getMaxRarity().ordinal()) {
            rarity = def.getMaxRarity();
        }
        List<ArmorModifier> modifiers = rollModifiers(def, rarity, rng);
        return stamp(def.getItemId(), rarity, def.getSetId(), modifiers);
    }

    @Nonnull
    public static ItemStack rollFor(@Nonnull ArmorDefinition def) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        WeaponRarity rarity = WeaponRarity.roll(def.getMinRarity());
        if (rarity.ordinal() > def.getMaxRarity().ordinal()) {
            rarity = def.getMaxRarity();
        }
        List<ArmorModifier> modifiers = rollModifiers(def, rarity, rng);
        return stamp(def.getItemId(), rarity, def.getSetId(), modifiers);
    }

    @Nonnull
    public static ItemStack stamp(@Nonnull String itemId,
                                   @Nonnull WeaponRarity rarity,
                                   @Nonnull String setId,
                                   @Nonnull List<ArmorModifier> modifiers) {
        ItemStack stack = new ItemStack(itemId, 1);
        stack = ArmorItemMetadata.setRarity(stack, rarity);
        stack = ArmorItemMetadata.setSetId(stack, setId);
        stack = ArmorItemMetadata.setModifiers(stack, modifiers);
        return stack;
    }

    @Nonnull
    private static List<ArmorModifier> rollModifiers(@Nonnull ArmorDefinition definition,
                                                      @Nonnull WeaponRarity rarity,
                                                      @Nonnull ThreadLocalRandom rng) {
        int count = rarity.getModifierCount();
        if (count <= 0) {
            return List.of();
        }

        List<ArmorModifierType> applicable = ArmorModifierType.getApplicable(definition.getSlotType());
        if (applicable.isEmpty()) {
            return List.of();
        }

        List<ArmorModifier> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ArmorModifierType type = applicable.get(rng.nextInt(applicable.size()));
            double value = type.getMinValue() + rng.nextDouble() * (type.getMaxValue() - type.getMinValue());
            value = Math.round(value * 100.0) / 100.0;
            result.add(new ArmorModifier(type, value));
        }
        return result;
    }

    /**
     * Rolls an armor piece from a crate with specific rarity bounds and whitelist.
     */
    @Nonnull
    public static ItemStack rollFromCrate(@Nonnull WeaponRarity crateMinRarity,
                                          @Nonnull WeaponRarity crateMaxRarity,
                                          @Nonnull List<String> whitelist) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        WeaponRarity rarity = WeaponRarity.roll(crateMinRarity);
        if (rarity.ordinal() > crateMaxRarity.ordinal()) {
            rarity = crateMaxRarity;
        }

        List<ArmorDefinition> eligible = new ArrayList<>();
        for (ArmorDefinition def : ArmorDefinitions.getAll()) {
            if (whitelist.contains(def.getItemId())
                    && def.getMinRarity().ordinal() <= rarity.ordinal()) {
                for (int i = 0; i < def.getSpawnWeight(); i++) {
                    eligible.add(def);
                }
            }
        }

        if (eligible.isEmpty()) {
            for (ArmorDefinition def : ArmorDefinitions.getAll()) {
                if (whitelist.contains(def.getItemId())) {
                    for (int i = 0; i < def.getSpawnWeight(); i++) {
                        eligible.add(def);
                    }
                }
            }
        }

        if (eligible.isEmpty()) {
            return rollRandom(rarity);
        }

        ArmorDefinition def = eligible.get(rng.nextInt(eligible.size()));
        return rollForWithRarity(def, rarity);
    }
}
