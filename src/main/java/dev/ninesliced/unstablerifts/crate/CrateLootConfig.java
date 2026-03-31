package dev.ninesliced.unstablerifts.crate;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import dev.ninesliced.unstablerifts.guns.WeaponRarity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Loads and caches the {@code crate_loot.json} configuration that defines
 * per-crate-type loot parameters (rarity range, weapon chance, coin range,
 * and weapon whitelist).
 */
public final class CrateLootConfig {

    private static final String CONFIG_FILE = "crate_loot.json";
    private static final Gson GSON = new Gson();

    private static volatile Map<String, CrateLootEntry> ENTRIES;

    private CrateLootConfig() {
    }

    /**
     * Eagerly loads config. Call once during initialization.
     */
    public static void load() {
        if (ENTRIES != null) return;
        ConfigRoot root = loadResource(CONFIG_FILE, ConfigRoot.class);
        ENTRIES = root.crates != null
                ? Collections.unmodifiableMap(root.crates)
                : Map.of();
    }

    /**
     * Returns true if the given block type ID has a crate loot entry.
     */
    public static boolean isCrate(@Nonnull String blockTypeId) {
        ensureLoaded();
        return ENTRIES.containsKey(blockTypeId);
    }

    /**
     * Returns the loot config for a crate block type ID, or null if not configured.
     */
    @Nullable
    public static CrateLootEntry getCrateConfig(@Nonnull String blockTypeId) {
        ensureLoaded();
        return ENTRIES.get(blockTypeId);
    }

    private static void ensureLoaded() {
        if (ENTRIES == null) load();
    }

    // ── JSON data classes ──────────────────────────────────────────────

    @Nonnull
    private static <T> T loadResource(@Nonnull String path, @Nonnull Class<T> type) {
        try (var is = CrateLootConfig.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Missing resource: " + path);
            }
            try (var reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                T result = GSON.fromJson(reader, type);
                if (result == null) {
                    throw new IllegalStateException("Failed to parse: " + path);
                }
                return result;
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to load " + path, e);
        }
    }

    private static final class ConfigRoot {
        @SerializedName("crates")
        Map<String, CrateLootEntry> crates;
    }

    // ── Resource loading ───────────────────────────────────────────────

    public static final class CrateLootEntry {
        @SerializedName("minRarity")
        private String minRarity;
        @SerializedName("maxRarity")
        private String maxRarity;
        @SerializedName("weaponChance")
        private double weaponChance;
        @SerializedName("coinMin")
        private int coinMin;
        @SerializedName("coinMax")
        private int coinMax;
        @SerializedName("ammoChance")
        private double ammoChance;
        @SerializedName("healChance")
        private double healChance;
        @SerializedName("weaponWhitelist")
        private List<String> weaponWhitelist;
        @SerializedName("armorChance")
        private double armorChance;
        @SerializedName("armorMinRarity")
        private String armorMinRarity;
        @SerializedName("armorMaxRarity")
        private String armorMaxRarity;
        @SerializedName("armorWhitelist")
        private List<String> armorWhitelist;

        @Nonnull
        public WeaponRarity getMinRarity() {
            return minRarity != null ? WeaponRarity.fromString(minRarity) : WeaponRarity.BASIC;
        }

        @Nonnull
        public WeaponRarity getMaxRarity() {
            return maxRarity != null ? WeaponRarity.fromString(maxRarity) : WeaponRarity.UNIQUE;
        }

        public double getWeaponChance() {
            return weaponChance;
        }

        public int getCoinMin() {
            return coinMin;
        }

        public int getCoinMax() {
            return coinMax;
        }

        public double getAmmoChance() {
            return ammoChance;
        }

        public double getHealChance() {
            return healChance;
        }

        @Nonnull
        public List<String> getWeaponWhitelist() {
            return weaponWhitelist != null ? weaponWhitelist : List.of();
        }

        public double getArmorChance() {
            return armorChance;
        }

        @Nonnull
        public WeaponRarity getArmorMinRarity() {
            return armorMinRarity != null ? WeaponRarity.fromString(armorMinRarity) : WeaponRarity.BASIC;
        }

        @Nonnull
        public WeaponRarity getArmorMaxRarity() {
            return armorMaxRarity != null ? WeaponRarity.fromString(armorMaxRarity) : WeaponRarity.UNIQUE;
        }

        @Nonnull
        public List<String> getArmorWhitelist() {
            return armorWhitelist != null ? armorWhitelist : List.of();
        }
    }
}
