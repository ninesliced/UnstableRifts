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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    // ────────────────────────────────────────────────
    //  Static helpers — config file management
    // ────────────────────────────────────────────────

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
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
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

    // ────────────────────────────────────────────────
    //  Static helpers — prefab resolution
    // ────────────────────────────────────────────────

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

    // ────────────────────────────────────────────────
    //  Accessors
    // ────────────────────────────────────────────────

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

    // ════════════════════════════════════════════════
    //  LevelConfig
    // ════════════════════════════════════════════════

    public static class LevelConfig {
        @SerializedName("id")
        private String id;

        @SerializedName("name")
        private String name = "Default";

        /** Global prefab pools — available on every branch. */
        @SerializedName("rooms")
        private RoomPools rooms = new RoomPools();

        /** Prefab globs that MUST appear exactly once per level. */
        @SerializedName("importantRooms")
        private List<String> importantRooms = new ArrayList<>();

        /** Main branch generation parameters. */
        @SerializedName("main")
        private MainBranchConfig main = new MainBranchConfig();

        /** Side branch generation parameters. */
        @SerializedName("branch")
        private BranchConfig branch = new BranchConfig();

        /** Weighted mob pool: mobId → weight. */
        @SerializedName("mobs")
        private Map<String, Integer> mobs = new HashMap<>();

        @SerializedName("nextLevel")
        private String nextLevel;

        /** Block type used for sealed doors. */
        @SerializedName("doorBlock")
        private String doorBlock = "Stone_Brick_Wall";

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        @Nonnull
        public RoomPools getRoomPools() {
            return rooms != null ? rooms : new RoomPools();
        }

        @Nonnull
        public List<String> getImportantRooms() {
            return importantRooms != null ? importantRooms : new ArrayList<>();
        }

        @Nonnull
        public MainBranchConfig getMain() {
            return main != null ? main : new MainBranchConfig();
        }

        @Nonnull
        public BranchConfig getBranch() {
            return branch != null ? branch : new BranchConfig();
        }

        @Nonnull
        public Map<String, Integer> getMobs() {
            return mobs != null ? mobs : new HashMap<>();
        }

        @Nonnull
        public WeightedPool<String> getMobPool() {
            return WeightedPool.of(getMobs());
        }

        @Nullable
        public String getNextLevel() {
            return nextLevel != null && !nextLevel.isBlank() ? nextLevel.trim() : null;
        }

        @Nonnull
        public String getDoorBlock() {
            return doorBlock != null ? doorBlock : "Stone_Brick_Wall";
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
            if (rooms == null) {
                rooms = new RoomPools();
            }
            rooms.sanitize();
            if (main == null) {
                main = new MainBranchConfig();
            }
            main.sanitize();
            if (branch == null) {
                branch = new BranchConfig();
            }
            branch.sanitize();
            if (importantRooms == null) {
                importantRooms = new ArrayList<>();
            }
            if (mobs == null) {
                mobs = new HashMap<>();
            }
            if (nextLevel != null && nextLevel.isBlank()) {
                nextLevel = null;
            }
        }
    }

    // ════════════════════════════════════════════════
    //  RoomPools — prefab glob lists per room type
    // ════════════════════════════════════════════════

    public static class RoomPools {
        @SerializedName("spawn")
        private List<String> spawn = new ArrayList<>();

        @SerializedName("corridor")
        private List<String> corridor = new ArrayList<>();

        @SerializedName("challenge")
        private List<String> challenge = new ArrayList<>();

        @SerializedName("treasure")
        private List<String> treasure = new ArrayList<>();

        @SerializedName("shop")
        private List<String> shop = new ArrayList<>();

        @SerializedName("boss")
        private List<String> boss = new ArrayList<>();

        @SerializedName("wall")
        private List<String> wall = new ArrayList<>();

        @SerializedName("keyDoor")
        private List<String> keyDoor = new ArrayList<>();

        @SerializedName("activationDoor")
        private List<String> activationDoor = new ArrayList<>();

        @SerializedName("lockDoor")
        private List<String> lockDoor = new ArrayList<>();

        @SerializedName("sealDoor")
        private List<String> sealDoor = new ArrayList<>();

        @SerializedName("branch")
        private List<String> branch = new ArrayList<>();

        @Nonnull public List<String> getSpawn()      { return spawn != null ? spawn : Collections.emptyList(); }
        @Nonnull public List<String> getCorridor()    { return corridor != null ? corridor : Collections.emptyList(); }
        @Nonnull public List<String> getChallenge()   { return challenge != null ? challenge : Collections.emptyList(); }
        @Nonnull public List<String> getTreasure()    { return treasure != null ? treasure : Collections.emptyList(); }
        @Nonnull public List<String> getShop()        { return shop != null ? shop : Collections.emptyList(); }
        @Nonnull public List<String> getBoss()        { return boss != null ? boss : Collections.emptyList(); }
        @Nonnull public List<String> getWall()        { return wall != null ? wall : Collections.emptyList(); }
        @Nonnull public List<String> getKeyDoor()     { return keyDoor != null ? keyDoor : Collections.emptyList(); }
        @Nonnull public List<String> getActivationDoor() { return activationDoor != null ? activationDoor : Collections.emptyList(); }
        @Nonnull public List<String> getLockDoor()    { return lockDoor != null ? lockDoor : Collections.emptyList(); }
        @Nonnull public List<String> getSealDoor()    { return sealDoor != null ? sealDoor : Collections.emptyList(); }
        @Nonnull public List<String> getBranch()      { return branch != null ? branch : Collections.emptyList(); }

        /**
         * Get globs for a specific room type.
         */
        @Nonnull
        public List<String> getForType(@Nonnull RoomType type) {
            return switch (type) {
                case SPAWN     -> getSpawn();
                case CORRIDOR  -> getCorridor();
                case CHALLENGE -> getChallenge();
                case TREASURE  -> getTreasure();
                case SHOP      -> getShop();
                case BOSS      -> getBoss();
                case WALL      -> getWall();
            };
        }

        /**
         * Merge another RoomPools into this one (adds entries without duplicates).
         */
        @Nonnull
        public RoomPools mergedWith(@Nullable RoomPools other) {
            if (other == null) return this;
            RoomPools merged = new RoomPools();
            merged.spawn     = mergeGlobs(this.getSpawn(), other.getSpawn());
            merged.corridor  = mergeGlobs(this.getCorridor(), other.getCorridor());
            merged.challenge = mergeGlobs(this.getChallenge(), other.getChallenge());
            merged.treasure  = mergeGlobs(this.getTreasure(), other.getTreasure());
            merged.shop      = mergeGlobs(this.getShop(), other.getShop());
            merged.boss      = mergeGlobs(this.getBoss(), other.getBoss());
            merged.wall      = mergeGlobs(this.getWall(), other.getWall());
            merged.keyDoor   = mergeGlobs(this.getKeyDoor(), other.getKeyDoor());
            merged.activationDoor = mergeGlobs(this.getActivationDoor(), other.getActivationDoor());
            merged.lockDoor  = mergeGlobs(this.getLockDoor(), other.getLockDoor());
            merged.sealDoor  = mergeGlobs(this.getSealDoor(), other.getSealDoor());
            merged.branch    = mergeGlobs(this.getBranch(), other.getBranch());
            return merged;
        }

        @Nonnull
        private static List<String> mergeGlobs(@Nonnull List<String> a, @Nonnull List<String> b) {
            List<String> merged = new ArrayList<>(a);
            for (String s : b) {
                if (!merged.contains(s)) {
                    merged.add(s);
                }
            }
            return merged;
        }

        private void sanitize() {
            spawn     = sanitizeGlobList(spawn);
            corridor  = sanitizeGlobList(corridor);
            challenge = sanitizeGlobList(challenge);
            treasure  = sanitizeGlobList(treasure);
            shop      = sanitizeGlobList(shop);
            boss      = sanitizeGlobList(boss);
            wall      = sanitizeGlobList(wall);
            keyDoor   = sanitizeGlobList(keyDoor);
            activationDoor = sanitizeGlobList(activationDoor);
            lockDoor  = sanitizeGlobList(lockDoor);
            sealDoor  = sanitizeGlobList(sealDoor);
            branch    = sanitizeGlobList(branch);
        }
    }

    // ════════════════════════════════════════════════
    //  MainBranchConfig
    // ════════════════════════════════════════════════

    public static class MainBranchConfig {
        @SerializedName("maxRooms")
        private int maxRooms = 10;

        @SerializedName("challengeRooms")
        private IntRange challengeRooms = new IntRange(2, 4);

        @SerializedName("minimumCorridorLength")
        private int minimumCorridorLength = 1;

        @SerializedName("mobsToSpawn")
        private IntRange mobsToSpawn = new IntRange(8, 15);

        @SerializedName("treasureRooms")
        private IntRange treasureRooms = new IntRange(0, 1);

        @SerializedName("shopRooms")
        private IntRange shopRooms = new IntRange(0, 1);

        /** Additional prefab pools exclusive to the main branch. */
        @SerializedName("rooms")
        private RoomPools rooms;

        public int getMaxRooms()                             { return maxRooms > 0 ? maxRooms : 10; }
        public int getMinimumCorridorLength()    { return Math.max(0, minimumCorridorLength); }
        @Nonnull public IntRange getChallengeRooms() { return challengeRooms != null ? challengeRooms : new IntRange(2, 4); }
        @Nonnull public IntRange getMobsToSpawn()     { return mobsToSpawn != null ? mobsToSpawn : new IntRange(8, 15); }
        @Nonnull public IntRange getTreasureRooms()   { return treasureRooms != null ? treasureRooms : new IntRange(0, 1); }
        @Nonnull public IntRange getShopRooms()       { return shopRooms != null ? shopRooms : new IntRange(0, 1); }
        @Nullable public RoomPools getRooms()         { return rooms; }

        private void sanitize() {
            if (maxRooms <= 0) maxRooms = 10;
            if (minimumCorridorLength < 0) minimumCorridorLength = 1;
            if (challengeRooms == null) challengeRooms = new IntRange(2, 4);
            if (mobsToSpawn == null) mobsToSpawn = new IntRange(8, 15);
            if (treasureRooms == null) treasureRooms = new IntRange(0, 1);
            if (shopRooms == null) shopRooms = new IntRange(0, 1);
            if (rooms != null) rooms.sanitize();
        }
    }

    // ════════════════════════════════════════════════
    //  BranchConfig
    // ════════════════════════════════════════════════

    public static class BranchConfig {
        /** Probability of spawning a branch at each depth: "1" → 0.30, "2" → 0.15, etc. */
        @SerializedName("spawnProbability")
        private Map<String, Double> spawnProbability = new HashMap<>();

        /** Number of branch splitter rooms to place on the main path. */
        @SerializedName("splitterCount")
        private IntRange splitterCount = new IntRange(0, 0);

        @SerializedName("maxRooms")
        private int maxRooms = 5;

        @SerializedName("challengeRooms")
        private IntRange challengeRooms = new IntRange(1, 2);

        @SerializedName("minimumCorridorLength")
        private int minimumCorridorLength = 1;

        @SerializedName("mobsToSpawn")
        private IntRange mobsToSpawn = new IntRange(3, 8);

        /** Additional prefab pools exclusive to branches. */
        @SerializedName("rooms")
        private RoomPools rooms;

        public int getMaxRooms()                             { return maxRooms > 0 ? maxRooms : 5; }
        public int getMinimumCorridorLength()    { return Math.max(0, minimumCorridorLength); }
        @Nonnull public IntRange getChallengeRooms() { return challengeRooms != null ? challengeRooms : new IntRange(1, 2); }
        @Nonnull public IntRange getMobsToSpawn()     { return mobsToSpawn != null ? mobsToSpawn : new IntRange(3, 8); }
        @Nullable public RoomPools getRooms()         { return rooms; }
        @Nonnull public IntRange getSplitterCount() { return splitterCount != null ? splitterCount : new IntRange(0, 0); }

        /**
         * Get the fork probability for a given branch depth (1-based).
         * Returns 0 if the depth is not configured.
         */
        public double getForkProbability(int depth) {
            if (spawnProbability == null || spawnProbability.isEmpty()) return 0.0;
            Double prob = spawnProbability.get(String.valueOf(depth));
            return prob != null ? Math.max(0.0, Math.min(1.0, prob)) : 0.0;
        }

        private void sanitize() {
            if (spawnProbability == null) spawnProbability = new HashMap<>();
            if (splitterCount == null) splitterCount = new IntRange(0, 0);
            if (maxRooms <= 0) maxRooms = 5;
            if (minimumCorridorLength < 0) minimumCorridorLength = 1;
            if (challengeRooms == null) challengeRooms = new IntRange(1, 2);
            if (mobsToSpawn == null) mobsToSpawn = new IntRange(3, 8);
            if (rooms != null) rooms.sanitize();
        }
    }
}
