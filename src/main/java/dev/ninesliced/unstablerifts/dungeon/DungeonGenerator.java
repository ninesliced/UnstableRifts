package dev.ninesliced.unstablerifts.dungeon;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.PrefabBuffer;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventItemMerging;
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
import java.util.function.Consumer;
import java.util.logging.Level;

import static dev.ninesliced.unstablerifts.dungeon.RotationUtil.rotateLocalX;
import static dev.ninesliced.unstablerifts.dungeon.RotationUtil.rotateLocalZ;
import static dev.ninesliced.unstablerifts.dungeon.RotationUtil.rotationIndexToYaw;
import static dev.ninesliced.unstablerifts.dungeon.RotationUtil.rotationIndexToYawDegrees;

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
    private static final String KEY_ITEM_ID = "UnstableRifts_Key_Item";
    private static final double KEY_OVERLAP_RADIUS = 0.2;
    private static final float INITIAL_PROGRESS = 0.02f;
    private static final float SPAWN_PROGRESS = 0.08f;
    private static final float ROOM_LAYOUT_START_PROGRESS = 0.08f;
    private static final float ROOM_LAYOUT_END_PROGRESS = 0.72f;
    private static final float DOOR_SETUP_PROGRESS = 0.80f;
    private static final float MOB_SETUP_PROGRESS = 0.88f;
    private static final float KEY_SETUP_PROGRESS = 0.94f;
    private static final float PRESPAWN_PROGRESS = 0.98f;

    /**
     * Number of rooms (from spawn outward) to spawn mobs in during generation.
     */
    private static final int EARLY_SPAWN_ROOM_COUNT = 3;

    /**
     * The level produced by the last generate() call.
     */
    @Nullable
    private dev.ninesliced.unstablerifts.dungeon.Level generatedLevel;

    /**
     * Rooms whose mobs were pre-spawned during generation (before onGameStart).
     */
    @Nonnull
    private Set<RoomData> preSpawnedRooms = new HashSet<>();

    /**
     * Optional mob spawning service for early mob spawning during generation.
     */
    @Nullable
    private MobSpawningService mobSpawningService;
    @Nullable
    private Consumer<Float> progressConsumer;
    private float lastReportedProgress = 0.0f;

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
            List<ShopKeeperLocal> shopKeepers = new ArrayList<>();
            List<ShopItemLocal> shopItems = new ArrayList<>();

            JsonArray arr = root.getAsJsonArray("blocks");
            if (arr != null) {
                for (JsonElement el : arr) {
                    JsonObject b = el.getAsJsonObject();
                    int x = b.get("x").getAsInt() - ax;
                    int y = b.get("y").getAsInt() - ay;
                    int z = b.get("z").getAsInt() - az;
                    String name = b.has("name") ? b.get("name").getAsString() : "";
                    int sRot = b.has("rotation") ? b.get("rotation").getAsInt() : 0;

                    MarkerType markerType = MarkerType.fromBlockName(name);
                    boolean isMarker;

                    if (markerType == MarkerType.EXIT) {
                        isMarker = true;
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
                                readPortalMode(markerType, serializedComponents),
                                readRoomConfig(markerType, serializedComponents)
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
                        if (markerType == MarkerType.SHOP_KEEPER) {
                            JsonObject shopKeeperComp = getSerializedComponent(serializedComponents, "ShopKeeperData");
                            double range = 5.0;
                            double yawDegrees = rotationIndexToYawDegrees(sRot);
                            int refreshCost = 0;
                            int refreshCount = 0;
                            if (shopKeeperComp != null) {
                                String rawRange = getSerializedString(shopKeeperComp, "ActionRange");
                                if (rawRange != null) {
                                    try {
                                        range = Double.parseDouble(rawRange);
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                                String rawYaw = getSerializedString(shopKeeperComp, "RotationYaw");
                                if (rawYaw != null) {
                                    try {
                                        yawDegrees = Double.parseDouble(rawYaw);
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                                String rawRefreshCost = getSerializedString(shopKeeperComp, "RefreshCost");
                                if (rawRefreshCost != null) {
                                    try {
                                        refreshCost = Math.max(0, Integer.parseInt(rawRefreshCost));
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                                String rawRefreshCount = getSerializedString(shopKeeperComp, "RefreshCount");
                                if (rawRefreshCount != null) {
                                    try {
                                        refreshCount = Math.max(0, Integer.parseInt(rawRefreshCount));
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                            }
                            shopKeepers.add(new ShopKeeperLocal(x, y, z, range, yawDegrees, refreshCost, refreshCount));
                        }
                        if (markerType == MarkerType.SHOP_ITEM) {
                            JsonObject shopItemComp = getSerializedComponent(serializedComponents, "ShopItemData");
                            if (shopItemComp != null) {
                                String type = getSerializedString(shopItemComp, "ItemType");
                                String rawPrice = getSerializedString(shopItemComp, "Price");
                                int itemPrice = 10;
                                if (rawPrice != null) {
                                    try {
                                        itemPrice = Integer.parseInt(rawPrice);
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                                String weapons = getSerializedString(shopItemComp, "Weapons");
                                String armors = getSerializedString(shopItemComp, "Armors");
                                String minRarity = getSerializedString(shopItemComp, "MinRarity");
                                String maxRarity = getSerializedString(shopItemComp, "MaxRarity");
                                shopItems.add(new ShopItemLocal(x, y, z,
                                        type != null ? type : "",
                                        itemPrice,
                                        weapons != null ? weapons : "",
                                        armors != null ? armors : "",
                                        minRarity != null ? minRarity : "",
                                        maxRarity != null ? maxRarity : ""));
                            }
                        }
                    } else {
                        isMarker = "Prefab_Spawner_Block".equals(name);
                        if (isMarker) {
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

            return new PrefabData(blocks, spawners, markers, mobMarkers, configuredSpawners,
                    shopKeepers, shopItems);
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

        return DoorMode.ACTIVATOR;
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

    @Nullable
    private static RoomConfigData readRoomConfig(@Nonnull MarkerType markerType, @Nullable JsonObject serializedComponents) {
        if (markerType != MarkerType.ROOM_CONFIG) {
            return null;
        }

        JsonObject component = getSerializedComponent(serializedComponents, "RoomConfigData");
        if (component == null) {
            return null;
        }

        return new RoomConfigData(
                getSerializedString(component, "LockRoom"),
                getSerializedString(component, "MobClearActivator"),
                getSerializedString(component, "MobClearUnlockPercent"),
                getSerializedString(component, "EnterTitle"),
                getSerializedString(component, "EnterSubtitle"),
                getSerializedString(component, "UnlockTitle"),
                getSerializedString(component, "UnlockSubtitle"),
                getSerializedString(component, "ExitTitle"),
                getSerializedString(component, "ExitSubtitle")
        );
    }

    public static void pasteLockDoor(@Nonnull World world, @Nonnull DungeonConfig.LevelConfig levelConfig,
                                     @Nonnull RoomData room, @Nonnull Vector3i pastePos, int rot) {
        pasteTrackedDoorPrefab(world, room, pastePos, rot,
                DungeonConfig.resolveGlobs(levelConfig.getRoomPools().getLockDoor()),
                "lockDoor", room, room.getDoorMode(), null);
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
                    "sealDoor", room, room.getDoorMode(), null);
        }

        return room.getLockDoorBlockPositions().size() > trackedBlocksBefore;
    }

    public static boolean pasteConfiguredDoorMarkers(@Nonnull World world,
                                                     @Nonnull DungeonConfig.LevelConfig levelConfig,
                                                     @Nonnull RoomData room) {
        return pasteConfiguredDoorMarkers(world, levelConfig, room, EnumSet.allOf(DoorMode.class), true);
    }

    public static boolean pasteConfiguredDoorMarkers(@Nonnull World world,
                                                     @Nonnull DungeonConfig.LevelConfig levelConfig,
                                                     @Nonnull RoomData room,
                                                     @Nonnull Set<DoorMode> allowedModes,
                                                     boolean includeChildren) {
        int trackedBlocksBefore = room.getLockDoorBlockPositions().size();
        pasteConfiguredDoorMarkersForTarget(world, levelConfig, room, room, allowedModes);
        if (includeChildren) {
            for (RoomData child : room.getChildren()) {
                pasteConfiguredDoorMarkersForTarget(world, levelConfig, room, child, allowedModes);
            }
        }
        return room.getLockDoorBlockPositions().size() > trackedBlocksBefore;
    }

    public static boolean pasteOpenedKeyEntrances(@Nonnull World world,
                                                  @Nonnull DungeonConfig.LevelConfig levelConfig,
                                                  @Nonnull dev.ninesliced.unstablerifts.dungeon.Level level,
                                                  @Nonnull RoomData room) {
        List<Path> lockDoorPaths = DungeonConfig.resolveGlobs(levelConfig.getRoomPools().getLockDoor());
        if (lockDoorPaths.isEmpty()) {
            return false;
        }

        int trackedBlocksBefore = room.getLockDoorBlockPositions().size();
        for (RoomData.DoorMarker doorMarker : room.getDoorMarkers()) {
            if (doorMarker.mode() != DoorMode.KEY) {
                continue;
            }
            if (hasTrackedKeyDoorForMarker(level, room, doorMarker.position())) {
                continue;
            }

            pasteTrackedDoorPrefabFloorAligned(world, room, doorMarker.position(), room.getRotation(), lockDoorPaths,
                    "openedKeyEntranceSeal", room, DoorMode.ACTIVATOR, doorMarker.position());
        }
        return room.getLockDoorBlockPositions().size() > trackedBlocksBefore;
    }

    private static void pasteConfiguredDoorMarkersForTarget(@Nonnull World world,
                                                            @Nonnull DungeonConfig.LevelConfig levelConfig,
                                                            @Nonnull RoomData trackingRoom,
                                                            @Nonnull RoomData targetRoom,
                                                            @Nonnull Set<DoorMode> allowedModes) {
        if (targetRoom.getDoorMarkers().isEmpty()) {
            return;
        }

        for (RoomData.DoorMarker doorMarker : targetRoom.getDoorMarkers()) {
            if (!allowedModes.contains(doorMarker.mode())) {
                continue;
            }

            List<String> globs = switch (doorMarker.mode()) {
                case KEY -> levelConfig.getRoomPools().getKeyDoor();
                case ACTIVATOR -> levelConfig.getRoomPools().getActivationDoor();
            };
            List<Path> doorPaths = DungeonConfig.resolveGlobs(globs);
            if (doorPaths.isEmpty()) {
                continue;
            }

            String doorKind = doorMarker.mode() == DoorMode.KEY ? "keyDoorMarker" : "activationDoorMarker";
            pasteTrackedDoorPrefab(world, trackingRoom, doorMarker.position(), targetRoom.getRotation(), doorPaths, doorKind,
                    targetRoom, doorMarker.mode(), doorMarker.position());
        }
    }

    private static void pasteTrackedDoorPrefab(@Nonnull World world,
                                               @Nonnull RoomData room,
                                               @Nonnull Vector3i pastePos,
                                               int rot,
                                               @Nonnull List<Path> doorPaths,
                                               @Nonnull String doorKind,
                                               @Nonnull RoomData unlockRoom,
                                               @Nonnull DoorMode unlockMode,
                                               @Nullable Vector3i sourceDoorMarker) {
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

        pasteTrackedDoorPrefabResolved(world, room, pastePos, rot, doorKind, unlockRoom, unlockMode,
                sourceDoorMarker, random, doorPath, doorData);
    }

    private static void pasteTrackedDoorPrefabFloorAligned(@Nonnull World world,
                                                           @Nonnull RoomData room,
                                                           @Nonnull Vector3i pastePos,
                                                           int rot,
                                                           @Nonnull List<Path> doorPaths,
                                                           @Nonnull String doorKind,
                                                           @Nonnull RoomData unlockRoom,
                                                           @Nonnull DoorMode unlockMode,
                                                           @Nullable Vector3i sourceDoorMarker) {
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

        int minBlockY = getMinBlockY(doorData);
        Vector3i alignedPastePos = minBlockY == 0
                ? new Vector3i(pastePos)
                : new Vector3i(pastePos.x, pastePos.y - minBlockY, pastePos.z);

        pasteTrackedDoorPrefabResolved(world, room, alignedPastePos, rot, doorKind, unlockRoom, unlockMode,
                sourceDoorMarker, random, doorPath, doorData);
    }

    private static void pasteTrackedDoorPrefabResolved(@Nonnull World world,
                                                       @Nonnull RoomData room,
                                                       @Nonnull Vector3i pastePos,
                                                       int rot,
                                                       @Nonnull String doorKind,
                                                       @Nonnull RoomData unlockRoom,
                                                       @Nonnull DoorMode unlockMode,
                                                       @Nullable Vector3i sourceDoorMarker,
                                                       @Nonnull Random random,
                                                       @Nonnull Path doorPath,
                                                       @Nonnull PrefabData doorData) {
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
                room.addLockDoorBlockPosition(new Vector3i(wx, wy, wz), unlockRoom, unlockMode, doorKind, sourceDoorMarker);
            }

            LOGGER.at(Level.INFO).log("Pasted %s %s at %s rot=%d (%d blocks tracked)",
                    doorKind, doorPath.getFileName(), pastePos, rot, room.getLockDoorBlockPositions().size());
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Failed to paste %s at %s", doorKind, pastePos);
        }
    }

    private static int getMinBlockY(@Nonnull PrefabData prefabData) {
        int minY = Integer.MAX_VALUE;
        for (BlockLocal block : prefabData.blocks) {
            if (block.isMarker) {
                continue;
            }
            minY = Math.min(minY, block.y);
        }
        return minY == Integer.MAX_VALUE ? 0 : minY;
    }

    private static boolean hasTrackedKeyDoorForMarker(@Nonnull dev.ninesliced.unstablerifts.dungeon.Level level,
                                                      @Nonnull RoomData targetRoom,
                                                      @Nonnull Vector3i sourceDoorMarker) {
        for (RoomData trackingRoom : level.getRooms()) {
            for (RoomData.TrackedDoorBlock trackedDoorBlock : trackingRoom.getTrackedDoorBlocks()) {
                if (trackedDoorBlock.mode() != DoorMode.KEY || trackedDoorBlock.targetRoom() != targetRoom) {
                    continue;
                }

                Vector3i marker = trackedDoorBlock.sourceDoorMarker();
                if (marker == null) {
                    continue;
                }
                if (marker.x == sourceDoorMarker.x && marker.y == sourceDoorMarker.y && marker.z == sourceDoorMarker.z) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    public dev.ninesliced.unstablerifts.dungeon.Level getGeneratedLevel() {
        return generatedLevel;
    }

    /**
     * Returns the set of rooms whose mobs were pre-spawned during generation.
     */
    @Nonnull
    public Set<RoomData> getPreSpawnedRooms() {
        return preSpawnedRooms;
    }

    /**
     * Sets the mob spawning service for early mob spawning during generation.
     * When set, the generator will spawn mobs in the first few rooms immediately
     * after distributing mobs, so they are ready before the player loads in.
     */
    public void setMobSpawningService(@Nullable MobSpawningService mobSpawningService) {
        this.mobSpawningService = mobSpawningService;
    }

    public void setProgressConsumer(@Nullable Consumer<Float> progressConsumer) {
        this.progressConsumer = progressConsumer;
        this.lastReportedProgress = 0.0f;
    }

    /**
     * Generate a dungeon level in the given world at the default origin (0, 128, 0).
     */
    public void generate(@Nonnull World world, long seed,
                         @Nonnull DungeonConfig.LevelConfig levelConfig) {
        generate(world, seed, levelConfig, new Vector3i(0, 128, 0), 0);
    }

    /**
     * Generate a dungeon level in the given world at the specified origin.
     *
     * @param origin     world position where the spawn room is placed
     * @param levelIndex the index of this level within the dungeon run
     */
    public void generate(@Nonnull World world, long seed,
                         @Nonnull DungeonConfig.LevelConfig levelConfig,
                         @Nonnull Vector3i origin, int levelIndex) {
        Random random = new Random(seed);
        Store<EntityStore> store = world.getEntityStore().getStore();
        GenerationProgressTracker progressTracker = new GenerationProgressTracker(this);
        reportProgress(INITIAL_PROGRESS);

        dev.ninesliced.unstablerifts.dungeon.Level level = new dev.ninesliced.unstablerifts.dungeon.Level(levelConfig.getName(), levelIndex);
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
                new BranchCounter(),
                progressTracker
        );

        LOGGER.at(Level.INFO).log("Generating dungeon '%s' seed=%d at origin=%s levelIndex=%d",
                levelConfig.getName(), seed, origin, levelIndex);
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

        Vector3i spawnPaste = new Vector3i(origin);
        pasteAndRegister(context, spawnPath, spawnData, spawnPaste, 0);
        reportProgress(SPAWN_PROGRESS);

        RoomData spawnRoom = buildRoomData(level, RoomType.SPAWN, spawnData, spawnPaste, 0,
                Collections.emptyList(), 0, "main");
        level.addRoom(spawnRoom);

        List<TrackedExit> spawnExits = collectTrackedExits(spawnData, spawnPaste, 0, spawnRoom, 0, "main");
        LOGGER.at(Level.INFO).log("Spawn placed at %s, %d exit(s)", spawnPaste, spawnExits.size());

        DungeonConfig.MainBranchConfig mainConfig = levelConfig.getMain();
        List<TrackedExit> deadEndExits = new ArrayList<>();
        List<TrackedExit> branchTerminalExits = new ArrayList<>();
        Set<String> specialRoomBranches = new HashSet<>();

        buildBranch(context, spawnExits, deadEndExits, branchTerminalExits,
                0, "main", mainConfig.getChallengeRooms(),
                mainConfig.getMinimumCorridorLength(),
                mainConfig.getMaxRooms(), true);

        // Keep treasure earlier in progression and reserve later special-room branches for shops.
        int treasureCount = mainConfig.getTreasureRooms().roll(random);
        context.progressTracker().addPlannedRooms(treasureCount);
        int treasuresPlaced = 0;
        if (treasureCount > 0 && !resolvedPools.treasure.isEmpty()) {
            for (int i = 0; i < treasureCount && !branchTerminalExits.isEmpty(); i++) {
                TrackedExit exit = removeExitOnUnusedBranch(branchTerminalExits, specialRoomBranches, false);
                if (exit == null) {
                    LOGGER.at(Level.WARNING).log("No eligible branch-terminal exits left for treasure distinct from %s",
                            specialRoomBranches);
                    break;
                }
                deadEndExits.remove(exit);
                if (tryPlaceRoom(context, resolvedPools.treasure, exit, deadEndExits,
                        "Treasure", false, RoomType.TREASURE)) {
                    treasuresPlaced++;
                    context.progressTracker().roomPlaced();
                    specialRoomBranches.add(exit.branchId());
                } else {
                    sealExit(context, exit.exit);
                }
            }
        }
        LOGGER.at(Level.INFO).log("Placed %d/%d treasure rooms", treasuresPlaced, treasureCount);

        int shopCount = mainConfig.getShopRooms().roll(random);
        context.progressTracker().addPlannedRooms(shopCount);
        int shopsPlaced = 0;
        if (shopCount > 0 && !resolvedPools.shop.isEmpty()) {
            for (int i = 0; i < shopCount && !deadEndExits.isEmpty(); i++) {
                TrackedExit exit = removeExitOnUnusedBranch(deadEndExits, specialRoomBranches, true);
                if (exit == null) {
                    LOGGER.at(Level.WARNING).log("No eligible dead-end exits left for shop distinct from %s",
                            specialRoomBranches);
                    break;
                }
                if (tryPlaceRoom(context, resolvedPools.shop, exit, deadEndExits,
                        "Shop", false, RoomType.SHOP)) {
                    shopsPlaced++;
                    context.progressTracker().roomPlaced();
                    specialRoomBranches.add(exit.branchId());
                } else {
                    sealExit(context, exit.exit);
                }
            }
        }
        LOGGER.at(Level.INFO).log("Placed %d/%d shop rooms", shopsPlaced, shopCount);

        List<String> importantGlobs = levelConfig.getImportantRooms();
        context.progressTracker().addPlannedRooms(importantGlobs.size());
        int importantPlaced = 0;
        for (String glob : importantGlobs) {
            List<Path> importantPaths = DungeonConfig.resolveGlobs(List.of(glob));
            if (importantPaths.isEmpty() || deadEndExits.isEmpty()) continue;
            TrackedExit exit = deadEndExits.remove(random.nextInt(deadEndExits.size()));
            if (tryPlaceRoom(context, importantPaths, exit, deadEndExits,
                    "Important", true, RoomType.CHALLENGE)) {
                importantPlaced++;
                context.progressTracker().roomPlaced();
            } else {
                sealExit(context, exit.exit);
            }
        }
        LOGGER.at(Level.INFO).log("Placed %d/%d important rooms", importantPlaced, importantGlobs.size());

        for (TrackedExit exit : deadEndExits) {
            sealExit(context, exit.exit);
        }
        deadEndExits.clear();
        reportProgress(ROOM_LAYOUT_END_PROGRESS);

        prePasteKeyDoors(world, levelConfig, level);
        reportProgress(DOOR_SETUP_PROGRESS);

        distributeMobs(level, levelConfig, random);
        reportProgress(MOB_SETUP_PROGRESS);

        distributeKeys(level, world, random);
        reportProgress(KEY_SETUP_PROGRESS);

        // Pre-spawn mobs in the first rooms so they are ready before the player loads in.
        preSpawnedRooms.clear();
        if (mobSpawningService != null) {
            preSpawnedRooms = mobSpawningService.spawnEarlyRoomMobs(level, store, EARLY_SPAWN_ROOM_COUNT);
            LOGGER.at(Level.INFO).log("Pre-spawned mobs in %d rooms during generation", preSpawnedRooms.size());
        }
        reportProgress(PRESPAWN_PROGRESS);

        LOGGER.at(Level.INFO).log("Dungeon complete: %d rooms, boss=%b, treasures=%d, shops=%d, branches=%d",
                level.getRooms().size(), level.getBossRoom() != null,
                treasuresPlaced, shopsPlaced, context.branchCount());
        reportProgress(1.0f);
    }

    private void prePasteKeyDoors(@Nonnull World world,
                                  @Nonnull DungeonConfig.LevelConfig levelConfig,
                                  @Nonnull dev.ninesliced.unstablerifts.dungeon.Level level) {
        int keyDoorRooms = 0;
        int pastedKeyDoorRooms = 0;

        for (RoomData room : level.getRooms()) {
            if (!room.hasDoorMode(DoorMode.KEY)) {
                continue;
            }

            keyDoorRooms++;
            if (pasteConfiguredDoorMarkers(world, levelConfig, room, EnumSet.of(DoorMode.KEY), false)) {
                pastedKeyDoorRooms++;
            }
        }

        if (keyDoorRooms > 0) {
            LOGGER.at(Level.INFO).log("Pre-pasted KEY doors in %d/%d room(s) during generation",
                    pastedKeyDoorRooms, keyDoorRooms);
        }
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
                             @Nonnull List<TrackedExit> branchTerminalExits,
                             int branchDepth, @Nonnull String branchId,
                             @Nonnull IntRange challengeRange, int minCorridorLength,
                             int maxRooms, boolean isMainBranch) {
        context.progressTracker().addPlannedRooms(maxRooms + (isMainBranch ? 1 : 0));
        BranchRoomPaths roomPaths = resolveBranchRoomPaths(context.resolvedPools(), isMainBranch);
        BranchLayoutPlan layoutPlan = planBranchLayout(
                context.random(), challengeRange, minCorridorLength, maxRooms, isMainBranch);
        logBranchLayout(branchId, layoutPlan, maxRooms);

        Set<Integer> globalSplitterSlots = planSplitterSlots(context, branchDepth, layoutPlan.totalCorridors());
        BranchState state = new BranchState(startExits);

        placeChallengeSegments(context, deadEndExits, branchTerminalExits, branchDepth, branchId, roomPaths, layoutPlan,
                globalSplitterSlots, state);

        if (isMainBranch) {
            placeBossSegment(context, deadEndExits, branchTerminalExits, branchDepth, branchId, roomPaths, layoutPlan,
                    globalSplitterSlots, state);
        }

        if (!state.deferredBranchExits.isEmpty()) {
            LOGGER.at(Level.INFO).log("[%s] Spawning %d deferred branch(es) from splitters",
                    branchId, state.deferredBranchExits.size());
            forceSpawnBranches(context, state.deferredBranchExits, deadEndExits, branchTerminalExits, branchDepth, branchId);
        }

        LOGGER.at(Level.INFO).log("[%s] Branch complete: %d rooms placed (max %d)",
                branchId, state.roomsPlaced, maxRooms);
        if (branchDepth > 0) {
            branchTerminalExits.addAll(state.currentExits);
        }
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
                                        @Nonnull List<TrackedExit> branchTerminalExits,
                                        int branchDepth,
                                        @Nonnull String branchId,
                                        @Nonnull BranchRoomPaths roomPaths,
                                        @Nonnull BranchLayoutPlan layoutPlan,
                                        @Nonnull Set<Integer> globalSplitterSlots,
                                        @Nonnull BranchState state) {
        for (int challengeIndex = 0; challengeIndex < layoutPlan.challengeCount(); challengeIndex++) {
            placeNextSegmentCorridors(context, deadEndExits, branchTerminalExits, branchDepth, branchId, roomPaths,
                    globalSplitterSlots, state, layoutPlan.corridorsPerSegment()[state.segmentIndex++]);

            if (state.currentExits.isEmpty()) {
                LOGGER.at(Level.WARNING).log("[%s] No exits left for challenge %d/%d",
                        branchId, challengeIndex + 1, layoutPlan.challengeCount());
                break;
            }

            if (placeChallengeRoom(context, deadEndExits, branchTerminalExits, branchDepth, branchId, roomPaths.challenge(),
                    challengeIndex, state)) {
                state.roomsPlaced++;
                context.progressTracker().roomPlaced();
            }
        }
    }

    private void placeNextSegmentCorridors(@Nonnull GenerationContext context,
                                           @Nonnull List<TrackedExit> deadEndExits,
                                           @Nonnull List<TrackedExit> branchTerminalExits,
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
                branchTerminalExits,
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
                                       @Nonnull List<TrackedExit> branchTerminalExits,
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
            handleExtraExits(context, newExits, state.currentExits, deadEndExits, branchTerminalExits, branchDepth, branchId);
        }
        return true;
    }

    private void placeBossSegment(@Nonnull GenerationContext context,
                                  @Nonnull List<TrackedExit> deadEndExits,
                                  @Nonnull List<TrackedExit> branchTerminalExits,
                                  int branchDepth,
                                  @Nonnull String branchId,
                                  @Nonnull BranchRoomPaths roomPaths,
                                  @Nonnull BranchLayoutPlan layoutPlan,
                                  @Nonnull Set<Integer> globalSplitterSlots,
                                  @Nonnull BranchState state) {
        if (state.segmentIndex < layoutPlan.corridorsPerSegment().length) {
            placeNextSegmentCorridors(context, deadEndExits, branchTerminalExits, branchDepth, branchId, roomPaths,
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
                    context.progressTracker().roomPlaced();
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
                                          @Nonnull List<TrackedExit> branchTerminalExits,
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
            context.progressTracker().roomPlaced();
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
                        handleExtraExits(context, newExits, currentExits, deadEndExits, branchTerminalExits,
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
                                  @Nonnull List<TrackedExit> branchTerminalExits,
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

                buildBranch(context, branchStarts, deadEndExits, branchTerminalExits,
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
                                    @Nonnull List<TrackedExit> branchTerminalExits,
                                    int currentBranchDepth, @Nonnull String currentBranchId) {
        DungeonConfig.BranchConfig branchConfig = context.levelConfig().getBranch();

        for (TrackedExit extra : extraExits) {
            int newDepth = currentBranchDepth + 1;
            String newBranchId = "branch-" + context.nextBranchIndex();
            LOGGER.at(Level.INFO).log("Splitter forking %s (depth %d) from %s", newBranchId, newDepth, currentBranchId);

            List<TrackedExit> branchStarts = new ArrayList<>();
            branchStarts.add(new TrackedExit(extra.exit, extra.parent, newDepth, newBranchId));

            buildBranch(context, branchStarts, deadEndExits, branchTerminalExits,
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
        int unreadablePrefabs = 0;
        int overlapFailures = 0;
        int pasteFailures = 0;

        for (int i = 0; i < attempts; i++) {
            Path chosen = shuffled.get(i);
            PrefabData data = readPrefabData(chosen);
            if (data == null) {
                unreadablePrefabs++;
                continue;
            }

            if (wouldOverlap(context, data, pastePos, pasteRot)) {
                overlapFailures++;
                LOGGER.at(Level.FINE).log("[%s] overlap retry %d/%d %s", label, i + 1, attempts, chosen.getFileName());
                continue;
            }

            try {
                pasteAndRegister(context, chosen, data, pastePos, pasteRot);
            } catch (Exception e) {
                pasteFailures++;
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

        LOGGER.at(Level.INFO).log("[%s] placement failed, sealing exit (attempts=%d, overlaps=%d, pasteFailures=%d, unreadable=%d)",
                label, attempts, overlapFailures, pasteFailures, unreadablePrefabs);
        sealExit(context, exit);
        return false;
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
                case DOOR -> roomData.addDoorPosition(worldPos, marker.doorMode);
                case ROOM_CONFIG -> {
                    RoomConfigData cfg = marker.roomConfig;
                    if (cfg != null) {
                        if (cfg.isLockRoom()) {
                            roomData.setLocked(true);
                        }
                        if (cfg.hasMobClearUnlockPercentConfigured()) {
                            roomData.setMobClearUnlockPercent(cfg.getMobClearUnlockPercent());
                        }
                        roomData.setEnterTitle(cfg.getEnterTitle());
                        roomData.setEnterSubtitle(cfg.getEnterSubtitle());
                        roomData.setUnlockTitle(cfg.getUnlockTitle());
                        roomData.setUnlockSubtitle(cfg.getUnlockSubtitle());
                        roomData.setExitTitle(cfg.getExitTitle());
                        roomData.setExitSubtitle(cfg.getExitSubtitle());
                    }
                }
                case ACTIVATION_ZONE -> roomData.addActivationZonePosition(worldPos);
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

        for (ShopKeeperLocal sk : data.shopKeepers()) {
            int wx = pastePos.x + rotateLocalX(sk.x, sk.z, rot);
            int wy = pastePos.y + sk.y;
            int wz = pastePos.z + rotateLocalZ(sk.x, sk.z, rot);
            roomData.setShopKeeperPosition(new Vector3i(wx, wy, wz));
            roomData.setShopActionRange(sk.actionRange);
            roomData.setShopKeeperYaw((float) Math.toRadians(sk.yawDegrees) + rotationIndexToYaw(rot));
            roomData.setShopRefreshCost(sk.refreshCost);
            roomData.setShopRefreshCount(sk.refreshCount);
        }

        for (ShopItemLocal si : data.shopItems()) {
            int wx = pastePos.x + rotateLocalX(si.x, si.z, rot);
            int wy = pastePos.y + si.y;
            int wz = pastePos.z + rotateLocalZ(si.x, si.z, rot);
            dev.ninesliced.unstablerifts.shop.ShopItemType itemType =
                    dev.ninesliced.unstablerifts.shop.ShopItemType.fromString(si.itemType);
            if (itemType != null) {
                roomData.addShopItemSlot(new RoomData.ShopItemSlot(
                        new Vector3i(wx, wy, wz), itemType, si.price,
                        dev.ninesliced.unstablerifts.shop.ShopItemData.parseWeightedEntriesStatic(si.weapons),
                        dev.ninesliced.unstablerifts.shop.ShopItemData.parseWeightedEntriesStatic(si.armors),
                        si.minRarity, si.maxRarity));
            }
        }

        for (Vector3i pos : roomData.getActivationZonePositions()) {
            roomData.addChallenge(new ChallengeObjective(ChallengeObjective.Type.ACTIVATION_ZONE, pos));
        }
        if (roomData.getMobClearUnlockPercent() > 0) {
            roomData.addChallenge(new ChallengeObjective(
                    ChallengeObjective.Type.MOB_CLEAR,
                    roomData.getAnchor(),
                    roomData.getMobClearUnlockPercent()));
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
     * Spawn one key per KEY-mode locked room, choosing a random key spawner from
     * an earlier room when possible.
     */
    private void distributeKeys(@Nonnull dev.ninesliced.unstablerifts.dungeon.Level level,
                                @Nonnull World world,
                                @Nonnull Random random) {
        List<RoomData> rooms = level.getRooms();
        if (rooms.isEmpty()) {
            return;
        }

        int keyDoorCount = 0;
        int keysSpawned = 0;
        Set<Long> usedSpawnerPositions = new HashSet<>();
        Map<Long, Integer> spawnCountBySpawner = new HashMap<>();
        Store<EntityStore> store = world.getEntityStore().getStore();

        for (RoomData room : rooms) {
            int roomKeyDoorCount = room.getDoorCount(DoorMode.KEY);
            if (!room.isLocked() || roomKeyDoorCount <= 0) {
                continue;
            }

            for (int roomDoorIndex = 0; roomDoorIndex < roomKeyDoorCount; roomDoorIndex++) {
                keyDoorCount++;

                List<Vector3i> availableSpawners = collectEligibleKeySpawners(room, usedSpawnerPositions);
                boolean reusingSpawner = false;
                if (availableSpawners.isEmpty()) {
                    availableSpawners = collectEligibleKeySpawners(room, null);
                    reusingSpawner = true;
                }

                if (availableSpawners.isEmpty()) {
                    LOGGER.at(Level.WARNING).log(
                            "No key spawner found before KEY door %d/%d in room at %s; this door will have no spawned key.",
                            roomDoorIndex + 1, roomKeyDoorCount, room.getAnchor());
                    continue;
                }

                Vector3i chosenSpawner = availableSpawners.get(random.nextInt(availableSpawners.size()));
                long spawnerKey = packPos(chosenSpawner.x, chosenSpawner.y, chosenSpawner.z);
                int overlapIndex = spawnCountBySpawner.getOrDefault(spawnerKey, 0);

                if (!spawnKeyItem(store, chosenSpawner, overlapIndex)) {
                    continue;
                }

                if (!reusingSpawner) {
                    usedSpawnerPositions.add(spawnerKey);
                }

                spawnCountBySpawner.put(spawnerKey, overlapIndex + 1);
                level.addKeySpawnPosition(chosenSpawner);
                keysSpawned++;
                LOGGER.at(Level.INFO).log("Spawned key %d/%d for room %s door %d/%d at %s%s",
                        keysSpawned,
                        keyDoorCount,
                        room.getAnchor(),
                        roomDoorIndex + 1,
                        roomKeyDoorCount,
                        chosenSpawner,
                        reusingSpawner ? " (reused spawner)" : "");
            }
        }

        if (keyDoorCount == 0) {
            return;
        }

        if (keysSpawned != keyDoorCount) {
            LOGGER.at(Level.WARNING).log("Spawned %d/%d key(s) for KEY door rooms in level '%s'",
                    keysSpawned, keyDoorCount, level.getName());
            return;
        }

        LOGGER.at(Level.INFO).log("Spawned %d key(s) for %d KEY door room(s) in level '%s'",
                keysSpawned, keyDoorCount, level.getName());
    }

    @Nonnull
    private List<Vector3i> collectEligibleKeySpawners(@Nonnull RoomData lockedRoom,
                                                      @Nullable Set<Long> excludedSpawnerPositions) {
        List<RoomData> progressionPath = collectRoomsBeforeLockedRoom(lockedRoom);
        List<Vector3i> eligible = new ArrayList<>();
        for (RoomData candidate : progressionPath) {
            if (candidate.getType() == RoomType.TREASURE) {
                continue;
            }
            for (Vector3i spawnerPos : candidate.getKeySpawnerPositions()) {
                long packed = packPos(spawnerPos.x, spawnerPos.y, spawnerPos.z);
                if (excludedSpawnerPositions != null && excludedSpawnerPositions.contains(packed)) {
                    continue;
                }
                eligible.add(spawnerPos);
            }
        }
        return eligible;
    }

    @Nonnull
    private List<RoomData> collectRoomsBeforeLockedRoom(@Nonnull RoomData lockedRoom) {
        LinkedList<RoomData> path = new LinkedList<>();
        RoomData cursor = lockedRoom.getParent();
        while (cursor != null) {
            path.addFirst(cursor);
            cursor = cursor.getParent();
        }
        return path;
    }

    private boolean spawnKeyItem(@Nonnull Store<EntityStore> store,
                                 @Nonnull Vector3i spawnerPos,
                                 int overlapIndex) {
        double offsetX = 0.0;
        double offsetZ = 0.0;
        if (overlapIndex > 0) {
            double angle = (Math.PI * 2.0 * overlapIndex) / 6.0;
            offsetX = Math.cos(angle) * KEY_OVERLAP_RADIUS;
            offsetZ = Math.sin(angle) * KEY_OVERLAP_RADIUS;
        }

        Vector3d dropPos = new Vector3d(
                spawnerPos.x + 0.5 + offsetX,
                spawnerPos.y + 0.2,
                spawnerPos.z + 0.5 + offsetZ);

        Holder<EntityStore> holder = ItemComponent.generateItemDrop(
                store,
                new ItemStack(KEY_ITEM_ID, 1),
                dropPos,
                Rotation3f.ZERO,
                0.0f,
                0.0f,
                0.0f);
        if (holder == null) {
            LOGGER.at(Level.WARNING).log("Failed to generate key item holder at %s", spawnerPos);
            return false;
        }

        holder.tryRemoveComponent(DespawnComponent.getComponentType());
        holder.addComponent(PreventItemMerging.getComponentType(), PreventItemMerging.INSTANCE);
        store.addEntity(holder, AddReason.SPAWN);
        return true;
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

    @Nullable
    private TrackedExit removeExitOnUnusedBranch(@Nonnull List<TrackedExit> exits,
                                                 @Nonnull Set<String> excludedBranchIds,
                                                 boolean preferLatestBranch) {
        int chosenIndex = -1;
        int chosenOrder = preferLatestBranch ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        for (int i = 0; i < exits.size(); i++) {
            TrackedExit exit = exits.get(i);
            if (excludedBranchIds.contains(exit.branchId())) {
                continue;
            }

            int order = branchProgressionOrder(exit.branchId());
            if (chosenIndex < 0
                    || (!preferLatestBranch && order < chosenOrder)
                    || (preferLatestBranch && order > chosenOrder)) {
                chosenIndex = i;
                chosenOrder = order;
            }
        }

        if (chosenIndex < 0) {
            return null;
        }
        return exits.remove(chosenIndex);
    }

    private int branchProgressionOrder(@Nonnull String branchId) {
        if ("main".equals(branchId)) {
            return 0;
        }
        if (branchId.startsWith("branch-")) {
            try {
                return Integer.parseInt(branchId.substring("branch-".length()));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
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

    @Nonnull
    private static List<String> parseCommaSeparated(@Nullable String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private record SpawnerLocal(int x, int y, int z, int rot) {
    }

    private record BlockLocal(int x, int y, int z, String name, boolean isMarker) {
    }

    private record MarkerLocal(int x, int y, int z,
                               @Nonnull MarkerType type,
                               @Nonnull DoorMode doorMode,
                               @Nonnull PortalMode portalMode,
                               @Nullable RoomConfigData roomConfig) {
    }

    private record PrefabMobMarkerLocal(double x, double y, double z, @Nonnull String mobId) {
    }

    private record ConfiguredSpawnerLocal(int x, int y, int z,
                                          @Nonnull String mobEntries, int spawnCount) {
    }

    private record ShopKeeperLocal(int x, int y, int z,
                                   double actionRange,
                                   double yawDegrees,
                                   int refreshCost,
                                   int refreshCount) {
    }

    private record ShopItemLocal(int x, int y, int z,
                                 @Nonnull String itemType, int price,
                                 @Nonnull String weapons, @Nonnull String armors,
                                 @Nonnull String minRarity, @Nonnull String maxRarity) {
    }

    private record PrefabData(List<BlockLocal> blocks, List<SpawnerLocal> spawners,
                              List<MarkerLocal> markers, List<PrefabMobMarkerLocal> mobMarkers,
                              List<ConfiguredSpawnerLocal> configuredSpawners,
                              List<ShopKeeperLocal> shopKeepers,
                              List<ShopItemLocal> shopItems) {
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
            @Nonnull BranchCounter branchCounter,
            @Nonnull GenerationProgressTracker progressTracker) {

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

    private static final class GenerationProgressTracker {
        private final DungeonGenerator generator;
        private int plannedRooms;
        private int completedRooms;

        private GenerationProgressTracker(@Nonnull DungeonGenerator generator) {
            this.generator = generator;
        }

        private void addPlannedRooms(int count) {
            if (count <= 0) {
                return;
            }
            this.plannedRooms += count;
            publish();
        }

        private void roomPlaced() {
            this.completedRooms++;
            publish();
        }

        private void publish() {
            if (plannedRooms <= 0) {
                return;
            }

            float fraction = Math.min(1.0f, (float) completedRooms / plannedRooms);
            float progress = ROOM_LAYOUT_START_PROGRESS
                    + fraction * (ROOM_LAYOUT_END_PROGRESS - ROOM_LAYOUT_START_PROGRESS);
            generator.reportProgress(progress);
        }
    }

    private void reportProgress(float progress) {
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        if (clamped <= lastReportedProgress) {
            return;
        }

        lastReportedProgress = clamped;
        if (progressConsumer != null) {
            progressConsumer.accept(clamped);
        }
    }

    /**
     * Wraps an IPrefabBuffer to skip Prefab_Spawner_Block entries (handled as exit markers)
     * and blocks that would land outside valid world Y range (where chunk sections don't exist).
     */
    private static final class SpawnerFilteredBuffer implements IPrefabBuffer {
        private static final int DECO_SUPPORT_VALUE = 15;

        private final IPrefabBuffer delegate;
        private final int spawnerBlockId;
        private final int pasteY;
        private final Map<Integer, Boolean> ignoreSupportPlacementCache = new HashMap<>();

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

                // Prefab optimization can remove hidden support cubes inside trees/branches.
                // Match Hytale's "IgnoreSupportWhenPlaced" behavior by pasting those blocks
                // as deco support so they do not collapse from stale prefab support data.
                int adjustedSupportValue = shouldPasteAsDecoSupport(blockId)
                        ? DECO_SUPPORT_VALUE
                        : supportValue;
                blockConsumer.accept(x, y, z, blockId, holder, adjustedSupportValue, rotation, filler, call, fluidId, fluidLevel);
            }, entityConsumer, childConsumer, t);
        }

        private boolean shouldPasteAsDecoSupport(int blockId) {
            if (blockId <= 0) {
                return false;
            }

            return ignoreSupportPlacementCache.computeIfAbsent(blockId, id -> {
                BlockType blockType = BlockType.getAssetMap().getAsset(id);
                return blockType != null && blockType.shouldIgnoreSupportWhenPlaced();
            });
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
