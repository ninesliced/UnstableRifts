package dev.ninesliced.shotcave.dungeon;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PrefabUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ninesliced.shotcave.ShotcaveLog;

/**
 * Generates a dungeon level by placing prefab rooms in a structured graph.
 * <p>
 * Generation flow:
 * <ol>
 *   <li>Place Spawn room at origin</li>
 *   <li>Build the main branch (corridors → challenges → boss)</li>
 *   <li>Fork side branches with decreasing probability</li>
 *   <li>Place treasure rooms (behind key door prefabs)</li>
 *   <li>Place shop rooms</li>
 *   <li>Place important rooms (guaranteed spawns)</li>
 *   <li>Seal all remaining open exits with walls</li>
 *   <li>Distribute keys to key spawner markers</li>
 * </ol>
 */
public class DungeonGenerator {

    private static final HytaleLogger LOGGER = ShotcaveLog.forModule("Dungeon");
    private static final Gson GSON = new Gson();
    private static final int MAX_RETRIES = 5;

    private final Set<Long> occupiedBlocks = new HashSet<>();

    private World activeWorld;
    private Random activeRandom;
    private DungeonConfig.LevelConfig activeLevelConfig;
    private int branchCounter = 0;

    /** Resolved prefab paths for each room type. */
    private ResolvedPools resolvedPools;

    /** The level produced by the last generate() call. */
    @Nullable
    private dev.ninesliced.shotcave.dungeon.Level generatedLevel;

    // ════════════════════════════════════════════════
    //  Rotation & position helpers
    // ════════════════════════════════════════════════

    private static int rotX(int x, int z, int rot) {
        return switch (rot & 3) {
            case 1 -> z;
            case 2 -> -x;
            case 3 -> -z;
            default -> x;
        };
    }

    private static int rotZ(int x, int z, int rot) {
        return switch (rot & 3) {
            case 1 -> -x;
            case 2 -> -z;
            case 3 -> x;
            default -> z;
        };
    }

    private static double rotX(double x, double z, int rot) {
        return switch (rot & 3) {
            case 1 -> z;
            case 2 -> -x;
            case 3 -> -z;
            default -> x;
        };
    }

    private static double rotZ(double x, double z, int rot) {
        return switch (rot & 3) {
            case 1 -> -x;
            case 2 -> -z;
            case 3 -> x;
            default -> z;
        };
    }

    private static Rotation toEngineRotation(int rot) {
        return switch (rot & 3) {
            case 1 -> Rotation.Ninety;
            case 2 -> Rotation.OneEighty;
            case 3 -> Rotation.TwoSeventy;
            default -> Rotation.None;
        };
    }

