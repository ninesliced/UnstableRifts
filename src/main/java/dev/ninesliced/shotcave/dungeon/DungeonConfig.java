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
import java.util.logging.Logger;

public class DungeonConfig {

    private static final Logger LOGGER = Logger.getLogger(DungeonConfig.class.getName());
    private static final String FILE_NAME = "dungeon.json";
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("levels")
    private List<LevelConfig> levels = new ArrayList<>();

    @Nonnull
    public List<LevelConfig> getLevels() {
        return levels;
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
            LOGGER.info("Created default dungeon config at " + configPath.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to ensure dungeon config at " + configPath.toAbsolutePath(), e);
        }
        return configPath;
    }

    @Nonnull
    public static DungeonConfig load(@Nonnull Path configPath) {
        try {
            if (!Files.exists(configPath)) {
                LOGGER.warning("Dungeon config not found at " + configPath.toAbsolutePath() + ", using defaults");
                return sanitize(new DungeonConfig());
            }
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(configPath), StandardCharsets.UTF_8)) {
                return sanitize(GSON.fromJson(reader, DungeonConfig.class));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load dungeon config from " + configPath.toAbsolutePath(), e);
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

    public static class LevelConfig {
        @SerializedName("id")
        private String id;

        @SerializedName("name")
        private String name = "Default";

        @SerializedName("rooms")
        private int rooms = 10;

        @SerializedName("prefabs")
        private PrefabSet prefabs = new PrefabSet();

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

        public List<String> getEntrance() { return entrance; }
        public List<String> getRoom() { return room; }
        public List<String> getWall() { return wall; }
        public List<String> getBoss() { return boss; }

        private void sanitize() {
            if (entrance == null) entrance = new ArrayList<>();
            if (room == null) room = new ArrayList<>();
            if (wall == null) wall = new ArrayList<>();
            if (boss == null) boss = new ArrayList<>();
        }
    }

    @Nonnull
    public static List<Path> resolveGlobs(@Nonnull List<String> globs) {
        List<Path> result = new ArrayList<>();
        for (String glob : globs) {
            result.addAll(resolveGlob(glob));
        }
        return result;
    }

    @Nonnull
    public static List<Path> resolveGlob(@Nonnull String glob) {
        List<Path> result = new ArrayList<>();

        if (glob.endsWith(".*")) {
            String folderPath = glob.substring(0, glob.length() - 2).replace('.', '/');
            Path folder = findPrefabFolder(folderPath);
            if (folder != null && Files.isDirectory(folder)) {
                collectPrefabFiles(folder, result);
            } else {
                LOGGER.warning("Could not resolve prefab folder: " + folderPath);
            }
        } else {
            String filePath = glob.replace('.', '/') + ".prefab.json";
            Path path = PrefabStore.get().findAssetPrefabPath(filePath);
            if (path != null) {
                result.add(path);
            } else {
                LOGGER.warning("Could not resolve prefab: " + filePath);
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
            LOGGER.log(Level.WARNING, "Failed to list prefabs in " + directory, e);
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
            LOGGER.log(Level.WARNING, "Failed to load prefab buffer: " + path, e);
            return null;
        }
    }
}
