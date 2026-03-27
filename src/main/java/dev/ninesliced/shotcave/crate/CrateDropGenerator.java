package dev.ninesliced.shotcave.crate;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import dev.ninesliced.shotcave.armor.ArmorLootRoller;
import dev.ninesliced.shotcave.guns.WeaponLootRoller;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Config-driven loot generator for crates. Reads per-crate settings from
 * {@link CrateLootConfig} (loaded from {@code crate_loot.json}).
 */
public final class CrateDropGenerator {

    private static final String COIN_ITEM_ID = "Shotcave_Props_Coin";
    private static final String AMMO_ITEM_ID = "Shotcave_Ammo_Item";
    private static final String HEAL_ITEM_ID = "Shotcave_Heal_Item";

    private CrateDropGenerator() {}

    /** Returns true if the given block type ID is a configured crate. */
    public static boolean isCrate(@Nonnull String blockTypeId) {
        return CrateLootConfig.isCrate(blockTypeId);
    }

    /**
     * Generates drops for a crate block type based on its config entry.
    * Returns coins (always) and optionally ammo/heal pickups, plus a rolled
    * weapon and/or armor piece.
     */
    @Nonnull
    public static List<ItemStack> generateDrops(@Nonnull String blockTypeId) {
        CrateLootConfig.CrateLootEntry entry = CrateLootConfig.getCrateConfig(blockTypeId);
        if (entry == null) return List.of();

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<ItemStack> drops = new ArrayList<>(3);

        int coinQuantity = rng.nextInt(entry.getCoinMin(), entry.getCoinMax() + 1);
        if (coinQuantity > 0) {
            drops.add(new ItemStack(COIN_ITEM_ID, coinQuantity));
        }

        if (rng.nextDouble() < entry.getAmmoChance()) {
            drops.add(new ItemStack(AMMO_ITEM_ID, 1));
        }

        if (rng.nextDouble() < entry.getHealChance()) {
            drops.add(new ItemStack(HEAL_ITEM_ID, 1));
        }

        if (rng.nextDouble() < entry.getWeaponChance()) {
            List<String> whitelist = entry.getWeaponWhitelist();
            if (!whitelist.isEmpty()) {
                drops.add(WeaponLootRoller.rollFromCrate(
                        entry.getMinRarity(), entry.getMaxRarity(), whitelist));
            } else {
                drops.add(WeaponLootRoller.rollRandom());
            }
        }

        if (rng.nextDouble() < entry.getArmorChance()) {
            List<String> armorWhitelist = entry.getArmorWhitelist();
            if (!armorWhitelist.isEmpty()) {
                drops.add(ArmorLootRoller.rollFromCrate(
                        entry.getArmorMinRarity(), entry.getArmorMaxRarity(), armorWhitelist));
            } else {
                drops.add(ArmorLootRoller.rollRandom());
            }
        }

        return drops;
    }
}