    private static long packPos(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) | (((long) y & 0xFFFFF) << 22) | (((long) z & 0x3FFFFF) << 42);
    }

    // ════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════

    @Nullable
    public dev.ninesliced.shotcave.dungeon.Level getGeneratedLevel() {
        return generatedLevel;
    }

    /**
     * Generate a dungeon level in the given world.
     */
    public void generate(@Nonnull World world, long seed,
                         @Nonnull DungeonConfig.LevelConfig levelConfig) {
        Random random = new Random(seed);
        Store<EntityStore> store = world.getEntityStore().getStore();
        occupiedBlocks.clear();
        branchCounter = 0;

        this.activeWorld = world;
        this.activeRandom = random;
        this.activeLevelConfig = levelConfig;

        dev.ninesliced.shotcave.dungeon.Level level = new dev.ninesliced.shotcave.dungeon.Level(levelConfig.getName(), 0);
        this.generatedLevel = level;

        this.resolvedPools = resolvePools(levelConfig);

        LOGGER.at(Level.INFO).log("Generating dungeon '%s' seed=%d", levelConfig.getName(), seed);
        LOGGER.at(Level.INFO).log("Resolved pools: spawn=%d corridor=%d challenge=%d treasure=%d shop=%d boss=%d wall=%d keyDoor=%d",
                resolvedPools.spawn.size(), resolvedPools.corridor.size(),
                resolvedPools.challenge.size(), resolvedPools.treasure.size(),
                resolvedPools.shop.size(), resolvedPools.boss.size(),
                resolvedPools.wall.size(), resolvedPools.keyDoor.size());

        // ── Phase 1: Place Spawn room ──
        if (resolvedPools.spawn.isEmpty()) {
            LOGGER.at(Level.SEVERE).log("No spawn prefabs configured!");
            return;
        }

        Path spawnPath = DungeonConfig.pickRandom(random, resolvedPools.spawn);
        if (spawnPath == null) {
            LOGGER.at(Level.SEVERE).log("Failed to pick spawn prefab!");
            return;
        }
        PrefabData spawnData = readPrefabData(spawnPath);
        if (spawnData == null) {
            LOGGER.at(Level.SEVERE).log("Failed to parse spawn prefab!");
            return;
        }

        Vector3i spawnPaste = new Vector3i(0, 65, 0);
        pasteAndRegister(world, store, random, spawnPath, spawnData, spawnPaste, 0);

        RoomData spawnRoom = buildRoomData(RoomType.SPAWN, spawnData, spawnPaste, 0,
                Collections.emptyList(), 0, "main");
        level.addRoom(spawnRoom);

        List<TrackedExit> spawnExits = collectTrackedExits(spawnData, spawnPaste, 0, spawnRoom, 0, "main");
        LOGGER.at(Level.INFO).log("Spawn placed at %s, %d exit(s)", spawnPaste, spawnExits.size());

        // ── Phase 2: Build main branch ──
        DungeonConfig.MainBranchConfig mainConfig = levelConfig.getMain();
        List<TrackedExit> deadEndExits = new ArrayList<>();

        buildBranch(world, store, random, level, spawnExits, deadEndExits,
                0, "main", mainConfig.getChallengeRooms(),
                mainConfig.getMinimumCorridorLength(),
                mainConfig.getMaxRooms(), true /* isMainBranch */);

        // ── Phase 3: Place treasure rooms ──
        int treasureCount = mainConfig.getTreasureRooms().roll(random);
        int treasuresPlaced = 0;
        if (treasureCount > 0 && !resolvedPools.treasure.isEmpty()) {
            Collections.shuffle(deadEndExits, random);
            for (int i = 0; i < treasureCount && !deadEndExits.isEmpty(); i++) {
                TrackedExit exit = deadEndExits.remove(deadEndExits.size() - 1);
                if (!resolvedPools.keyDoor.isEmpty()) {
                    TrackedExit doorExit = placeConnectorRoom(world, store, random, level,
                            resolvedPools.keyDoor, exit, "KeyDoor", RoomType.WALL);
                    if (doorExit != null) {
                        exit = doorExit;
                    }
                }
                if (tryPlaceRoom(world, store, random, resolvedPools.treasure, exit, deadEndExits,
                        "Treasure", false, RoomType.TREASURE, level)) {
                    treasuresPlaced++;
                } else {
                    sealExit(exit.exit);
                }
            }
        }
        LOGGER.at(Level.INFO).log("Placed %d/%d treasure rooms", treasuresPlaced, treasureCount);

        // ── Phase 4: Place shop rooms ──
        int shopCount = mainConfig.getShopRooms().roll(random);
        int shopsPlaced = 0;
        if (shopCount > 0 && !resolvedPools.shop.isEmpty()) {
            Collections.shuffle(deadEndExits, random);
            for (int i = 0; i < shopCount && !deadEndExits.isEmpty(); i++) {
                TrackedExit exit = deadEndExits.remove(deadEndExits.size() - 1);
                if (tryPlaceRoom(world, store, random, resolvedPools.shop, exit, deadEndExits,
                        "Shop", false, RoomType.SHOP, level)) {
                    shopsPlaced++;
                } else {
                    sealExit(exit.exit);
                }
            }
        }
        LOGGER.at(Level.INFO).log("Placed %d/%d shop rooms", shopsPlaced, shopCount);

        // ── Phase 5: Place important rooms ──
        List<String> importantGlobs = levelConfig.getImportantRooms();
        int importantPlaced = 0;
        for (String glob : importantGlobs) {
            List<Path> importantPaths = DungeonConfig.resolveGlobs(List.of(glob));
            if (importantPaths.isEmpty() || deadEndExits.isEmpty()) continue;
            TrackedExit exit = deadEndExits.remove(random.nextInt(deadEndExits.size()));
            if (tryPlaceRoom(world, store, random, importantPaths, exit, deadEndExits,
                    "Important", true, RoomType.CHALLENGE, level)) {
                importantPlaced++;
            } else {
                sealExit(exit.exit);
            }
        }
        LOGGER.at(Level.INFO).log("Placed %d/%d important rooms", importantPlaced, importantGlobs.size());

        // ── Phase 6: Seal all remaining exits ──
        for (TrackedExit exit : deadEndExits) {
            sealExit(exit.exit);
        }
        deadEndExits.clear();

        // ── Phase 7: Distribute keys ──
        if (treasuresPlaced > 0) {
            distributeKeys(level, treasuresPlaced, random);
        }

        LOGGER.at(Level.INFO).log("Dungeon complete: %d rooms, boss=%b, treasures=%d, shops=%d, branches=%d",
                level.getRooms().size(), level.getBossRoom() != null,
                treasuresPlaced, shopsPlaced, branchCounter);
    }

    // ════════════════════════════════════════════════
    //  Branch building
    // ════════════════════════════════════════════════

    /**
     * Build a branch (main or side) by placing corridors, challenge rooms, and optionally a boss.
     * <p>
     * The layout is planned before placement to distribute corridors evenly across
     * segments (the gaps between challenges and before the boss). This keeps the
     * dungeon pacing homogeneous instead of front- or back-loading corridors.
     * <p>
     * Layout planning:
     * <ol>
     *   <li>Roll the challenge count</li>
     *   <li>Determine segments: one before each challenge + one before the boss (main only)</li>
     *   <li>Fill each segment with {@code minCorridorLength} corridors</li>
     *   <li>Distribute remaining room budget evenly across segments</li>
     *   <li>Execute the plan segment by segment</li>
     * </ol>
     * Boss does not count toward the room budget.
     */
    private void buildBranch(@Nonnull World world, @Nonnull Store<EntityStore> store,
                             @Nonnull Random random, @Nonnull dev.ninesliced.shotcave.dungeon.Level level,
                             @Nonnull List<TrackedExit> startExits,
                             @Nonnull List<TrackedExit> deadEndExits,
                             int branchDepth, @Nonnull String branchId,
                             @Nonnull IntRange challengeRange, int minCorridorLength,
                             int maxRooms, boolean isMainBranch) {
        List<TrackedExit> currentExits = new ArrayList<>(startExits);

        List<Path> corridorPaths = isMainBranch
                ? resolvedPools.mainCorridor
                : resolvedPools.branchCorridor;
        List<Path> challengePaths = isMainBranch
                ? resolvedPools.mainChallenge
                : resolvedPools.branchChallenge;

        if (corridorPaths.isEmpty()) corridorPaths = resolvedPools.corridor;
        if (challengePaths.isEmpty()) challengePaths = resolvedPools.challenge;

        // ── Phase 1: Plan the layout ──
        int challengeCount = challengeRange.roll(random);
        // Segments = gaps where corridors go: before each challenge + before boss (main only)
        int segments = challengeCount + (isMainBranch ? 1 : 0);
        if (segments == 0) segments = 1; // at least one segment for corridors

        // Clamp challenge count if minimum corridors + challenges exceed budget
        while (challengeCount > 0 && (segments * minCorridorLength + challengeCount) > maxRooms) {
            challengeCount--;
            segments = challengeCount + (isMainBranch ? 1 : 0);
            if (segments == 0) segments = 1;
        }

        int corridorBudget = maxRooms - challengeCount;
        int[] corridorsPerSegment = new int[segments];

        // Fill each segment with the minimum
        for (int i = 0; i < segments; i++) {
            corridorsPerSegment[i] = Math.min(minCorridorLength, corridorBudget);
            corridorBudget -= corridorsPerSegment[i];
        }

        // Distribute remaining budget evenly across segments
        if (corridorBudget > 0) {
            // Shuffle segment indices so extra corridors aren't always front-loaded
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < segments; i++) indices.add(i);
            Collections.shuffle(indices, random);

            for (int idx : indices) {
                if (corridorBudget <= 0) break;
                corridorsPerSegment[idx]++;
                corridorBudget--;
            }
        }

        // Log the planned layout
        StringBuilder plan = new StringBuilder();
        for (int s = 0; s < segments; s++) {
            if (s > 0) plan.append(" → ");
            plan.append(corridorsPerSegment[s]).append("c");
            if (s < challengeCount) {
                plan.append(" → [challenge]");
            } else if (isMainBranch) {
                plan.append(" → [boss]");
            }
        }
        LOGGER.at(Level.INFO).log("[%s] Layout plan: %s (max %d rooms, %d challenges)",
                branchId, plan, maxRooms, challengeCount);

        // ── Phase 2: Execute the plan ──
        int roomsPlaced = 0;
        int segmentIndex = 0;

        for (int c = 0; c < challengeCount; c++) {
            // Place corridors for this segment
            int corridorsToPlace = corridorsPerSegment[segmentIndex++];
            if (corridorsToPlace > 0) {
                int placed = placeCorridorChainCounted(world, store, random, level, currentExits,
                        deadEndExits, corridorPaths, corridorsToPlace, branchDepth, branchId);
                roomsPlaced += placed;
            }

            if (currentExits.isEmpty()) {
                LOGGER.at(Level.WARNING).log("[%s] No exits left for challenge %d/%d", branchId, c + 1, challengeCount);
                break;
            }

            // Place challenge room
            TrackedExit challengeExit = currentExits.remove(random.nextInt(currentExits.size()));
            List<TrackedExit> newExits = new ArrayList<>();
            if (tryPlaceRoom(world, store, random, challengePaths, challengeExit, newExits,
                    branchId + "-Challenge-" + (c + 1), true, RoomType.CHALLENGE, level,
                    branchDepth, branchId)) {
                roomsPlaced++;
                if (!newExits.isEmpty()) {
                    TrackedExit primary = newExits.remove(0);
                    currentExits.add(primary);
                    handleExtraExits(world, store, random, level, newExits, currentExits, deadEndExits,
                            branchDepth, branchId);
                }
            }
        }

        // Boss room (does not count toward room budget)
        if (isMainBranch) {
            // Place pre-boss corridor segment
            if (segmentIndex < segments) {
                int preBossCorridors = corridorsPerSegment[segmentIndex];
                if (preBossCorridors > 0) {
                    int placed = placeCorridorChainCounted(world, store, random, level, currentExits,
                            deadEndExits, corridorPaths, preBossCorridors, branchDepth, branchId);
                    roomsPlaced += placed;
                }
            }

            boolean bossPlaced = false;
            if (!resolvedPools.boss.isEmpty() && !currentExits.isEmpty()) {
                Collections.shuffle(currentExits, random);
                while (!currentExits.isEmpty() && !bossPlaced) {
                    TrackedExit bossExit = currentExits.remove(0);
                    List<TrackedExit> bossNewExits = new ArrayList<>();
                    if (tryPlaceRoom(world, store, random, resolvedPools.boss, bossExit, bossNewExits,
                            "Boss", true, RoomType.BOSS, level, branchDepth, branchId)) {
                        bossPlaced = true;
                        for (TrackedExit e : bossNewExits) {
                            sealExit(e.exit);
                        }
                    } else {
                        sealExit(bossExit.exit);
                    }
                }
            }
            if (!bossPlaced) {
                LOGGER.at(Level.WARNING).log("Failed to place boss room!");
            }
        }

        LOGGER.at(Level.INFO).log("[%s] Branch complete: %d rooms placed (max %d)",
                branchId, roomsPlaced, maxRooms);
        deadEndExits.addAll(currentExits);
    }

    /**
     * Place a chain of corridors and handle branch forking from extra exits.
     * Mutates {@code currentExits} in place and returns the number of corridors actually placed.
     */
    private int placeCorridorChainCounted(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                           @Nonnull Random random, @Nonnull dev.ninesliced.shotcave.dungeon.Level level,
                                           @Nonnull List<TrackedExit> currentExits,
                                           @Nonnull List<TrackedExit> deadEndExits,
                                           @Nonnull List<Path> corridorPaths,
                                           int count, int branchDepth, @Nonnull String branchId) {
        int placed = 0;
        for (int i = 0; i < count; i++) {
            if (currentExits.isEmpty()) break;

            TrackedExit exit = currentExits.remove(random.nextInt(currentExits.size()));
            List<TrackedExit> newExits = new ArrayList<>();

            if (corridorPaths.isEmpty() || !tryPlaceRoom(world, store, random, corridorPaths, exit, newExits,
                    branchId + "-Corridor-" + (i + 1), false, RoomType.CORRIDOR, level,
                    branchDepth, branchId)) {
                sealExit(exit.exit);
                continue;
            }

            placed++;
            if (!newExits.isEmpty()) {
                TrackedExit primary = newExits.remove(0);
                currentExits.add(primary);
                handleExtraExits(world, store, random, level, newExits, currentExits, deadEndExits,
                        branchDepth, branchId);
            }
        }
        return placed;
    }

    /**
     * For each extra exit (beyond the primary continuation), try to fork a side branch.
     * If forking fails or isn't rolled, add to dead end exits.
     */
    private void handleExtraExits(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                  @Nonnull Random random, @Nonnull dev.ninesliced.shotcave.dungeon.Level level,
                                  @Nonnull List<TrackedExit> extraExits,
                                  @Nonnull List<TrackedExit> currentExits,
                                  @Nonnull List<TrackedExit> deadEndExits,
                                  int currentBranchDepth, @Nonnull String currentBranchId) {
        DungeonConfig.BranchConfig branchConfig = activeLevelConfig.getBranch();

        for (TrackedExit extra : extraExits) {
            int newDepth = currentBranchDepth + 1;
            double forkProb = branchConfig.getForkProbability(newDepth);

            if (forkProb > 0 && random.nextDouble() < forkProb) {
                branchCounter++;
                String newBranchId = "branch-" + branchCounter;
                LOGGER.at(Level.INFO).log("Forking %s (depth %d) from %s", newBranchId, newDepth, currentBranchId);

                List<TrackedExit> branchStarts = new ArrayList<>();
                branchStarts.add(new TrackedExit(extra.exit, extra.parent, newDepth, newBranchId));

                buildBranch(world, store, random, level, branchStarts, deadEndExits,
                        newDepth, newBranchId,
                        branchConfig.getChallengeRooms(),
                        branchConfig.getMinimumCorridorLength(),
                        branchConfig.getMaxRooms(),
                        false /* not main branch */);
            } else {
                deadEndExits.add(extra);
            }
        }
    }

    // ════════════════════════════════════════════════
    //  Room placement
    // ════════════════════════════════════════════════

    /**
     * Try to place a room from the given prefab pool at the given exit.
     * Uses the default branch depth/id from the exit.
     */
    private boolean tryPlaceRoom(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                 @Nonnull Random random, @Nonnull List<Path> roomPaths,
                                 @Nonnull TrackedExit trackedExit,
                                 @Nonnull List<TrackedExit> newExitsOut,
                                 @Nonnull String label, boolean forceOnFailure,
                                 @Nonnull RoomType roomType, @Nonnull dev.ninesliced.shotcave.dungeon.Level level) {
        return tryPlaceRoom(world, store, random, roomPaths, trackedExit, newExitsOut,
                label, forceOnFailure, roomType, level,
                trackedExit.branchDepth, trackedExit.branchId);
    }

    /**
     * Try to place a room from the given prefab pool at the given exit.
     */
    private boolean tryPlaceRoom(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                 @Nonnull Random random, @Nonnull List<Path> roomPaths,
                                 @Nonnull TrackedExit trackedExit,
                                 @Nonnull List<TrackedExit> newExitsOut,
                                 @Nonnull String label, boolean forceOnFailure,
                                 @Nonnull RoomType roomType, @Nonnull dev.ninesliced.shotcave.dungeon.Level level,
                                 int branchDepth, @Nonnull String branchId) {
        if (roomPaths.isEmpty()) {
            LOGGER.at(Level.WARNING).log("[%s] No prefabs available for type %s", label, roomType);
            return false;
        }

        List<Path> shuffled = new ArrayList<>(roomPaths);
        Collections.shuffle(shuffled, random);

        OpenExit exit = trackedExit.exit;
        Vector3i pastePos = new Vector3i(exit.worldX, exit.worldY, exit.worldZ);
        int pasteRot = (exit.rotation + 2) & 3;
        int attempts = Math.min(MAX_RETRIES, shuffled.size());

        for (int i = 0; i < attempts; i++) {
            Path chosen = shuffled.get(i);
            PrefabData data = readPrefabData(chosen);
            if (data == null) continue;

            if (wouldOverlap(data, pastePos, pasteRot)) {
                LOGGER.at(Level.FINE).log("[%s] overlap retry %d/%d %s", label, i + 1, attempts, chosen.getFileName());
                continue;
            }

            try {
                pasteAndRegister(world, store, random, chosen, data, pastePos, pasteRot);
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).withCause(e).log("[%s] paste failed %s", label, chosen.getFileName());
                continue;
            }

            RoomData roomData = buildRoomData(roomType, data, pastePos, pasteRot,
                    Collections.emptyList(), branchDepth, branchId);
            if (trackedExit.parent != null) {
                trackedExit.parent.addChild(roomData);
            }
            level.addRoom(roomData);

            List<TrackedExit> exits = collectTrackedExits(data, pastePos, pasteRot, roomData, branchDepth, branchId);
            newExitsOut.addAll(exits);

            LOGGER.at(Level.INFO).log("[%s] %s paste=%s rot=%d -> %d exit(s)",
                    label, chosen.getFileName(), pastePos, pasteRot, exits.size());
            return true;
        }

        if (forceOnFailure && !shuffled.isEmpty()) {
            LOGGER.at(Level.WARNING).log("[%s] retries exhausted, force-placing", label);
            Path chosen = shuffled.get(0);
            PrefabData data = readPrefabData(chosen);
            if (data != null) {
                try {
                    pasteAndRegister(world, store, random, chosen, data, pastePos, pasteRot);
                    RoomData roomData = buildRoomData(roomType, data, pastePos, pasteRot,
                            Collections.emptyList(), branchDepth, branchId);
                    if (trackedExit.parent != null) {
                        trackedExit.parent.addChild(roomData);
                    }
                    level.addRoom(roomData);
                    List<TrackedExit> exits = collectTrackedExits(data, pastePos, pasteRot, roomData, branchDepth, branchId);
                    newExitsOut.addAll(exits);
                    LOGGER.at(Level.WARNING).log("[%s] force-placed %s at %s", label, chosen.getFileName(), pastePos);
                    return true;
                } catch (Exception e) {
                    LOGGER.at(Level.WARNING).withCause(e).log("[%s] force-paste failed", label);
                }
            }
        }

        LOGGER.at(Level.INFO).log("[%s] placement failed, sealing exit", label);
        sealExit(exit);
        return false;
    }

    /**
     * Place a single "connector" room (like a key door) and return the first exit, or null on failure.
     */
    @Nullable
    private TrackedExit placeConnectorRoom(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                            @Nonnull Random random, @Nonnull dev.ninesliced.shotcave.dungeon.Level level,
                                            @Nonnull List<Path> roomPaths,
                                            @Nonnull TrackedExit entrance,
                                            @Nonnull String label, @Nonnull RoomType roomType) {
        List<TrackedExit> newExits = new ArrayList<>();
        if (tryPlaceRoom(world, store, random, roomPaths, entrance, newExits, label, false, roomType, level)) {
            if (!newExits.isEmpty()) {
                TrackedExit primary = newExits.remove(0);
                for (TrackedExit extra : newExits) {
                    sealExit(extra.exit);
                }
                return primary;
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════
    //  RoomData building & marker detection
    // ════════════════════════════════════════════════

    @Nonnull
    private RoomData buildRoomData(@Nonnull RoomType type, @Nonnull PrefabData data,
                                   @Nonnull Vector3i pastePos, int rot,
                                   @Nonnull List<String> mobs,
                                   int branchDepth, @Nonnull String branchId) {
        List<Vector3i> spawnerPositions = computeWorldPositions(data.spawners, pastePos, rot);
        List<Vector3d> mobMarkerPositions = computeWorldDoublePositions(data.mobMarkers, pastePos, rot);

        RoomData roomData = new RoomData(type, pastePos, rot,
                spawnerPositions, mobMarkerPositions, mobs, branchDepth, branchId);

        for (MarkerLocal marker : data.markers) {
            int wx = pastePos.x + rotX(marker.x, marker.z, rot);
            int wy = pastePos.y + marker.y;
            int wz = pastePos.z + rotZ(marker.x, marker.z, rot);
            Vector3i worldPos = new Vector3i(wx, wy, wz);

            switch (marker.type) {
                case MOB_SPAWN_POINT -> roomData.addMobSpawnPoint(worldPos);
                case KEY_SPAWNER -> roomData.addKeySpawnerPosition(worldPos);
                case PORTAL -> roomData.addPortalPosition(worldPos);
                case PORTAL_EXIT -> roomData.addPortalExitPosition(worldPos);
                default -> {}
            }
        }

        return roomData;
    }

    @Nonnull
    private List<Vector3i> computeWorldPositions(@Nonnull List<SpawnerLocal> spawners,
                                                  @Nonnull Vector3i pastePos, int rot) {
        List<Vector3i> positions = new ArrayList<>();
        for (SpawnerLocal sp : spawners) {
            int wx = pastePos.x + rotX(sp.x, sp.z, rot);
            int wy = pastePos.y + sp.y;
            int wz = pastePos.z + rotZ(sp.x, sp.z, rot);
            positions.add(new Vector3i(wx, wy, wz));
        }
        return positions;
    }

    @Nonnull
    private List<Vector3d> computeWorldDoublePositions(@Nonnull List<PrefabMobMarkerLocal> markers,
                                                        @Nonnull Vector3i pastePos, int rot) {
        List<Vector3d> positions = new ArrayList<>();
        for (PrefabMobMarkerLocal marker : markers) {
            double wx = pastePos.x + rotX(marker.x, marker.z, rot);
            double wy = pastePos.y + marker.y;
            double wz = pastePos.z + rotZ(marker.x, marker.z, rot);
            positions.add(new Vector3d(wx, wy, wz));
        }
        return positions;
    }

    // ════════════════════════════════════════════════
    //  Exit collection & sealing
    // ════════════════════════════════════════════════

    @Nonnull
    private List<TrackedExit> collectTrackedExits(@Nonnull PrefabData data, @Nonnull Vector3i pastePos,
                                                   int parentRot, @Nonnull RoomData parent,
                                                   int branchDepth, @Nonnull String branchId) {
        List<TrackedExit> exits = new ArrayList<>();
        for (SpawnerLocal sp : data.spawners) {
            int wx = pastePos.x + rotX(sp.x, sp.z, parentRot);
            int wy = pastePos.y + sp.y;
            int wz = pastePos.z + rotZ(sp.x, sp.z, parentRot);
            int exitRot = (parentRot + sp.rot) & 3;
            exits.add(new TrackedExit(new OpenExit(wx, wy, wz, exitRot), parent, branchDepth, branchId));
        }
        return exits;
    }

    private void sealExit(@Nonnull OpenExit exit) {
        if (resolvedPools != null && !resolvedPools.wall.isEmpty()) {
            Path wallPath = DungeonConfig.pickRandom(activeRandom, resolvedPools.wall);
            if (wallPath != null) {
                PrefabData wallData = readPrefabData(wallPath);
                if (wallData != null) {
                    int placed = 0;
                    for (BlockLocal block : wallData.blocks) {
                        int wx = exit.worldX + rotX(block.x, block.z, exit.rotation);
                        int wy = exit.worldY + block.y;
                        int wz = exit.worldZ + rotZ(block.x, block.z, exit.rotation);
                        try {
                            activeWorld.setBlock(wx, wy, wz, block.name, 0);
                            occupiedBlocks.add(packPos(wx, wy, wz));
                            placed++;
                        } catch (Exception e) {
                            LOGGER.at(Level.FINE).withCause(e).log("Failed to place wall block at %d,%d,%d", wx, wy, wz);
                        }
                    }
                    LOGGER.at(Level.FINE).log("[Wall] sealed (%d,%d,%d) rot=%d %d blocks",
                            exit.worldX, exit.worldY, exit.worldZ, exit.rotation, placed);
                    return;
                }
            }
        }
        try {
            activeWorld.setBlock(exit.worldX, exit.worldY, exit.worldZ, "Empty", 0);
        } catch (Exception e) {
            LOGGER.at(Level.FINE).withCause(e).log("Failed to clear exit block at %d,%d,%d", exit.worldX, exit.worldY, exit.worldZ);
        }
    }

    // ════════════════════════════════════════════════
    //  Key distribution
    // ════════════════════════════════════════════════

    /**
     * Find key spawner positions across the level and select N for placement.
     */
    private void distributeKeys(@Nonnull dev.ninesliced.shotcave.dungeon.Level level, int keyCount, @Nonnull Random random) {
        List<Vector3i> allKeySpawners = new ArrayList<>();
        for (RoomData room : level.getRooms()) {
            if (room.getType() != RoomType.TREASURE) {
                allKeySpawners.addAll(room.getKeySpawnerPositions());
            }
        }

        if (allKeySpawners.isEmpty()) {
            LOGGER.at(Level.WARNING).log("No key spawner positions found! %d treasure rooms have no keys.", keyCount);
            return;
        }

        Collections.shuffle(allKeySpawners, random);
        int keysToPlace = Math.min(keyCount, allKeySpawners.size());
        LOGGER.at(Level.INFO).log("Distributing %d key(s) from %d available spawner(s)", keysToPlace, allKeySpawners.size());

        for (int i = 0; i < keysToPlace; i++) {
            Vector3i pos = allKeySpawners.get(i);
            LOGGER.at(Level.INFO).log("Key %d placed at %s", i + 1, pos);
        }
    }

    // ════════════════════════════════════════════════
    //  Prefab I/O
    // ════════════════════════════════════════════════

    @Nullable
    private PrefabData readPrefabData(@Nonnull Path path) {
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            int ax = root.has("anchorX") ? root.get("anchorX").getAsInt() : 0;
            int ay = root.has("anchorY") ? root.get("anchorY").getAsInt() : 0;
            int az = root.has("anchorZ") ? root.get("anchorZ").getAsInt() : 0;

            List<BlockLocal> blocks = new ArrayList<>();
            List<SpawnerLocal> spawners = new ArrayList<>();
            List<MarkerLocal> markers = new ArrayList<>();
            List<PrefabMobMarkerLocal> mobMarkers = new ArrayList<>();

            JsonArray arr = root.getAsJsonArray("blocks");
            if (arr != null) {
                for (JsonElement el : arr) {
                    JsonObject b = el.getAsJsonObject();
                    int x = b.get("x").getAsInt() - ax;
                    int y = b.get("y").getAsInt() - ay;
                    int z = b.get("z").getAsInt() - az;
                    String name = b.has("name") ? b.get("name").getAsString() : "";

                    MarkerType markerType = MarkerType.fromBlockName(name);
                    boolean isMarker;

                    if (markerType == MarkerType.EXIT) {
                        isMarker = true;
                        int sRot = b.has("rotation") ? b.get("rotation").getAsInt() : 0;
                        spawners.add(new SpawnerLocal(x, y, z, sRot));
                    } else if (markerType != null) {
                        isMarker = true;
                        markers.add(new MarkerLocal(x, y, z, markerType));
                    } else {
                        isMarker = "Prefab_Spawner_Block".equals(name);
                        if (isMarker) {
                            int sRot = b.has("rotation") ? b.get("rotation").getAsInt() : 0;
                            spawners.add(new SpawnerLocal(x, y, z, sRot));
                        }
                    }

                    blocks.add(new BlockLocal(x, y, z, name, isMarker));
                }
            }

            JsonArray entities = root.getAsJsonArray("entities");
            if (entities != null) {
                for (JsonElement entityElement : entities) {
                    JsonObject entity = entityElement.getAsJsonObject();
                    JsonObject components = entity.getAsJsonObject("Components");
                    if (components == null) continue;

                    JsonObject spawnMarkerComponent = components.getAsJsonObject("SpawnMarkerComponent");
                    JsonObject transformComponent = components.getAsJsonObject("Transform");
                    if (spawnMarkerComponent == null || transformComponent == null) continue;

                    JsonObject position = transformComponent.getAsJsonObject("Position");
                    if (position == null) continue;

                    String mobId = spawnMarkerComponent.has("SpawnMarker")
                            ? spawnMarkerComponent.get("SpawnMarker").getAsString() : "";
                    double x = position.has("X") ? position.get("X").getAsDouble() - ax : 0.0;
                    double y = position.has("Y") ? position.get("Y").getAsDouble() - ay : 0.0;
                    double z = position.has("Z") ? position.get("Z").getAsDouble() - az : 0.0;
                    mobMarkers.add(new PrefabMobMarkerLocal(x, y, z, mobId));
                }
            }

            return new PrefabData(blocks, spawners, markers, mobMarkers);
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Failed to read prefab: %s", path);
            return null;
        }
    }

    private void pasteAndRegister(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                  @Nonnull Random random, @Nonnull Path prefabPath,
                                  @Nonnull PrefabData data, @Nonnull Vector3i pastePos,
                                  int rot) {
        IPrefabBuffer buffer = DungeonConfig.loadBuffer(prefabPath);
        if (buffer == null) {
            throw new RuntimeException("Failed to load buffer: " + prefabPath);
        }

        PrefabUtil.paste(buffer, world, pastePos, toEngineRotation(rot),
                true, random, 0, false, false, true, store);

        for (BlockLocal block : data.blocks) {
            if (!block.isMarker) {
                int wx = pastePos.x + rotX(block.x, block.z, rot);
                int wy = pastePos.y + block.y;
                int wz = pastePos.z + rotZ(block.x, block.z, rot);
                occupiedBlocks.add(packPos(wx, wy, wz));
            }
        }

        for (BlockLocal block : data.blocks) {
            if (block.isMarker) {
                int wx = pastePos.x + rotX(block.x, block.z, rot);
                int wy = pastePos.y + block.y;
                int wz = pastePos.z + rotZ(block.x, block.z, rot);
                try {
                    MarkerType mt = MarkerType.fromBlockName(block.name);
                    world.setBlock(wx, wy, wz, "Empty", 0);
                    if (mt == MarkerType.WATER) {
                        placeFluid(world, wx, wy, wz, "Water", (byte) 8);
                    }
                } catch (Exception e) {
                    LOGGER.at(Level.FINE).withCause(e).log("Failed to process marker block at %d,%d,%d", wx, wy, wz);
                }
            }
        }
    }

    private boolean wouldOverlap(@Nonnull PrefabData data, @Nonnull Vector3i pastePos, int rot) {
        for (BlockLocal block : data.blocks) {
            if (block.isMarker) continue;
            int wx = pastePos.x + rotX(block.x, block.z, rot);
            int wy = pastePos.y + block.y;
            int wz = pastePos.z + rotZ(block.x, block.z, rot);
            if (occupiedBlocks.contains(packPos(wx, wy, wz))) {
                return true;
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════
    //  Prefab pool resolution
    // ════════════════════════════════════════════════

    @Nonnull
    private ResolvedPools resolvePools(@Nonnull DungeonConfig.LevelConfig config) {
        DungeonConfig.RoomPools global = config.getRoomPools();
        DungeonConfig.MainBranchConfig mainCfg = config.getMain();
        DungeonConfig.BranchConfig branchCfg = config.getBranch();

        List<Path> spawn     = DungeonConfig.resolveGlobs(global.getSpawn());
        List<Path> corridor  = DungeonConfig.resolveGlobs(global.getCorridor());
        List<Path> challenge = DungeonConfig.resolveGlobs(global.getChallenge());
        List<Path> treasure  = DungeonConfig.resolveGlobs(global.getTreasure());
        List<Path> shop      = DungeonConfig.resolveGlobs(global.getShop());
        List<Path> boss      = DungeonConfig.resolveGlobs(global.getBoss());
        List<Path> wall      = DungeonConfig.resolveGlobs(global.getWall());
        List<Path> keyDoor   = DungeonConfig.resolveGlobs(global.getKeyDoor());

        DungeonConfig.RoomPools mainPools = mainCfg.getRooms();
        List<Path> mainCorridor = new ArrayList<>(corridor);
        List<Path> mainChallenge = new ArrayList<>(challenge);
        if (mainPools != null) {
            mainCorridor.addAll(DungeonConfig.resolveGlobs(mainPools.getCorridor()));
            mainChallenge.addAll(DungeonConfig.resolveGlobs(mainPools.getChallenge()));
        }

        DungeonConfig.RoomPools branchPools = branchCfg.getRooms();
        List<Path> branchCorridor = new ArrayList<>(corridor);
        List<Path> branchChallenge = new ArrayList<>(challenge);
        if (branchPools != null) {
            branchCorridor.addAll(DungeonConfig.resolveGlobs(branchPools.getCorridor()));
            branchChallenge.addAll(DungeonConfig.resolveGlobs(branchPools.getChallenge()));
        }

        return new ResolvedPools(spawn, corridor, challenge, treasure, shop, boss, wall, keyDoor,
                mainCorridor, mainChallenge, branchCorridor, branchChallenge);
    }

    // ════════════════════════════════════════════════
    //  Internal data records
    // ════════════════════════════════════════════════

    private void placeFluid(@Nonnull World world, int x, int y, int z,
                            @Nonnull String fluidKey, byte level) {
        int fluidId = Fluid.getAssetMap().getIndex(fluidKey);
        if (fluidId == Integer.MIN_VALUE) return;
        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) return;
        Store<ChunkStore> store = world.getChunkStore().getStore();
        ChunkColumn column = store.getComponent(chunk.getReference(), ChunkColumn.getComponentType());
        Ref<ChunkStore> section = column.getSection(ChunkUtil.chunkCoordinate(y));
        FluidSection fluidSection = store.ensureAndGetComponent(section, FluidSection.getComponentType());
        fluidSection.setFluid(x, y, z, fluidId, level);
    }

    private record SpawnerLocal(int x, int y, int z, int rot) {
    }

    private record BlockLocal(int x, int y, int z, String name, boolean isMarker) {
    }

    private record MarkerLocal(int x, int y, int z, @Nonnull MarkerType type) {
    }

    private record PrefabMobMarkerLocal(double x, double y, double z, @Nonnull String mobId) {
    }

    private record PrefabData(List<BlockLocal> blocks, List<SpawnerLocal> spawners,
                              List<MarkerLocal> markers, List<PrefabMobMarkerLocal> mobMarkers) {
    }

    private record OpenExit(int worldX, int worldY, int worldZ, int rotation) {
    }

    private record TrackedExit(OpenExit exit, @Nullable RoomData parent, int branchDepth, String branchId) {
    }

    private record ResolvedPools(
            List<Path> spawn, List<Path> corridor, List<Path> challenge,
            List<Path> treasure, List<Path> shop, List<Path> boss,
            List<Path> wall, List<Path> keyDoor,
            List<Path> mainCorridor, List<Path> mainChallenge,
            List<Path> branchCorridor, List<Path> branchChallenge) {
    }
}
