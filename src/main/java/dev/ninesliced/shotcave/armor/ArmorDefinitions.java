package dev.ninesliced.shotcave.armor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Static registry of all armor definitions, populated by
 * {@link ArmorRegistry#registerAll()} at startup.
 */
public final class ArmorDefinitions {

    private ArmorDefinitions() {}

    private static final Map<String, ArmorDefinition> BY_ITEM_ID = new LinkedHashMap<>();

    public static void register(@Nonnull ArmorDefinition definition) {
        BY_ITEM_ID.put(definition.getItemId(), definition);
    }

    @Nullable
    public static ArmorDefinition getById(@Nonnull String itemId) {
        return BY_ITEM_ID.get(itemId);
    }

    @Nonnull
    public static List<ArmorDefinition> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(BY_ITEM_ID.values()));
    }

    /**
     * Returns all armor pieces belonging to a given set (e.g., "crystal").
     */
    @Nonnull
    public static List<ArmorDefinition> getBySetId(@Nonnull String setId) {
        return BY_ITEM_ID.values().stream()
                .filter(def -> def.getSetId().equals(setId))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Returns a flat list where each armor piece appears N times equal to its spawnWeight.
     */
    @Nonnull
    public static List<ArmorDefinition> getWeightedPool() {
        List<ArmorDefinition> pool = new ArrayList<>();
        for (ArmorDefinition def : BY_ITEM_ID.values()) {
            for (int i = 0; i < def.getSpawnWeight(); i++) {
                pool.add(def);
            }
        }
        return pool;
    }
}
