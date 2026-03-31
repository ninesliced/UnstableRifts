package dev.ninesliced.unstablerifts.dungeon;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.PrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import dev.ninesliced.unstablerifts.logging.UnstableRiftsLog;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;

import static dev.ninesliced.unstablerifts.dungeon.RotationUtil.rotateLocalX;
import static dev.ninesliced.unstablerifts.dungeon.RotationUtil.rotateLocalZ;

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

    private static final HytaleLogger LOGGER = UnstableRiftsLog.forModule("Dungeon");
    private static final Gson GSON = new Gson();
    private static final int MAX_RETRIES = 5;

    /**
     * The level produced by the last generate() call.
     */
    @Nullable
    private dev.ninesliced.unstablerifts.dungeon.Level generatedLevel;

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
    private static PrefabData readPrefabData(@Nonnull Path path) {
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
            List<ConfiguredSpawnerLocal> configuredSpawners = new ArrayList<>();

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
                        JsonObject serializedComponents = getSerializedBlockComponents(b);
                        markers.add(new MarkerLocal(
                                x,
                                y,
                                z,
                                markerType,
                                readDoorMode(markerType, serializedComponents),
                                readPortalMode(markerType, serializedComponents)
                        ));
                        if (markerType == MarkerType.MOB_SPAWNER) {
                            JsonObject mobSpawnerData = getSerializedComponent(serializedComponents, "MobSpawnerData");
                            if (mobSpawnerData != null) {
                                String entries = getSerializedString(mobSpawnerData, "MobEntries");
                                int count = 1;
                                String rawCount = getSerializedString(mobSpawnerData, "SpawnCount");
                                if (rawCount != null) {
                                    try {
                                        count = Integer.parseInt(rawCount);
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                                if (entries != null && !entries.isEmpty()) {
                                    configuredSpawners.add(new ConfiguredSpawnerLocal(x, y, z, entries, count));
                                }
                            }
                        }
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

            return new PrefabData(blocks, spawners, markers, mobMarkers, configuredSpawners);
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Failed to read prefab: %s", path);
            return null;
        }
    }

    @Nullable
    private static JsonObject getSerializedBlockComponents(@Nonnull JsonObject block) {
        JsonObject components = block.getAsJsonObject("components");
        return components != null ? components.getAsJsonObject("Components") : null;
    }

    // ════════════════════════════════════════════════
    //  Branch building
    // ════════════════════════════════════════════════

    @Nullable
    private static JsonObject getSerializedComponent(@Nullable JsonObject serializedComponents, @Nonnull String componentId) {
        return serializedComponents != null ? serializedComponents.getAsJsonObject(componentId) : null;
    }

    @Nullable
    private static String getSerializedString(@Nullable JsonObject component, @Nonnull String key) {
        if (component == null || !component.has(key)) {
            return null;
        }
        return component.get(key).getAsString();
    }

    @Nonnull
    private static DoorMode readDoorMode(@Nonnull MarkerType markerType, @Nullable JsonObject serializedComponents) {
        String serializedMode = getSerializedString(getSerializedComponent(serializedComponents, "DoorData"), "Mode");
        if (serializedMode != null && !serializedMode.isBlank()) {
            return DoorData.parseMode(serializedMode);
        }

        return switch (markerType) {
            case DOOR_KEY -> DoorMode.KEY;
            case DOOR, DOOR_ACTIVATOR -> DoorMode.ACTIVATOR;
            default -> DoorMode.ACTIVATOR;
        };
    }

    @Nonnull
    private static PortalMode readPortalMode(@Nonnull MarkerType markerType, @Nullable JsonObject serializedComponents) {
        if (markerType != MarkerType.PORTAL) {
            return PortalMode.NEXT_LEVEL;
        }

        String serializedMode = getSerializedString(getSerializedComponent(serializedComponents, "PortalData"), "Mode");
        if (serializedMode != null && !serializedMode.isBlank()) {
            return PortalData.parseMode(serializedMode);
        }

        return PortalMode.NEXT_LEVEL;
    }

    public static void pasteLockDoor(@Nonnull World world, @Nonnull DungeonConfig.LevelConfig levelConfig,
                                     @Nonnull RoomData room, @Nonnull Vector3i pastePos, int rot) {
        pasteTrackedDoorPrefab(world, room, pastePos, rot,
                DungeonConfig.resolveGlobs(levelConfig.getRoomPools().getLockDoor()),
                "lockDoor");
    }

    public static boolean pasteSealDoorsAtRoomExits(@Nonnull World world,
                                                    @Nonnull DungeonConfig.LevelConfig levelConfig,
                                                    @Nonnull RoomData room) {
        if (room.getExitSpawners().isEmpty()) {
            return false;
        }

        int trackedBlocksBefore = room.getLockDoorBlockPositions().size();
        for (RoomData.ExitSpawner exitSpawner : room.getExitSpawners()) {
            pasteTrackedDoorPrefab(world, room, exitSpawner.position(), exitSpawner.rotation(),
                    DungeonConfig.resolveGlobs(levelConfig.getRoomPools().getSealDoor()),
                    "sealDoor");
        }

        return room.getLockDoorBlockPositions().size() > trackedBlocksBefore;
    }

    public static boolean pasteConfiguredDoorMarkers(@Nonnull World world,
                                                     @Nonnull DungeonConfig.LevelConfig levelConfig,
                                                     @Nonnull RoomData room) {
        int trackedBlocksBefore = room.getLockDoorBlockPositions().size();
        pasteConfiguredDoorMarkersForTarget(world, levelConfig, room, room);
        for (RoomData child : room.getChildren()) {
            pasteConfiguredDoorMarkersForTarget(world, levelConfig, room, child);
        }
        return room.getLockDoorBlockPositions().size() > trackedBlocksBefore;
    }

    private static void pasteConfiguredDoorMarkersForTarget(@Nonnull World world,
                                                            @Nonnull DungeonConfig.LevelConfig levelConfig,
                                                            @Nonnull RoomData trackingRoom,
                                                            @Nonnull RoomData targetRoom) {
        if (targetRoom.getDoorPositions().isEmpty()) {
            return;
        }

        List<String> globs = switch (targetRoom.getDoorMode()) {
            case KEY -> levelConfig.getRoomPools().getKeyDoor();
            case ACTIVATOR -> levelConfig.getRoomPools().getActivationDoor();
        };
        List<Path> doorPaths = DungeonConfig.resolveGlobs(globs);
        if (doorPaths.isEmpty()) {
            return;
        }

        String doorKind = targetRoom.getDoorMode() == DoorMode.KEY ? "keyDoorMarker" : "activationDoorMarker";
        for (Vector3i pos : targetRoom.getDoorPositions()) {
            pasteTrackedDoorPrefab(world, trackingRoom, pos, targetRoom.getRotation(), doorPaths, doorKind);
        }
    }

    private static void pasteTrackedDoorPrefab(@Nonnull World world,
                                               @Nonnull RoomData room,
                                               @Nonnull Vector3i pastePos,
                                               int rot,
                                               @Nonnull List<Path> doorPaths,
                                               @Nonnull String doorKind) {
        if (doorPaths.isEmpty()) {
            LOGGER.at(Level.WARNING).log("No %s prefabs configured, skipping door paste at %s", doorKind, pastePos);
            return;
        }

        Random random = new Random();
        Path doorPath = DungeonConfig.pickRandom(random, doorPaths);
        if (doorPath == null) {
            LOGGER.at(Level.WARNING).log("Failed to choose %s prefab for %s", doorKind, pastePos);
            return;
        }

        PrefabData doorData = readPrefabData(doorPath);
        if (doorData == null) {
            LOGGER.at(Level.WARNING).log("Failed to read %s prefab: %s", doorKind, doorPath);
            return;
        }

        try {
            IPrefabBuffer rawBuffer = DungeonConfig.loadBuffer(doorPath);
            if (rawBuffer == null) {
                LOGGER.at(Level.WARNING).log("Failed to load %s buffer: %s", doorKind, doorPath);
                return;
            }

            IPrefabBuffer buffer = new SpawnerFilteredBuffer(rawBuffer, pastePos.y);
            Store<EntityStore> store = world.getEntityStore().getStore();
            PrefabUtil.paste(buffer, world, pastePos, toEngineRotation(rot),
                    true, random, 0, false, false, true, store);

            for (BlockLocal block : doorData.blocks) {
                if (block.isMarker) continue;
                int wx = pastePos.x + rotateLocalX(block.x, block.z, rot);
                int wy = pastePos.y + block.y;
                int wz = pastePos.z + rotateLocalZ(block.x, block.z, rot);
                room.addLockDoorBlockPosition(new Vector3i(wx, wy, wz));
            }

            LOGGER.at(Level.INFO).log("Pasted %s %s at %s rot=%d (%d blocks tracked)",
                    doorKind, doorPath.getFileName(), pastePos, rot, room.getLockDoorBlockPositions().size());
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Failed to paste %s at %s", doorKind, pastePos);
        }
    }

    @Nullable
    public dev.ninesliced.unstablerifts.dungeon.Level getGeneratedLevel() {
        return generatedLevel;
    }

    /**
     * Generate a dungeon level in the given world.
     */
    public void generate(@Nonnull World world, long seed,
                         @Nonnull DungeonConfig.LevelConfig levelConfig) {
        Random random = new Random(seed);
        Store<EntityStore> store = world.getEntityStore().getStore();

        dev.ninesliced.unstablerifts.dungeon.Level level = new dev.ninesliced.unstablerifts.dungeon.Level(levelConfig.getName(), 0);
        this.generatedLevel = level;

        ResolvedPools resolvedPools = resolvePools(levelConfig);
        GenerationContext context = new GenerationContext(
                world,
                store,
                random,
                level,
                levelConfig,
                resolvedPools,
                new HashSet<>(),
                new BranchCounter()
        );

        LOGGER.at(Level.INFO).log("Generating dungeon '%s' seed=%d", levelConfig.getName(), seed);
        LOGGER.at(Level.INFO).log("Resolved pools: spawn=%d corridor=%d challenge=%d treasure=%d shop=%d boss=%d wall=%d keyDoor=%d",
                resolvedPools.spawn.size(), resolvedPools.corridor.size(),
                resolvedPools.challenge.size(), resolvedPools.treasure.size(),
                resolvedPools.shop.size(), resolvedPools.boss.size(),
                resolvedPools.wall.size(), resolvedPools.keyDoor.size());

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

        Vector3i spawnPaste = new Vector3i(0, 128, 0);
        pasteAndRegister(context, spawnPath, spawnData, spawnPaste, 0);

        RoomData spawnRoom = buildRoomData(level, RoomType.SPAWN, spawnData, spawnPaste, 0,
                Collections.emptyList(), 0, "main");
        level.addRoom(spawnRoom);

        List<TrackedExit> spawnExits = collectTrackedExits(spawnData, spawnPaste, 0, spawnRoom, 0, "main");
        LOGGER.at(Level.INFO).log("Spawn placed at %s, %d exit(s)", spawnPaste, spawnExits.size());

        DungeonConfig.MainBranchConfig mainConfig = levelConfig.getMain();
        List<TrackedExit> deadEndExits = new ArrayList<>();

        buildBranch(context, spawnExits, deadEndExits,
                0, "main", mainConfig.getChallengeRooms(),
                mainConfig.getMinimumCorridorLength(),
                mainConfig.getMaxRooms(), true);

        int treasureCount = mainConfig.getTreasureRooms().roll(random);
        int treasuresPlaced = 0;
        if (treasureCount > 0 && !resolvedPools.treasure.isEmpty()) {
            Collections.shuffle(deadEndExits, random);
            for (int i = 0; i < treasureCount && !deadEndExits.isEmpty(); i++) {
                TrackedExit exit = deadEndExits.remove(deadEndExits.size() - 1);
                if (!resolvedPools.keyDoor.isEmpty()) {
                    TrackedExit doorExit = placeConnectorRoom(context,
                            resolvedPools.keyDoor, exit, "KeyDoor", RoomType.WALL);
                    if (doorExit != null) {
                        exit = doorExit;
                    }
                }
                if (tryPlaceRoom(context, resolvedPools.treasure, exit, deadEndExits,
                        "Treasure", false, RoomType.TREASURE)) {
                    treasuresPlaced++;
                } else {
                    sealExit(context, exit.exit);
                }
            }
        }
        LOGGER.at(Level.INFO).log("Placed %d/%d treasure rooms", treasuresPlaced, treasureCount);

        int shopCount = mainConfig.getShopRooms().roll(random);
        int shopsPlaced = 0;
        if (shopCount > 0 && !resolvedPools.shop.isEmpty()) {
            Collections.shuffle(deadEndExits, random);
            for (int i = 0; i < shopCount && !deadEndExits.isEmpty(); i++) {
                TrackedExit exit = deadEndExits.remove(deadEndExits.size() - 1);
                if (tryPlaceRoom(context, resolvedPools.shop, exit, deadEndExits,
                        "Shop", false, RoomType.SHOP)) {
                    shopsPlaced++;
                } else {
                    sealExit(context, exit.exit);
                }
            }
        }
        LOGGER.at(Level.INFO).log("Placed %d/%d shop rooms", shopsPlaced, shopCount);

        List<String> importantGlobs = levelConfig.getImportantRooms();
        int importantPlaced = 0;
        for (String glob : importantGlobs) {
            List<Path> importantPaths = DungeonConfig.resolveGlobs(List.of(glob));
            if (importantPaths.isEmpty() || deadEndExits.isEmpty()) continue;
            TrackedExit exit = deadEndExits.remove(random.nextInt(deadEndExits.size()));
            if (tryPlaceRoom(context, importantPaths, exit, deadEndExits,
                    "Important", true, RoomType.CHALLENGE)) {
                importantPlaced++;
            } else {
                sealExit(context, exit.exit);
            }
        }
        LOGGER.at(Level.INFO).log("Placed %d/%d important rooms", importantPlaced, importantGlobs.size());

        for (TrackedExit exit : deadEndExits) {
            sealExit(context, exit.exit);
        }
        deadEndExits.clear();

        distributeMobs(level, levelConfig, random);

        if (treasuresPlaced > 0) {
            distributeKeys(level, treasuresPlaced, random);
        }

        LOGGER.at(Level.INFO).log("Dungeon complete: %d rooms, boss=%b, treasures=%d, shops=%d, branches=%d",
                level.getRooms().size(), level.getBossRoom() != null,
                treasuresPlaced, shopsPlaced, context.branchCount());
    }

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
    private void buildBranch(@Nonnull GenerationContext context,
                             @Nonnull List<TrackedExit> startExits,
                             @Nonnull List<TrackedExit> deadEndExits,
                             int branchDepth, @Nonnull String branchId,
                             @Nonnull IntRange challengeRange, int minCorridorLength,
                             int maxRooms, boolean isMainBranch) {
        BranchRoomPaths roomPaths = resolveBranchRoomPaths(context.resolvedPools(), isMainBranch);
        BranchLayoutPlan layoutPlan = planBranchLayout(
                context.random(), challengeRange, minCorridorLength, maxRooms, isMainBranch);
        logBranchLayout(branchId, layoutPlan, maxRooms);

        Set<Integer> globalSplitterSlots = planSplitterSlots(context, branchDepth, layoutPlan.totalCorridors());
        BranchState state = new BranchState(startExits);

        placeChallengeSegments(context, deadEndExits, branchDepth, branchId, roomPaths, layoutPlan,
                globalSplitterSlots, state);

        if (isMainBranch) {
            placeBossSegment(context, deadEndExits, branchDepth, branchId, roomPaths, layoutPlan,
                    globalSplitterSlots, state);
        }

        if (!state.deferredBranchExits.isEmpty()) {
            LOGGER.at(Level.INFO).log("[%s] Spawning %d deferred branch(es) from splitters",
                    branchId, state.deferredBranchExits.size());
            forceSpawnBranches(context, state.deferredBranchExits, deadEndExits, branchDepth, branchId);
        }

        LOGGER.at(Level.INFO).log("[%s] Branch complete: %d rooms placed (max %d)",
                branchId, state.roomsPlaced, maxRooms);
        deadEndExits.addAll(state.currentExits);
    }

    @Nonnull
    private BranchRoomPaths resolveBranchRoomPaths(@Nonnull ResolvedPools resolvedPools, boolean isMainBranch) {
        List<Path> corridorPaths = isMainBranch ? resolvedPools.mainCorridor : resolvedPools.branchCorridor;
        List<Path> challengePaths = isMainBranch ? resolvedPools.mainChallenge : resolvedPools.branchChallenge;
        if (corridorPaths.isEmpty()) corridorPaths = resolvedPools.corridor;
        if (challengePaths.isEmpty()) challengePaths = resolvedPools.challenge;
        return new BranchRoomPaths(corridorPaths, challengePaths);
    }

    // ════════════════════════════════════════════════
    //  Room placement
    // ════════════════════════════════════════════════

    @Nonnull
    private BranchLayoutPlan planBranchLayout(@Nonnull Random random,
                                              @Nonnull IntRange challengeRange,
                                              int minCorridorLength,
                                              int maxRooms,
                                              boolean isMainBranch) {
        int challengeCount = challengeRange.roll(random);
        int segments = challengeCount + (isMainBranch ? 1 : 0);
        if (segments == 0) segments = 1;

        while (challengeCount > 0 && (segments * minCorridorLength + challengeCount) > maxRooms) {
            challengeCount--;
            segments = challengeCount + (isMainBranch ? 1 : 0);
            if (segments == 0) segments = 1;
        }

        int corridorBudget = maxRooms - challengeCount;
        int[] corridorsPerSegment = new int[segments];
        for (int i = 0; i < segments; i++) {
            corridorsPerSegment[i] = Math.min(minCorridorLength, corridorBudget);
            corridorBudget -= corridorsPerSegment[i];
        }

        if (corridorBudget > 0) {
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < segments; i++) indices.add(i);
            Collections.shuffle(indices, random);
            for (int idx : indices) {
                if (corridorBudget <= 0) break;
                corridorsPerSegment[idx]++;
                corridorBudget--;
            }
        }

        return new BranchLayoutPlan(challengeCount, corridorsPerSegment, isMainBranch);
    }

    private void logBranchLayout(@Nonnull String branchId,
                                 @Nonnull BranchLayoutPlan layoutPlan,
                                 int maxRooms) {
        StringBuilder plan = new StringBuilder();
        int[] corridorsPerSegment = layoutPlan.corridorsPerSegment();
        for (int s = 0; s < corridorsPerSegment.length; s++) {
            if (s > 0) plan.append(" -> ");
            plan.append(corridorsPerSegment[s]).append("c");
            if (s < layoutPlan.challengeCount()) {
                plan.append(" -> [challenge]");
            } else if (layoutPlan.includesBossSegment()) {
                plan.append(" -> [boss]");
            }
        }
        LOGGER.at(Level.INFO).log("[%s] Layout plan: %s (max %d rooms, %d challenges)",
                branchId, plan, maxRooms, layoutPlan.challengeCount());
    }

    @Nonnull
    private Set<Integer> planSplitterSlots(@Nonnull GenerationContext context,
                                           int branchDepth,
                                           int totalCorridors) {
        Set<Integer> splitterSlots = new HashSet<>();
        if (branchDepth != 0 || context.resolvedPools().branch.isEmpty() || totalCorridors <= 0) {
            return splitterSlots;
        }

        int splitterCount = context.levelConfig().getBranch().getSplitterCount().roll(context.random());
        splitterCount = Math.min(splitterCount, totalCorridors);

        List<Integer> allSlots = new ArrayList<>();
        for (int i = 0; i < totalCorridors; i++) {
            allSlots.add(i);
        }
        Collections.shuffle(allSlots, context.random());
        for (int i = 0; i < splitterCount; i++) {
            splitterSlots.add(allSlots.get(i));
        }
        return splitterSlots;
    }

    // ════════════════════════════════════════════════
    //  RoomData building & marker detection
    // ════════════════════════════════════════════════

    private void placeChallengeSegments(@Nonnull GenerationContext context,
                                        @Nonnull List<TrackedExit> deadEndExits,
                                        int branchDepth,
                                        @Nonnull String branchId,
                                        @Nonnull BranchRoomPaths roomPaths,
                                        @Nonnull BranchLayoutPlan layoutPlan,
                                        @Nonnull Set<Integer> globalSplitterSlots,
                                        @Nonnull BranchState state) {
        for (int challengeIndex = 0; challengeIndex < layoutPlan.challengeCount(); challengeIndex++) {
            placeNextSegmentCorridors(context, deadEndExits, branchDepth, branchId, roomPaths,
                    globalSplitterSlots, state, layoutPlan.corridorsPerSegment()[state.segmentIndex++]);

            if (state.currentExits.isEmpty()) {
                LOGGER.at(Level.WARNING).log("[%s] No exits left for challenge %d/%d",
                        branchId, challengeIndex + 1, layoutPlan.challengeCount());
                break;
            }

            if (placeChallengeRoom(context, deadEndExits, branchDepth, branchId, roomPaths.challenge(),
                    challengeIndex, state)) {
                state.roomsPlaced++;
            }
        }
    }

    private void placeNextSegmentCorridors(@Nonnull GenerationContext context,
                                           @Nonnull List<TrackedExit> deadEndExits,
                                           int branchDepth,
                                           @Nonnull String branchId,
                                           @Nonnull BranchRoomPaths roomPaths,
                                           @Nonnull Set<Integer> globalSplitterSlots,
                                           @Nonnull BranchState state,
                                           int corridorsToPlace) {
        if (corridorsToPlace <= 0) {
            return;
        }

        Set<Integer> segmentSplitters = extractSegmentSplitters(globalSplitterSlots, state.corridorOffset, corridorsToPlace);
        state.corridorOffset += corridorsToPlace;
        state.roomsPlaced += placeCorridorChainCounted(context, state.currentExits, deadEndExits,
                roomPaths.corridor(), corridorsToPlace, branchDepth, branchId,
                state.deferredBranchExits, segmentSplitters);
    }

    @Nonnull
    private Set<Integer> extractSegmentSplitters(@Nonnull Set<Integer> globalSplitterSlots,
                                                 int corridorOffset,
                                                 int corridorsToPlace) {
        Set<Integer> segmentSplitters = new HashSet<>();
        for (int i = 0; i < corridorsToPlace; i++) {
            if (globalSplitterSlots.contains(corridorOffset + i)) {
                segmentSplitters.add(i);
            }
        }
        return segmentSplitters;
    }

    // ════════════════════════════════════════════════
    //  Exit collection & sealing
    // ════════════════════════════════════════════════

    private boolean placeChallengeRoom(@Nonnull GenerationContext context,
                                       @Nonnull List<TrackedExit> deadEndExits,
                                       int branchDepth,
                                       @Nonnull String branchId,
                                       @Nonnull List<Path> challengePaths,
                                       int challengeIndex,
                                       @Nonnull BranchState state) {
        TrackedExit challengeExit = state.currentExits.remove(context.random().nextInt(state.currentExits.size()));
        List<TrackedExit> newExits = new ArrayList<>();
        if (!tryPlaceRoom(context, challengePaths, challengeExit, newExits,
                branchId + "-Challenge-" + (challengeIndex + 1), true, RoomType.CHALLENGE,
                branchDepth, branchId)) {
            return false;
        }

        if (!newExits.isEmpty()) {
            TrackedExit primary = newExits.remove(0);
            state.currentExits.add(primary);
            handleExtraExits(context, newExits, state.currentExits, deadEndExits, branchDepth, branchId);
        }
        return true;
    }

    private void placeBossSegment(@Nonnull GenerationContext context,
                                  @Nonnull List<TrackedExit> deadEndExits,
                                  int branchDepth,
                                  @Nonnull String branchId,
                                  @Nonnull BranchRoomPaths roomPaths,
                                  @Nonnull BranchLayoutPlan layoutPlan,
                                  @Nonnull Set<Integer> globalSplitterSlots,
                                  @Nonnull BranchState state) {
        if (state.segmentIndex < layoutPlan.corridorsPerSegment().length) {
            placeNextSegmentCorridors(context, deadEndExits, branchDepth, branchId, roomPaths,
                    globalSplitterSlots, state, layoutPlan.corridorsPerSegment()[state.segmentIndex]);
        }

        boolean bossPlaced = false;
        if (!context.resolvedPools().boss.isEmpty() && !state.currentExits.isEmpty()) {
            Collections.shuffle(state.currentExits, context.random());
            while (!state.currentExits.isEmpty() && !bossPlaced) {
                TrackedExit bossExit = state.currentExits.remove(0);
                List<TrackedExit> bossNewExits = new ArrayList<>();
                if (tryPlaceRoom(context, context.resolvedPools().boss, bossExit, bossNewExits,
                        "Boss", true, RoomType.BOSS, branchDepth, branchId)) {
                    bossPlaced = true;
                    for (TrackedExit exit : bossNewExits) {
                        sealExit(context, exit.exit);
                    }
                } else {
                    sealExit(context, bossExit.exit);
                }
            }
        }

        if (!bossPlaced) {
            LOGGER.at(Level.WARNING).log("Failed to place boss room!");
        }
    }

    // ════════════════════════════════════════════════
    //  Key distribution
    // ════════════════════════════════════════════════

    /**
     * Place a chain of corridors and handle branch forking from extra exits.
     * Mutates {@code currentExits} in place and returns the number of corridors actually placed.
     */
    private int placeCorridorChainCounted(@Nonnull GenerationContext context,
                                          @Nonnull List<TrackedExit> currentExits,
                                          @Nonnull List<TrackedExit> deadEndExits,
                                          @Nonnull List<Path> corridorPaths,
                                          int count, int branchDepth, @Nonnull String branchId,
                                          @Nonnull List<TrackedExit> deferredBranchExits,
                                          @Nonnull Set<Integer> splitterSlots) {
        int placed = 0;

        for (int i = 0; i < count; i++) {
            if (currentExits.isEmpty()) break;

            TrackedExit exit = currentExits.remove(context.random().nextInt(currentExits.size()));
            List<TrackedExit> newExits = new ArrayList<>();

            boolean useSplitter = splitterSlots.remove(i)
                    && !context.resolvedPools().branch.isEmpty();

            boolean success;
            if (useSplitter) {
                success = tryPlaceRoom(context, context.resolvedPools().branch, exit, newExits,
                        branchId + "-Splitter-" + (i + 1), false, RoomType.CORRIDOR,
                        branchDepth, branchId);
                if (!success) {
                    success = !corridorPaths.isEmpty() && tryPlaceRoom(context, corridorPaths, exit, newExits,
                            branchId + "-Corridor-" + (i + 1), false, RoomType.CORRIDOR, branchDepth, branchId);
                }
            } else {
                success = !corridorPaths.isEmpty() && tryPlaceRoom(context, corridorPaths, exit, newExits,
                        branchId + "-Corridor-" + (i + 1), false, RoomType.CORRIDOR, branchDepth, branchId);
            }

            if (!success) {
                sealExit(context, exit.exit);
                continue;
            }

            placed++;
            if (!newExits.isEmpty()) {
                if (useSplitter && newExits.size() > 1) {
                    // Pick the exit most aligned with the incoming direction as main continuation
                    int incomingRot = exit.exit.rotation();
                    int bestIdx = 0;
                    for (int j = 0; j < newExits.size(); j++) {
                        if (newExits.get(j).exit.rotation() == incomingRot) {
                            bestIdx = j;
                            break;
                        }
                    }
                    TrackedExit primary = newExits.remove(bestIdx);
                    currentExits.add(primary);
                    // Defer branch exits — build them after the main path is complete
                    deferredBranchExits.addAll(newExits);
                } else {
                    TrackedExit primary = newExits.remove(0);
                    currentExits.add(primary);
                    if (useSplitter) {
                        deferredBranchExits.addAll(newExits);
                    } else {
                        handleExtraExits(context, newExits, currentExits, deadEndExits,
                                branchDepth, branchId);
                    }
                }
            }
        }
        return placed;
    }

    /**
     * For each extra exit (beyond the primary continuation), try to fork a side branch.
     * If forking fails or isn't rolled, add to dead end exits.
     */
    private void handleExtraExits(@Nonnull GenerationContext context,
                                  @Nonnull List<TrackedExit> extraExits,
                                  @Nonnull List<TrackedExit> currentExits,
                                  @Nonnull List<TrackedExit> deadEndExits,
                                  int currentBranchDepth, @Nonnull String currentBranchId) {
        DungeonConfig.BranchConfig branchConfig = context.levelConfig().getBranch();

        for (TrackedExit extra : extraExits) {
            int newDepth = currentBranchDepth + 1;
            double forkProb = branchConfig.getForkProbability(newDepth);

            if (forkProb > 0 && context.random().nextDouble() < forkProb) {
                String newBranchId = "branch-" + context.nextBranchIndex();
                LOGGER.at(Level.INFO).log("Forking %s (depth %d) from %s", newBranchId, newDepth, currentBranchId);

                List<TrackedExit> branchStarts = new ArrayList<>();
                branchStarts.add(new TrackedExit(extra.exit, extra.parent, newDepth, newBranchId));

                buildBranch(context, branchStarts, deadEndExits,
                        newDepth, newBranchId,
                        branchConfig.getChallengeRooms(),
                        branchConfig.getMinimumCorridorLength(),
                        branchConfig.getMaxRooms(),
                        false);
            } else {
                deadEndExits.add(extra);
            }
        }
    }

    // ════════════════════════════════════════════════
    //  Prefab I/O
    // ════════════════════════════════════════════════

    /**
     * Force-fork all extra exits into side branches (used by splitter rooms).
     */
    private void forceSpawnBranches(@Nonnull GenerationContext context,
                                    @Nonnull List<TrackedExit> extraExits,
                                    @Nonnull List<TrackedExit> deadEndExits,
                                    int currentBranchDepth, @Nonnull String currentBranchId) {
        DungeonConfig.BranchConfig branchConfig = context.levelConfig().getBranch();

        for (TrackedExit extra : extraExits) {
            int newDepth = currentBranchDepth + 1;
            String newBranchId = "branch-" + context.nextBranchIndex();
            LOGGER.at(Level.INFO).log("Splitter forking %s (depth %d) from %s", newBranchId, newDepth, currentBranchId);

            List<TrackedExit> branchStarts = new ArrayList<>();
            branchStarts.add(new TrackedExit(extra.exit, extra.parent, newDepth, newBranchId));

            buildBranch(context, branchStarts, deadEndExits,
                    newDepth, newBranchId,
                    branchConfig.getChallengeRooms(),
                    branchConfig.getMinimumCorridorLength(),
                    branchConfig.getMaxRooms(),
                    false);
        }
    }

    /**
     * Try to place a room from the given prefab pool at the given exit.
     * Uses the default branch depth/id from the exit.
     */
    private boolean tryPlaceRoom(@Nonnull GenerationContext context,
                                 @Nonnull List<Path> roomPaths,
                                 @Nonnull TrackedExit trackedExit,
                                 @Nonnull List<TrackedExit> newExitsOut,
                                 @Nonnull String label, boolean forceOnFailure,
                                 @Nonnull RoomType roomType) {
        return tryPlaceRoom(context, roomPaths, trackedExit, newExitsOut,
                label, forceOnFailure, roomType,
                trackedExit.branchDepth, trackedExit.branchId);
    }

    /**
     * Try to place a room from the given prefab pool at the given exit.
     */
    private boolean tryPlaceRoom(@Nonnull GenerationContext context,
                                 @Nonnull List<Path> roomPaths,
                                 @Nonnull TrackedExit trackedExit,
                                 @Nonnull List<TrackedExit> newExitsOut,
                                 @Nonnull String label, boolean forceOnFailure,
                                 @Nonnull RoomType roomType,
                                 int branchDepth, @Nonnull String branchId) {
        if (roomPaths.isEmpty()) {
            LOGGER.at(Level.WARNING).log("[%s] No prefabs available for type %s", label, roomType);
            return false;
        }

        List<Path> shuffled = new ArrayList<>(roomPaths);
        Collections.shuffle(shuffled, context.random());

        OpenExit exit = trackedExit.exit;
        Vector3i pastePos = new Vector3i(exit.worldX, exit.worldY, exit.worldZ);
        int pasteRot = (exit.rotation + 2) & 3;
        int attempts = Math.min(MAX_RETRIES, shuffled.size());

        for (int i = 0; i < attempts; i++) {
            Path chosen = shuffled.get(i);
            PrefabData data = readPrefabData(chosen);
            if (data == null) continue;

            if (wouldOverlap(context, data, pastePos, pasteRot)) {
                LOGGER.at(Level.FINE).log("[%s] overlap retry %d/%d %s", label, i + 1, attempts, chosen.getFileName());
                continue;
            }

            try {
                pasteAndRegister(context, chosen, data, pastePos, pasteRot);
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).withCause(e).log("[%s] paste failed %s", label, chosen.getFileName());
                continue;
            }

            RoomData roomData = buildRoomData(context.level(), roomType, data, pastePos, pasteRot,
                    Collections.emptyList(), branchDepth, branchId);
            if (trackedExit.parent != null) {
                trackedExit.parent.addChild(roomData);
            }
            context.level().addRoom(roomData);

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
                    pasteAndRegister(context, chosen, data, pastePos, pasteRot);
                    RoomData roomData = buildRoomData(context.level(), roomType, data, pastePos, pasteRot,
                            Collections.emptyList(), branchDepth, branchId);
                    if (trackedExit.parent != null) {
                        trackedExit.parent.addChild(roomData);
                    }
                    context.level().addRoom(roomData);
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
        sealExit(context, exit);
        return false;
    }

    /**
     * Place a single "connector" room (like a key door) and return the first exit, or null on failure.
     */
    @Nullable
    private TrackedExit placeConnectorRoom(@Nonnull GenerationContext context,
                                           @Nonnull List<Path> roomPaths,
                                           @Nonnull TrackedExit entrance,
                                           @Nonnull String label, @Nonnull RoomType roomType) {
        List<TrackedExit> newExits = new ArrayList<>();
        if (tryPlaceRoom(context, roomPaths, entrance, newExits, label, false, roomType)) {
            if (!newExits.isEmpty()) {
                TrackedExit primary = newExits.remove(0);
                for (TrackedExit extra : newExits) {
                    sealExit(context, extra.exit);
                }
                return primary;
            }
        }
        return null;
    }

    @Nonnull
    private RoomData buildRoomData(@Nonnull dev.ninesliced.unstablerifts.dungeon.Level level,
                                   @Nonnull RoomType type, @Nonnull PrefabData data,
                                   @Nonnull Vector3i pastePos, int rot,
                                   @Nonnull List<String> mobs,
                                   int branchDepth, @Nonnull String branchId) {
        List<Vector3i> spawnerPositions = computeWorldPositions(data.spawners, pastePos, rot);
        List<Vector3d> mobMarkerPositions = computeWorldDoublePositions(data.mobMarkers, pastePos, rot);

        RoomData roomData = new RoomData(type, pastePos, rot,
                spawnerPositions, mobMarkerPositions, mobs, branchDepth, branchId);

        for (SpawnerLocal spawner : data.spawners) {
            int wx = pastePos.x + rotateLocalX(spawner.x, spawner.z, rot);
            int wy = pastePos.y + spawner.y;
            int wz = pastePos.z + rotateLocalZ(spawner.x, spawner.z, rot);
            int exitRotation = (rot + spawner.rot) & 3;
            roomData.addExitSpawner(new Vector3i(wx, wy, wz), exitRotation);
        }

        for (MarkerLocal marker : data.markers) {
            int wx = pastePos.x + rotateLocalX(marker.x, marker.z, rot);
            int wy = pastePos.y + marker.y;
            int wz = pastePos.z + rotateLocalZ(marker.x, marker.z, rot);
            Vector3i worldPos = new Vector3i(wx, wy, wz);

            switch (marker.type) {
                case MOB_SPAWN_POINT, MOB_SPAWNER -> roomData.addMobSpawnPoint(worldPos);
                case KEY_SPAWNER -> roomData.addKeySpawnerPosition(worldPos);
                case PORTAL -> roomData.addPortal(worldPos, marker.portalMode);
                case PORTAL_EXIT -> roomData.addPortalExitPosition(worldPos);
                case DOOR -> {
                    roomData.addDoorPosition(worldPos);
                    roomData.setDoorMode(marker.doorMode);
                }
                case DOOR_KEY -> {
                    roomData.addDoorPosition(worldPos);
                    roomData.setDoorMode(marker.doorMode);
                }
                case DOOR_ACTIVATOR -> {
                    roomData.addDoorPosition(worldPos);
                    roomData.setDoorMode(marker.doorMode);
                }
                case LOCK_ROOM -> roomData.setLocked(true);
                case ACTIVATION_ZONE -> roomData.addActivationZonePosition(worldPos);
                case MOB_ACTIVATOR -> roomData.addMobActivatorPosition(worldPos);
                case MOB_CLEAR_ACTIVATOR -> roomData.setHasMobClearActivator(true);
                default -> {
                }
            }
        }

        for (ConfiguredSpawnerLocal cs : data.configuredSpawners) {
            int wx = pastePos.x + rotateLocalX(cs.x, cs.z, rot);
            int wy = pastePos.y + cs.y;
            int wz = pastePos.z + rotateLocalZ(cs.x, cs.z, rot);
            Vector3i pos = new Vector3i(wx, wy, wz);

            List<MobSpawnerData.MobEntryData> entries = MobSpawnerData.parseEntries(cs.mobEntries);
            if (!entries.isEmpty()) {
                int totalWeight = 0;
                for (MobSpawnerData.MobEntryData e : entries) totalWeight += e.weight();
                if (totalWeight > 0) {
                    Random rng = new Random();
                    for (int i = 0; i < cs.spawnCount; i++) {
                        int roll = rng.nextInt(totalWeight);
                        for (MobSpawnerData.MobEntryData e : entries) {
                            roll -= e.weight();
                            if (roll < 0) {
                                roomData.addPinnedMobSpawn(pos, e.mobId());
                                break;
                            }
                        }
                    }
                }
            }
        }

        for (Vector3i pos : roomData.getActivationZonePositions()) {
            roomData.addChallenge(new ChallengeObjective(ChallengeObjective.Type.ACTIVATION_ZONE, pos));
        }
        for (Vector3i pos : roomData.getMobActivatorPositions()) {
            roomData.addChallenge(new ChallengeObjective(ChallengeObjective.Type.MOB_ACTIVATOR, pos));
        }
        if (roomData.hasMobClearActivator()) {
            roomData.addChallenge(new ChallengeObjective(ChallengeObjective.Type.MOB_CLEAR, roomData.getAnchor()));
        }
        if (!roomData.getChallenges().isEmpty()) {
            roomData.setLocked(true);
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockLocal block : data.blocks) {
            if (block.isMarker) continue;
            int wx = pastePos.x + rotateLocalX(block.x, block.z, rot);
            int wy = pastePos.y + block.y;
            int wz = pastePos.z + rotateLocalZ(block.x, block.z, rot);
            minX = Math.min(minX, wx);
            maxX = Math.max(maxX, wx);
            minY = Math.min(minY, wy);
            maxY = Math.max(maxY, wy);
            minZ = Math.min(minZ, wz);
            maxZ = Math.max(maxZ, wz);
            level.setBlockOwner(wx, wz, roomData);
        }
        if (minX <= maxX && minZ <= maxZ) {
            roomData.setBounds(minX, minZ, maxX, maxZ);
            roomData.setYBounds(minY, maxY);
        }

        return roomData;
    }

    @Nonnull
    private List<Vector3i> computeWorldPositions(@Nonnull List<SpawnerLocal> spawners,
                                                 @Nonnull Vector3i pastePos, int rot) {
        List<Vector3i> positions = new ArrayList<>();
        for (SpawnerLocal sp : spawners) {
            int wx = pastePos.x + rotateLocalX(sp.x, sp.z, rot);
            int wy = pastePos.y + sp.y;
            int wz = pastePos.z + rotateLocalZ(sp.x, sp.z, rot);
            positions.add(new Vector3i(wx, wy, wz));
        }
        return positions;
    }

    @Nonnull
    private List<Vector3d> computeWorldDoublePositions(@Nonnull List<PrefabMobMarkerLocal> markers,
                                                       @Nonnull Vector3i pastePos, int rot) {
        List<Vector3d> positions = new ArrayList<>();
        for (PrefabMobMarkerLocal marker : markers) {
            double wx = pastePos.x + rotateLocalX(marker.x, marker.z, rot);
            double wy = pastePos.y + marker.y;
            double wz = pastePos.z + rotateLocalZ(marker.x, marker.z, rot);
            positions.add(new Vector3d(wx, wy, wz));
        }
        return positions;
    }

    @Nonnull
    private List<TrackedExit> collectTrackedExits(@Nonnull PrefabData data, @Nonnull Vector3i pastePos,
                                                  int parentRot, @Nonnull RoomData parent,
                                                  int branchDepth, @Nonnull String branchId) {
        List<TrackedExit> exits = new ArrayList<>();
        for (SpawnerLocal sp : data.spawners) {
            int wx = pastePos.x + rotateLocalX(sp.x, sp.z, parentRot);
            int wy = pastePos.y + sp.y;
            int wz = pastePos.z + rotateLocalZ(sp.x, sp.z, parentRot);
            int exitRot = (parentRot + sp.rot) & 3;
            exits.add(new TrackedExit(new OpenExit(wx, wy, wz, exitRot), parent, branchDepth, branchId));
        }
        return exits;
    }

    private void sealExit(@Nonnull GenerationContext context, @Nonnull OpenExit exit) {
        if (!context.resolvedPools().wall.isEmpty()) {
            Path wallPath = DungeonConfig.pickRandom(context.random(), context.resolvedPools().wall);
            if (wallPath != null) {
                PrefabData wallData = readPrefabData(wallPath);
                if (wallData != null) {
                    int placed = 0;
                    for (BlockLocal block : wallData.blocks) {
                        int wx = exit.worldX + rotateLocalX(block.x, block.z, exit.rotation);
                        int wy = exit.worldY + block.y;
                        int wz = exit.worldZ + rotateLocalZ(block.x, block.z, exit.rotation);
                        try {
                            context.world().setBlock(wx, wy, wz, block.name, 0);
                            context.occupiedBlocks().add(packPos(wx, wy, wz));
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
            context.world().setBlock(exit.worldX, exit.worldY, exit.worldZ, DungeonConstants.EMPTY_BLOCK, 0);
        } catch (Exception e) {
            LOGGER.at(Level.FINE).withCause(e).log("Failed to clear exit block at %d,%d,%d", exit.worldX, exit.worldY, exit.worldZ);
        }
    }

    /**
     * Distribute mobs across eligible rooms using the level's mob pool.
     * <p>
     * {@code UnstableRifts_Mob_Spawn_Point} markers are candidate locations — not every
     * marker gets a mob. The total mob count is rolled from the config's
     * {@code mobsToSpawn} range, then distributed proportionally: rooms with more
     * spawn points receive more mobs.
     */
    private void distributeMobs(@Nonnull dev.ninesliced.unstablerifts.dungeon.Level level,
                                @Nonnull DungeonConfig.LevelConfig levelConfig,
                                @Nonnull Random random) {
        WeightedPool<String> mobPool = levelConfig.getMobPool();
        if (mobPool == null || mobPool.isEmpty()) {
            LOGGER.at(Level.WARNING).log("No mob pool configured for level '%s'", levelConfig.getName());
            return;
        }

        List<RoomData> eligible = new ArrayList<>();
        int totalSpawnPoints = 0;
        for (RoomData room : level.getRooms()) {
            int points = room.getMobSpawnPoints().size();
            if (points > 0) {
                eligible.add(room);
                totalSpawnPoints += points;
            }
        }

        if (eligible.isEmpty() || totalSpawnPoints == 0) {
            LOGGER.at(Level.WARNING).log("No eligible rooms with spawn points for mob distribution");
            return;
        }

        int totalMobs = levelConfig.getMain().getMobsToSpawn().roll(random);

        int assigned = 0;
        for (int r = 0; r < eligible.size(); r++) {
            RoomData room = eligible.get(r);
            int points = room.getMobSpawnPoints().size();
            int roomMobs;
            if (r == eligible.size() - 1) {
                roomMobs = totalMobs - assigned;
            } else {
                roomMobs = Math.round((float) totalMobs * points / totalSpawnPoints);
            }
            roomMobs = Math.max(0, Math.min(roomMobs, points));

            for (int i = 0; i < roomMobs; i++) {
                room.addMobToSpawn(mobPool.pick(random));
            }
            assigned += roomMobs;
        }

        LOGGER.at(Level.INFO).log("Distributed %d mobs across %d rooms (%d spawn points available, pool=%d entries)",
                assigned, eligible.size(), totalSpawnPoints, mobPool.size());
    }

    /**
     * Find key spawner positions across the level and select N for placement.
     */
    private void distributeKeys(@Nonnull dev.ninesliced.unstablerifts.dungeon.Level level, int keyCount, @Nonnull Random random) {
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

    private void pasteAndRegister(@Nonnull GenerationContext context,
                                  @Nonnull Path prefabPath,
                                  @Nonnull PrefabData data, @Nonnull Vector3i pastePos,
                                  int rot) {
        IPrefabBuffer rawBuffer = DungeonConfig.loadBuffer(prefabPath);
        if (rawBuffer == null) {
            throw new RuntimeException("Failed to load buffer: " + prefabPath);
        }

        IPrefabBuffer buffer = new SpawnerFilteredBuffer(rawBuffer, pastePos.y);
        PrefabUtil.paste(buffer, context.world(), pastePos, toEngineRotation(rot),
                true, context.random(), 0, false, false, true, context.store());

        for (BlockLocal block : data.blocks) {
            if (!block.isMarker) {
                int wx = pastePos.x + rotateLocalX(block.x, block.z, rot);
                int wy = pastePos.y + block.y;
                int wz = pastePos.z + rotateLocalZ(block.x, block.z, rot);
                context.occupiedBlocks().add(packPos(wx, wy, wz));
            }
        }

        for (BlockLocal block : data.blocks) {
            if (block.isMarker) {
                int wx = pastePos.x + rotateLocalX(block.x, block.z, rot);
                int wy = pastePos.y + block.y;
                int wz = pastePos.z + rotateLocalZ(block.x, block.z, rot);
                try {
                    MarkerType mt = MarkerType.fromBlockName(block.name);
                    context.world().setBlock(wx, wy, wz, DungeonConstants.EMPTY_BLOCK, 0);
                    if (mt != null) {
                        switch (mt) {
                            case WATER -> placeFluid(context.world(), wx, wy, wz, "Water", (byte) 8);
                            case TAR -> placeFluid(context.world(), wx, wy, wz, "Tar", (byte) 8);
                            case POISON -> placeFluid(context.world(), wx, wy, wz, "Poison", (byte) 8);
                            case LAVA -> placeFluid(context.world(), wx, wy, wz, "Lava", (byte) 8);
                            case SLIME -> placeFluid(context.world(), wx, wy, wz, "Slime", (byte) 8);
                            case RED_SLIME -> placeFluid(context.world(), wx, wy, wz, "Red_Slime", (byte) 8);
                            default -> {
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.at(Level.FINE).withCause(e).log("Failed to process marker block at %d,%d,%d", wx, wy, wz);
                }
            }
        }
    }

    private boolean wouldOverlap(@Nonnull GenerationContext context,
                                 @Nonnull PrefabData data,
                                 @Nonnull Vector3i pastePos, int rot) {
        for (BlockLocal block : data.blocks) {
            if (block.isMarker) continue;
            int wx = pastePos.x + rotateLocalX(block.x, block.z, rot);
            int wy = pastePos.y + block.y;
            int wz = pastePos.z + rotateLocalZ(block.x, block.z, rot);
            if (context.occupiedBlocks().contains(packPos(wx, wy, wz))) {
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

        List<Path> spawn = DungeonConfig.resolveGlobs(global.getSpawn());
        List<Path> corridor = DungeonConfig.resolveGlobs(global.getCorridor());
        List<Path> challenge = DungeonConfig.resolveGlobs(global.getChallenge());
        List<Path> treasure = DungeonConfig.resolveGlobs(global.getTreasure());
        List<Path> shop = DungeonConfig.resolveGlobs(global.getShop());
        List<Path> boss = DungeonConfig.resolveGlobs(global.getBoss());
        List<Path> wall = DungeonConfig.resolveGlobs(global.getWall());
        List<Path> keyDoor = DungeonConfig.resolveGlobs(global.getKeyDoor());
        List<Path> lockDoor = DungeonConfig.resolveGlobs(global.getLockDoor());
        List<Path> branch = DungeonConfig.resolveGlobs(global.getBranch());

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

        return new ResolvedPools(spawn, corridor, challenge, treasure, shop, boss, wall, keyDoor, lockDoor, branch,
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

    private record MarkerLocal(int x, int y, int z,
                               @Nonnull MarkerType type,
                               @Nonnull DoorMode doorMode,
                               @Nonnull PortalMode portalMode) {
    }

    private record PrefabMobMarkerLocal(double x, double y, double z, @Nonnull String mobId) {
    }

    private record ConfiguredSpawnerLocal(int x, int y, int z,
                                          @Nonnull String mobEntries, int spawnCount) {
    }

    private record PrefabData(List<BlockLocal> blocks, List<SpawnerLocal> spawners,
                              List<MarkerLocal> markers, List<PrefabMobMarkerLocal> mobMarkers,
                              List<ConfiguredSpawnerLocal> configuredSpawners) {
    }

    private record OpenExit(int worldX, int worldY, int worldZ, int rotation) {
    }

    private record TrackedExit(OpenExit exit, @Nullable RoomData parent, int branchDepth, String branchId) {
    }

    private record BranchRoomPaths(List<Path> corridor, List<Path> challenge) {
    }

    private record BranchLayoutPlan(int challengeCount,
                                    int[] corridorsPerSegment,
                                    boolean includesBossSegment) {
        private int totalCorridors() {
            int total = 0;
            for (int corridors : corridorsPerSegment) {
                total += corridors;
            }
            return total;
        }
    }

    private record ResolvedPools(
            List<Path> spawn, List<Path> corridor, List<Path> challenge,
            List<Path> treasure, List<Path> shop, List<Path> boss,
            List<Path> wall, List<Path> keyDoor, List<Path> lockDoor, List<Path> branch,
            List<Path> mainCorridor, List<Path> mainChallenge,
            List<Path> branchCorridor, List<Path> branchChallenge) {
    }

    private record GenerationContext(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Random random,
            @Nonnull dev.ninesliced.unstablerifts.dungeon.Level level,
            @Nonnull DungeonConfig.LevelConfig levelConfig,
            @Nonnull ResolvedPools resolvedPools,
            @Nonnull Set<Long> occupiedBlocks,
            @Nonnull BranchCounter branchCounter) {

        private int nextBranchIndex() {
            return branchCounter.next();
        }

        private int branchCount() {
            return branchCounter.get();
        }
    }

    private static final class BranchCounter {
        private int value;

        private int next() {
            return ++value;
        }

        private int get() {
            return value;
        }
    }

    private static final class BranchState {
        private final List<TrackedExit> currentExits;
        private final List<TrackedExit> deferredBranchExits = new ArrayList<>();
        private int roomsPlaced;
        private int segmentIndex;
        private int corridorOffset;

        private BranchState(@Nonnull List<TrackedExit> startExits) {
            this.currentExits = new ArrayList<>(startExits);
        }
    }

    /**
     * Wraps an IPrefabBuffer to skip Prefab_Spawner_Block entries (handled as exit markers)
     * and blocks that would land outside valid world Y range (where chunk sections don't exist).
     */
    private static final class SpawnerFilteredBuffer implements IPrefabBuffer {
        private final IPrefabBuffer delegate;
        private final int spawnerBlockId;
        private final int pasteY;

        SpawnerFilteredBuffer(@Nonnull IPrefabBuffer delegate, int pasteY) {
            this.delegate = delegate;
            this.spawnerBlockId = BlockType.getAssetMap().getIndex("Prefab_Spawner_Block");
            this.pasteY = pasteY;
        }

        @Override
        public int getAnchorX() {
            return delegate.getAnchorX();
        }

        @Override
        public int getAnchorY() {
            return delegate.getAnchorY();
        }

        @Override
        public int getAnchorZ() {
            return delegate.getAnchorZ();
        }

        @Override
        public int getMinX(@Nonnull PrefabRotation r) {
            return delegate.getMinX(r);
        }

        @Override
        public int getMinY() {
            return delegate.getMinY();
        }

        @Override
        public int getMinZ(@Nonnull PrefabRotation r) {
            return delegate.getMinZ(r);
        }

        @Override
        public int getMaxX(@Nonnull PrefabRotation r) {
            return delegate.getMaxX(r);
        }

        @Override
        public int getMaxY() {
            return delegate.getMaxY();
        }

        @Override
        public int getMaxZ(@Nonnull PrefabRotation r) {
            return delegate.getMaxZ(r);
        }

        @Override
        public int getMinYAt(@Nonnull PrefabRotation r, int x, int z) {
            return delegate.getMinYAt(r, x, z);
        }

        @Override
        public int getMaxYAt(@Nonnull PrefabRotation r, int x, int z) {
            return delegate.getMaxYAt(r, x, z);
        }

        @Override
        public int getColumnCount() {
            return delegate.getColumnCount();
        }

        @Override
        @Nonnull
        public PrefabBuffer.ChildPrefab[] getChildPrefabs() {
            return delegate.getChildPrefabs();
        }

        @Override
        public int getBlockId(int x, int y, int z) {
            return delegate.getBlockId(x, y, z);
        }

        @Override
        public int getFiller(int x, int y, int z) {
            return delegate.getFiller(x, y, z);
        }

        @Override
        public int getRotationIndex(int x, int y, int z) {
            return delegate.getRotationIndex(x, y, z);
        }

        @Override
        public <T extends PrefabBufferCall> void forEach(
                @Nonnull ColumnPredicate<T> columnPredicate,
                @Nonnull BlockConsumer<T> blockConsumer,
                @Nullable EntityConsumer<T> entityConsumer,
                @Nullable ChildConsumer<T> childConsumer,
                @Nonnull T t) {
            delegate.forEach(columnPredicate, (x, y, z, blockId, holder, supportValue, rotation, filler, call, fluidId, fluidLevel) -> {
                if (blockId == spawnerBlockId) {
                    return; // Skip entirely — replaced with Empty after paste
                }
                int worldY = pasteY + y;
                if (worldY < 0 || worldY >= 256) {
                    return; // Skip blocks outside world bounds — no chunk section exists
                }
                blockConsumer.accept(x, y, z, blockId, holder, supportValue, rotation, filler, call, fluidId, fluidLevel);
            }, entityConsumer, childConsumer, t);
        }

        @Override
        public <T> void forEachRaw(
                @Nonnull ColumnPredicate<T> columnPredicate,
                @Nonnull RawBlockConsumer<T> blockConsumer,
                @Nonnull FluidConsumer<T> fluidConsumer,
                @Nullable EntityConsumer<T> entityConsumer,
                @Nullable T t) {
            delegate.forEachRaw(columnPredicate, blockConsumer, fluidConsumer, entityConsumer, t);
        }

        @Override
        public <T> boolean forEachRaw(
                @Nonnull ColumnPredicate<T> columnPredicate,
                @Nonnull RawBlockPredicate<T> blockPredicate,
                @Nonnull FluidPredicate<T> fluidPredicate,
                @Nullable EntityPredicate<T> entityPredicate,
                @Nullable T t) {
            return delegate.forEachRaw(columnPredicate, blockPredicate, fluidPredicate, entityPredicate, t);
        }
    }
}
