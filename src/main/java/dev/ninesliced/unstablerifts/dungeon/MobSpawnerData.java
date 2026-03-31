package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Block component that stores mob spawner configuration on a UnstableRifts_Mob_Spawner block.
 * Persists through chunk save/load and prefab save/load.
 * <p>
 * Format: MobEntries is semicolon-separated "MobId:Weight" pairs.
 * Example: "Trork_Warrior:3;Skeleton_Archer:2"
 */
public class MobSpawnerData implements Component<ChunkStore> {

    @Nonnull
    public static final BuilderCodec<MobSpawnerData> CODEC = BuilderCodec.builder(MobSpawnerData.class, MobSpawnerData::new)
            .append(new KeyedCodec<>("MobEntries", Codec.STRING), (d, v) -> d.mobEntries = v, d -> d.mobEntries).add()
            .append(new KeyedCodec<>("SpawnCount", Codec.STRING), (d, v) -> d.spawnCount = v, d -> d.spawnCount).add()
            .build();

    private static ComponentType<ChunkStore, MobSpawnerData> componentType;

    @Nullable
    private String mobEntries;
    @Nullable
    private String spawnCount;

    public MobSpawnerData() {
    }

    public MobSpawnerData(@Nullable String mobEntries, @Nullable String spawnCount) {
        this.mobEntries = mobEntries;
        this.spawnCount = spawnCount;
    }

    public static ComponentType<ChunkStore, MobSpawnerData> getComponentType() {
        return componentType;
    }

    public static void setComponentType(ComponentType<ChunkStore, MobSpawnerData> type) {
        componentType = type;
    }

    @Nonnull
    public static List<MobEntryData> parseEntries(@Nullable String raw) {
        List<MobEntryData> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split(";")) {
            String[] kv = part.split(":", 2);
            if (kv.length == 2 && !kv[0].isBlank()) {
                int weight = 1;
                try {
                    weight = Integer.parseInt(kv[1]);
                } catch (NumberFormatException ignored) {
                }
                result.add(new MobEntryData(kv[0], weight));
            }
        }
        return result;
    }

    public static String serializeEntries(List<MobEntryData> entries) {
        StringBuilder sb = new StringBuilder();
        for (MobEntryData e : entries) {
            if (!e.mobId.isBlank()) {
                if (!sb.isEmpty()) sb.append(";");
                sb.append(e.mobId).append(":").append(e.weight);
            }
        }
        return sb.toString();
    }

    @Nullable
    public String getMobEntries() {
        return mobEntries;
    }

    @Nullable
    public String getSpawnCount() {
        return spawnCount;
    }

    public List<MobEntryData> parseEntries() {
        return parseEntries(mobEntries);
    }

    public int parseSpawnCount() {
        if (spawnCount == null || spawnCount.isBlank()) return 1;
        try {
            return Integer.parseInt(spawnCount);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    @Override
    @Nullable
    public Component<ChunkStore> clone() {
        return new MobSpawnerData(this.mobEntries, this.spawnCount);
    }

    public record MobEntryData(String mobId, int weight) {
    }
}
