package dev.ninesliced.shotcave.crate;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// TODO: replace with custom logic based on difficulty of the region & other factors
/** Placeholder loot table for crate drops. */
public final class CrateDropGenerator {

    private static final String COIN_ITEM_ID = "Shotcave_Props_Coin";
    private static final String WEAPON_ITEM_ID = "Weapon_Voidlance_Shotcave";

    // 1×1 crate loot
    private static final int SMALL_COIN_MIN = 1;
    private static final int SMALL_COIN_MAX = 3;
    private static final double SMALL_WEAPON_CHANCE = 0.10;

    // 2×2 crate loot
    private static final int LARGE_COIN_MIN = 3;
    private static final int LARGE_COIN_MAX = 7;
    private static final double LARGE_WEAPON_CHANCE = 0.25;

    public static final String CRATE_1X1_BLOCK_ID = "Shotcave_Props_Crate";
    public static final String CRATE_2X2_BLOCK_ID = "Shotcave_Props_Crate_2x2";

    private CrateDropGenerator() {
    }

    public static boolean isCrate(@Nonnull String blockTypeId) {
        return CRATE_1X1_BLOCK_ID.equals(blockTypeId)
                || CRATE_2X2_BLOCK_ID.equals(blockTypeId);
    }

    @Nonnull
    public static List<ItemStack> generateDrops(@Nonnull String blockTypeId) {
        return switch (blockTypeId) {
            case CRATE_1X1_BLOCK_ID -> generate(SMALL_COIN_MIN, SMALL_COIN_MAX, SMALL_WEAPON_CHANCE);
            case CRATE_2X2_BLOCK_ID -> generate(LARGE_COIN_MIN, LARGE_COIN_MAX, LARGE_WEAPON_CHANCE);
            default -> List.of();
        };
    }

    @Nonnull
    private static List<ItemStack> generate(int coinMin, int coinMax, double weaponChance) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<ItemStack> drops = new ArrayList<>(2);

        int coinQuantity = rng.nextInt(coinMin, coinMax + 1);
        if (coinQuantity > 0) {
            drops.add(new ItemStack(COIN_ITEM_ID, coinQuantity));
        }

        if (rng.nextDouble() < weaponChance) {
            drops.add(new ItemStack(WEAPON_ITEM_ID, 1));
        }

        return drops;
    }
}
