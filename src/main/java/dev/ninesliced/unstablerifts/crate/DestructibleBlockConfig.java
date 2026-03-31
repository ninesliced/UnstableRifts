package dev.ninesliced.unstablerifts.crate;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import javax.annotation.Nonnull;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads and caches the {@code destructible_blocks.json} configuration that
 * defines which blocks can be targeted and destroyed by weapons.
 * Blocks are categorised as either CRATE (drops loot) or BARREL (spawns toxic gas).
 */
public final class DestructibleBlockConfig {

    private static final String CONFIG_FILE = "destructible_blocks.json";
    private static final Gson GSON = new Gson();

    private static volatile Set<String> CRATE_IDS;
    private static volatile Set<String> BARREL_IDS;
    private static volatile Set<String> ALL_IDS;

    private DestructibleBlockConfig() {
    }

    /**
     * Eagerly loads config. Call once during initialization.
     */
    public static void load() {
        if (ALL_IDS != null) return;
        ConfigRoot root = loadResource(CONFIG_FILE, ConfigRoot.class);

        Set<String> crates = root.crates != null
                ? Collections.unmodifiableSet(new HashSet<>(root.crates))
                : Set.of();
        Set<String> barrels = root.barrels != null
                ? Collections.unmodifiableSet(new HashSet<>(root.barrels))
                : Set.of();

        Set<String> all = new HashSet<>(crates);
        all.addAll(barrels);

        CRATE_IDS = crates;
        BARREL_IDS = barrels;
        ALL_IDS = Collections.unmodifiableSet(all);
    }

    /**
     * Returns true if the given block type ID is a destructible crate or barrel.
     */
    public static boolean isDestructible(@Nonnull String blockTypeId) {
        ensureLoaded();
        return ALL_IDS.contains(blockTypeId);
    }

    /**
     * Returns true if the given block type ID is a crate (drops loot).
     */
    public static boolean isCrate(@Nonnull String blockTypeId) {
        ensureLoaded();
        return CRATE_IDS.contains(blockTypeId);
    }

    /**
     * Returns true if the given block type ID is a barrel (spawns toxic gas).
     */
    public static boolean isBarrel(@Nonnull String blockTypeId) {
        ensureLoaded();
        return BARREL_IDS.contains(blockTypeId);
    }

    private static void ensureLoaded() {
        if (ALL_IDS == null) load();
    }

    // ── JSON data classes ──────────────────────────────────────────────

    @Nonnull
    private static <T> T loadResource(@Nonnull String path, @Nonnull Class<T> type) {
        try (var is = DestructibleBlockConfig.class.getClassLoader().getResourceAsStream(path)) {
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

    // ── Resource loading ───────────────────────────────────────────────

    private static final class ConfigRoot {
        @SerializedName("crates")
        List<String> crates;
        @SerializedName("barrels")
        List<String> barrels;
    }
}
