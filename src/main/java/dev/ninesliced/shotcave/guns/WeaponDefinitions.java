package dev.ninesliced.shotcave.guns;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static registry of all weapon definitions, populated by
 * {@link WeaponRegistry#registerAll()} at startup.
 */
public final class WeaponDefinitions {

    private WeaponDefinitions() {}

    private static final Map<String, WeaponDefinition> BY_ITEM_ID = new LinkedHashMap<>();

    public static void register(@Nonnull WeaponDefinition definition) {
        BY_ITEM_ID.put(definition.getItemId(), definition);
    }

    @Nullable
    public static WeaponDefinition getById(@Nonnull String itemId) {
        return BY_ITEM_ID.get(itemId);
    }

    @Nonnull
    public static List<WeaponDefinition> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(BY_ITEM_ID.values()));
    }

    /**
     * Returns a flat list where each weapon appears N times equal to its spawnWeight.
     * Used by {@link WeaponLootRoller} for weighted random selection.
     */
    @Nonnull
    public static List<WeaponDefinition> getWeightedPool() {
        List<WeaponDefinition> pool = new ArrayList<>();
        for (WeaponDefinition def : BY_ITEM_ID.values()) {
            for (int i = 0; i < def.getSpawnWeight(); i++) {
                pool.add(def);
            }
        }
        return pool;
    }
}
