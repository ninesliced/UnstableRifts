package dev.ninesliced.shotcave.dungeon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.logging.Level;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ninesliced.shotcave.ShotcaveLog;

public class DungeonConfig {

    private static final HytaleLogger LOGGER = ShotcaveLog.forModule("Dungeon");
    private static final String FILE_NAME = "dungeon.json";
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("levels")
    private List<LevelConfig> levels = new ArrayList<>();

    @SerializedName("startEquipment")
    private List<String> startEquipment = new ArrayList<>();

    @SerializedName("bossWallBlock")
    private String bossWallBlock = "Stone_Brick_Wall";

    @SerializedName("maxPartySize")
    private int maxPartySize = 4;

    @SerializedName("dungeonName")
    private String dungeonName = "Shotcave";

    @Nonnull
    public static Path ensureRuntimeConfig(@Nonnull Path dataDirectory) {
        Path configPath = dataDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(dataDirectory);
            if (Files.exists(configPath)) {
                return configPath;
            }
            try (InputStream is = DungeonConfig.class.getClassLoader().getResourceAsStream(FILE_NAME)) {
                if (is != null) {
                    Files.copy(is, configPath, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.writeString(configPath, PRETTY_GSON.toJson(new DungeonConfig()), StandardCharsets.UTF_8);
                }
            }
            LOGGER.at(Level.INFO).log("Created default dungeon config at %s", configPath.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Failed to ensure dungeon config at %s",
                    configPath.toAbsolutePath());
        }
        return configPath;
    }

    @Nonnull
    public static DungeonConfig load(@Nonnull Path configPath) {
        try {
            if (!Files.exists(configPath)) {
                LOGGER.at(Level.WARNING).log("Dungeon config not found at %s, using defaults",
                        configPath.toAbsolutePath());
                return sanitize(new DungeonConfig());
            }
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(configPath),
                    StandardCharsets.UTF_8)) {
                return sanitize(GSON.fromJson(reader, DungeonConfig.class));
            }
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Failed to load dungeon config from %s",
                    configPath.toAbsolutePath());
            return sanitize(new DungeonConfig());
        }
    }

    @Nonnull
    private static DungeonConfig sanitize(@Nullable DungeonConfig config) {
        DungeonConfig safe = config == null ? new DungeonConfig() : config;
        if (safe.levels == null) {
            safe.levels = new ArrayList<>();
        }
        safe.levels.removeIf(Objects::isNull);
        for (LevelConfig level : safe.levels) {
            level.sanitize();
        }
        return safe;
    }

    private static String normalize(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    @Nonnull
    private static List<String> sanitizeGlobList(@Nullable List<String> globs) {
        List<String> sanitized = new ArrayList<>();
        if (globs == null) {
            return sanitized;
        }
        for (String glob : globs) {
            if (glob == null) {
                continue;
            }
            String trimmed = glob.trim();
            if (!trimmed.isEmpty()) {
                sanitized.add(trimmed);
            }
        }
        return sanitized;
    }

    @Nonnull
    public static List<Path> resolveGlobs(@Nonnull List<String> globs) {
        List<Path> result = new ArrayList<>();
        for (String glob : globs) {
            if (glob == null || glob.isBlank()) {
                LOGGER.at(Level.WARNING).log("Skipping empty prefab glob entry");
                continue;
            }
            result.addAll(resolveGlob(glob));
        }
        return result;
    }

    @Nonnull
    public static List<Path> resolveGlob(@Nullable String glob) {
        List<Path> result = new ArrayList<>();
        if (glob == null) {
            LOGGER.at(Level.WARNING).log("Skipping null prefab glob entry");
            return result;
        }

        String trimmedGlob = glob.trim();
        if (trimmedGlob.isEmpty()) {
            LOGGER.at(Level.WARNING).log("Skipping blank prefab glob entry");
            return result;
        }

        if (trimmedGlob.endsWith(".*")) {
            String folderPath = trimmedGlob.substring(0, trimmedGlob.length() - 2).replace('.', '/');
            Path folder = findPrefabFolder(folderPath);
            if (folder != null && Files.isDirectory(folder)) {
                collectPrefabFiles(folder, result);
            } else {
                LOGGER.at(Level.WARNING).log("Could not resolve prefab folder: %s", folderPath);
            }
        } else {
            String filePath = trimmedGlob.replace('.', '/') + ".prefab.json";
            Path path = PrefabStore.get().findAssetPrefabPath(filePath);
            if (path != null) {
                result.add(path);
            } else {
                LOGGER.at(Level.WARNING).log("Could not resolve prefab: %s", filePath);
            }
        }

        return result;
    }

    @Nullable
    private static Path findPrefabFolder(@Nonnull String relativePath) {
        for (var packPath : PrefabStore.get().getAllAssetPrefabPaths()) {
            Path folder = packPath.prefabsPath().resolve(relativePath);
            if (Files.isDirectory(folder)) {
                return folder;
            }
        }
        return null;
    }

    private static void collectPrefabFiles(@Nonnull Path directory, @Nonnull List<Path> result) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    collectPrefabFiles(entry, result);
                } else if (entry.getFileName().toString().endsWith(".prefab.json")) {
                    result.add(entry);
                }
            }
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Failed to list prefabs in %s", directory);
        }
    }

    @Nullable
    public static Path pickRandom(@Nonnull Random random, @Nonnull List<Path> paths) {
        if (paths.isEmpty()) return null;
        return paths.get(random.nextInt(paths.size()));
    }

    @Nullable
    public static IPrefabBuffer loadBuffer(@Nonnull Path path) {
        try {
            return PrefabBufferUtil.getCached(path);
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Failed to load prefab buffer: %s", path);
            return null;
        }
    }

    @Nonnull
    public List<LevelConfig> getLevels() {
        return levels;
    }

    @Nonnull
    public List<String> getStartEquipment() {
        return startEquipment != null ? startEquipment : new ArrayList<>();
    }

    @Nonnull
    public String getBossWallBlock() {
        return bossWallBlock != null ? bossWallBlock : "Stone_Brick_Wall";
    }

    public int getMaxPartySize() {
        return maxPartySize;
    }

    @Nonnull
    public String getDungeonName() {
        return dungeonName != null ? dungeonName : "Shotcave";
    }

    @Nonnull
    public List<String> getLevelSelectors() {
        List<String> selectors = new ArrayList<>();
        for (LevelConfig level : levels) {
            selectors.add(level.getSelector());
        }
        return selectors;
    }

    @Nullable
    public LevelConfig findLevel(@Nonnull String selection) {
        String normalized = normalize(selection);
        for (LevelConfig level : levels) {
            if (level.matches(selection, normalized)) {
                return level;
            }
        }
        return null;
    }

    public static class LevelConfig {
        @SerializedName("id")
        private String id;

        @SerializedName("name")
        private String name = "Default";

        @SerializedName("rooms")
        private int rooms = 10;

        @SerializedName("prefabs")
        private PrefabSet prefabs = new PrefabSet();

        @SerializedName("mobs")
        private List<String> mobs = new ArrayList<>();

        @SerializedName("bossMob")
        private String bossMob;

        @SerializedName("moneyPerKill")
        private int moneyPerKill = 10;

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public int getRooms() {
            return rooms;
        }

        public PrefabSet getPrefabs() {
            return prefabs;
        }

        @Nonnull
        public List<String> getMobs() {
            return mobs != null ? mobs : new ArrayList<>();
        }

        @Nullable
        public String getBossMob() {
            return bossMob;
        }

        public int getMoneyPerKill() {
            return moneyPerKill;
        }

        @Nonnull
        public String getSelector() {
            if (id != null && !id.isBlank()) {
                return id.trim();
            }
            String normalizedName = normalize(name);
            return normalizedName.isBlank() ? "default" : normalizedName;
        }

        private boolean matches(@Nonnull String rawSelection, @Nonnull String normalizedSelection) {
            if (rawSelection.equalsIgnoreCase(getSelector())) {
                return true;
            }
            if (name != null && rawSelection.equalsIgnoreCase(name)) {
                return true;
            }
            return normalizedSelection.equals(normalize(getSelector()))
                    || normalizedSelection.equals(normalize(name));
        }

        private void sanitize() {
            if (name == null || name.isBlank()) {
                name = "Default";
            }
            if (rooms < 0) {
                rooms = 0;
            }
            if (prefabs == null) {
                prefabs = new PrefabSet();
            }
            prefabs.sanitize();
        }
    }

    public static class PrefabSet {
        @SerializedName("entrance")
        private List<String> entrance = new ArrayList<>();

        @SerializedName("room")
        private List<String> room = new ArrayList<>();

        @SerializedName("wall")
        private List<String> wall = new ArrayList<>();

        @SerializedName("boss")
        private List<String> boss = new ArrayList<>();

        public List<String> getEntrance() {
            return entrance;
        }

        public List<String> getRoom() {
            return room;
        }

        public List<String> getWall() {
            return wall;
        }

        public List<String> getBoss() {
            return boss;
        }

        private void sanitize() {
            entrance = sanitizeGlobList(entrance);
            room = sanitizeGlobList(room);
            wall = sanitizeGlobList(wall);
            boss = sanitizeGlobList(boss);
        }
    }

}
