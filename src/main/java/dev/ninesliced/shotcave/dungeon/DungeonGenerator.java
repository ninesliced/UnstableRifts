package dev.ninesliced.shotcave.dungeon;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
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
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ninesliced.shotcave.ShotcaveLog;

public class DungeonGenerator {

    private static final HytaleLogger LOGGER = ShotcaveLog.forModule("Dungeon");
    private static final Gson GSON = new Gson();
    private static final int MAX_RETRIES = 5;

    private final Set<Long> occupiedBlocks = new HashSet<>();

    private List<Path> wallPaths;
    private World activeWorld;
    private Random activeRandom;

    /**
     * The level produced by the last generate() call, with full RoomData graph.
     */
    @Nullable
    private dev.ninesliced.shotcave.dungeon.Level generatedLevel;

    /**
     * Current parent room during generation (used to build the graph).
     */
    @Nullable
    private RoomData currentParentRoom;

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

    /**
     * Returns the Level produced by the last {@link #generate} call.
     */
    @Nullable
    public dev.ninesliced.shotcave.dungeon.Level getGeneratedLevel() {
        return generatedLevel;
    }

    public void generate(@Nonnull World world, long seed,
            @Nonnull DungeonConfig.LevelConfig levelConfig) {
        Random random = new Random(seed);
        Store<EntityStore> store = world.getEntityStore().getStore();
        occupiedBlocks.clear();

        this.activeWorld = world;
        this.activeRandom = random;

        // Create the Level object for this generation
        dev.ninesliced.shotcave.dungeon.Level level = new dev.ninesliced.shotcave.dungeon.Level(
                levelConfig.getName(), 0);
        this.generatedLevel = level;
        this.currentParentRoom = null;

        // Resolve mob lists from config
        List<String> roomMobs = levelConfig.getMobs() != null ? levelConfig.getMobs() : Collections.emptyList();

        LOGGER.at(Level.INFO).log("Generating dungeon '" + levelConfig.getName()
                + "' seed=" + seed + " targetRooms=" + levelConfig.getRooms());

        List<Path> entrancePaths = DungeonConfig.resolveGlobs(levelConfig.getPrefabs().getEntrance());
        List<Path> roomPaths = DungeonConfig.resolveGlobs(levelConfig.getPrefabs().getRoom());
        this.wallPaths = DungeonConfig.resolveGlobs(levelConfig.getPrefabs().getWall());
        List<Path> bossPaths = DungeonConfig.resolveGlobs(levelConfig.getPrefabs().getBoss());

        LOGGER.at(Level.INFO).log("Resolved: entrances=%d rooms=%d walls=%d bosses=%d",
                entrancePaths.size(), roomPaths.size(), wallPaths.size(), bossPaths.size());

        if (entrancePaths.isEmpty()) {
            LOGGER.at(Level.SEVERE).log("No entrance prefabs!");
            return;
        }
        if (roomPaths.isEmpty()) {
            LOGGER.at(Level.SEVERE).log("No room prefabs!");
            return;
        }

        List<OpenExit> openExits = new ArrayList<>();
        int roomsPlaced = 0;

        Path entrancePath = DungeonConfig.pickRandom(random, entrancePaths);
        if (entrancePath == null) {
            LOGGER.at(Level.SEVERE).log("Failed to pick an entrance prefab!");
            return;
        }
        PrefabData entranceData = readPrefabData(entrancePath);
        if (entranceData == null) {
            LOGGER.at(Level.SEVERE).log("Failed to parse entrance!");
            return;
        }

        Vector3i entrancePaste = new Vector3i(0, 65, 0);
        pasteAndRegister(world, store, random, entrancePath, entranceData, entrancePaste, 0);

        // Build RoomData for entrance
        List<Vector3i> entranceSpawnerPositions = computeWorldSpawnerPositions(entranceData, entrancePaste, 0);
        List<Vector3d> entrancePrefabMobMarkers = computeWorldPrefabMobMarkerPositions(entranceData, entrancePaste, 0);
        RoomData entranceRoom = new RoomData(RoomType.ENTRANCE, entrancePaste, 0,
                entranceSpawnerPositions, entrancePrefabMobMarkers, Collections.emptyList());
        level.addRoom(entranceRoom);
        this.currentParentRoom = entranceRoom;

        collectExits(entranceData, entrancePaste, 0, openExits);

        LOGGER.at(Level.INFO).log("Entrance placed at %s, %d exit(s)", entrancePaste, openExits.size());

        while (roomsPlaced < levelConfig.getRooms() && !openExits.isEmpty()) {
            int idx = random.nextInt(openExits.size());
            OpenExit exit = openExits.remove(idx);
            int nextRoomNumber = roomsPlaced + 1;
            boolean forceOnFailure = nextRoomNumber == 5;

            if (tryPlaceRoom(world, store, random, roomPaths, exit, openExits,
                    "Room_" + nextRoomNumber, forceOnFailure, RoomType.ROOM, roomMobs, level)) {
                roomsPlaced++;
            }
        }

        boolean bossPlaced = false;
        if (!bossPaths.isEmpty() && !openExits.isEmpty()) {
            Collections.shuffle(openExits, random);
            Iterator<OpenExit> it = openExits.iterator();
            while (it.hasNext()) {
                OpenExit exit = it.next();
                it.remove();
                if (tryPlaceRoom(world, store, random, bossPaths, exit, openExits,
                        "Boss", false, RoomType.BOSS, Collections.emptyList(), level)) {
                    bossPlaced = true;
                    roomsPlaced++;
                    break;
                }
            }
            if (!bossPlaced && !openExits.isEmpty()) {
                OpenExit forceExit = openExits.remove(random.nextInt(openExits.size()));
                if (forcePlaceRoom(world, store, random, bossPaths, forceExit, openExits,
                        "Boss(forced)", RoomType.BOSS, Collections.emptyList(), level)) {
                    bossPlaced = true;
                    roomsPlaced++;
                } else {
                    sealExit(forceExit);
                }
            }
        }

        for (OpenExit exit : openExits) {
            sealExit(exit);
        }
        openExits.clear();

        LOGGER.at(Level.INFO).log("Dungeon complete: " + roomsPlaced + " rooms, boss=" + bossPlaced
                + ", totalRoomData=" + level.getRooms().size());
    }

    /**
     * Compute world-space spawner positions for a prefab placement.
     */
    @Nonnull
    private List<Vector3i> computeWorldSpawnerPositions(@Nonnull PrefabData data, @Nonnull Vector3i pastePos, int rot) {
        List<Vector3i> positions = new ArrayList<>();
        for (SpawnerLocal sp : data.spawners) {
            int wx = pastePos.x + rotX(sp.x, sp.z, rot);
            int wy = pastePos.y + sp.y;
            int wz = pastePos.z + rotZ(sp.x, sp.z, rot);
            positions.add(new Vector3i(wx, wy, wz));
        }
        return positions;
    }

    @Nonnull
    private List<Vector3d> computeWorldPrefabMobMarkerPositions(@Nonnull PrefabData data, @Nonnull Vector3i pastePos, int rot) {
        List<Vector3d> positions = new ArrayList<>();
        for (PrefabMobMarkerLocal marker : data.mobMarkers) {
            double wx = pastePos.x + rotX(marker.x, marker.z, rot);
            double wy = pastePos.y + marker.y;
            double wz = pastePos.z + rotZ(marker.x, marker.z, rot);
            positions.add(new Vector3d(wx, wy, wz));
        }
        return positions;
    }

    private boolean tryPlaceRoom(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                 @Nonnull Random random, @Nonnull List<Path> roomPaths,
                                 @Nonnull OpenExit exit, @Nonnull List<OpenExit> openExits,
                                 @Nonnull String label, boolean forceOnFailure,
                                 @Nonnull RoomType roomType, @Nonnull List<String> mobs,
                                 @Nonnull dev.ninesliced.shotcave.dungeon.Level level) {
        List<Path> shuffled = new ArrayList<>(roomPaths);
        Collections.shuffle(shuffled, random);

        Vector3i pastePos = new Vector3i(exit.worldX, exit.worldY, exit.worldZ);
        // Rotate 180° so the room's entrance face connects to the exit and the body extends away
        int pasteRot = (exit.rotation + 2) & 3;
        int attempts = Math.min(MAX_RETRIES, shuffled.size());

        for (int i = 0; i < attempts; i++) {
            Path chosen = shuffled.get(i);
            PrefabData data = readPrefabData(chosen);
            if (data == null) {
                continue;
            }
            if (wouldOverlap(data, pastePos, pasteRot)) {
                LOGGER.at(Level.FINE).log("[" + label + "] overlap retry " + (i + 1) + "/" + attempts
                        + " " + chosen.getFileName()
                        + " paste=" + pastePos + " rot=" + pasteRot);
                continue;
            }

            try {
                pasteAndRegister(world, store, random, chosen, data, pastePos, pasteRot);
            } catch (Exception e) {
                LOGGER.at(Level.WARNING).withCause(e).log("[%s] paste failed %s", label, chosen.getFileName());
                continue;
            }

            int newExitCount = collectExits(data, pastePos, pasteRot, openExits);

            // Build RoomData and add to level graph
            List<Vector3i> spawnerPositions = computeWorldSpawnerPositions(data, pastePos, pasteRot);
            List<Vector3d> prefabMobMarkerPositions = computeWorldPrefabMobMarkerPositions(data, pastePos, pasteRot);
            RoomData roomData = new RoomData(roomType, pastePos, pasteRot, spawnerPositions, prefabMobMarkerPositions, mobs);
            if (currentParentRoom != null) {
                currentParentRoom.addChild(roomData);
            }
            level.addRoom(roomData);
            currentParentRoom = roomData;

            LOGGER.at(Level.INFO).log("[" + label + "] " + chosen.getFileName()
                    + " paste=" + pastePos + " rot=" + pasteRot
                    + " -> " + newExitCount + " new exit(s)");
            return true;
        }

        if (forceOnFailure) {
            LOGGER.at(Level.WARNING).log("[" + label + "] retries exhausted, force-placing for inspection");
            if (forcePlaceRoom(world, store, random, roomPaths, exit, openExits,
                    label + "(forced)", roomType, mobs, level)) {
                return true;
            }
            LOGGER.at(Level.WARNING).log("[" + label + "] forced placement also failed, sealing exit");
        } else {
            LOGGER.at(Level.INFO).log("[" + label + "] " + attempts + " retries failed, sealing exit");
        }
        sealExit(exit);
        return false;
    }

    private boolean forcePlaceRoom(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                   @Nonnull Random random, @Nonnull List<Path> roomPaths,
                                   @Nonnull OpenExit exit, @Nonnull List<OpenExit> openExits,
                                   @Nonnull String label,
                                   @Nonnull RoomType roomType, @Nonnull List<String> mobs,
                                   @Nonnull dev.ninesliced.shotcave.dungeon.Level level) {
        Path chosen = DungeonConfig.pickRandom(random, roomPaths);
        if (chosen == null) {
            return false;
        }
        PrefabData data = readPrefabData(chosen);
        if (data == null) {
            return false;
        }

        Vector3i pastePos = new Vector3i(exit.worldX, exit.worldY, exit.worldZ);
        // Rotate 180° so the room's entrance face connects to the exit and the body extends away
        int pasteRot = (exit.rotation + 2) & 3;
        try {
            pasteAndRegister(world, store, random, chosen, data, pastePos, pasteRot);
            int newExitCount = collectExits(data, pastePos, pasteRot, openExits);

            // Build RoomData for force-placed room
            List<Vector3i> spawnerPositions = computeWorldSpawnerPositions(data, pastePos, pasteRot);
            List<Vector3d> prefabMobMarkerPositions = computeWorldPrefabMobMarkerPositions(data, pastePos, pasteRot);
            RoomData roomData = new RoomData(roomType, pastePos, pasteRot, spawnerPositions, prefabMobMarkerPositions, mobs);
            if (currentParentRoom != null) {
                currentParentRoom.addChild(roomData);
            }
            level.addRoom(roomData);
            currentParentRoom = roomData;

            LOGGER.at(Level.WARNING).log("[" + label + "] " + chosen.getFileName()
                    + " force-placed at " + pastePos + " rot=" + pasteRot
                    + " -> " + newExitCount + " new exit(s)");
            return true;
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).log("[" + label + "] force-paste failed", e);
            return false;
        }
    }

    private void sealExit(@Nonnull OpenExit exit) {
        if (wallPaths != null && !wallPaths.isEmpty()) {
            Path wallPath = DungeonConfig.pickRandom(activeRandom, wallPaths);
            if (wallPath != null) {
                PrefabData wallData = readPrefabData(wallPath);
                if (wallData != null) {
                    int px = exit.worldX;
                    int py = exit.worldY;
                    int pz = exit.worldZ;
                    int placed = 0;
                    for (BlockLocal block : wallData.blocks) {
                        int wx = px + rotX(block.x, block.z, exit.rotation);
                        int wy = py + block.y;
                        int wz = pz + rotZ(block.x, block.z, exit.rotation);
                        try {
                            activeWorld.setBlock(wx, wy, wz, block.name, 0);
                            occupiedBlocks.add(packPos(wx, wy, wz));
                            placed++;
                        } catch (Exception ignored) {
                        }
                    }
                    LOGGER.at(Level.INFO).log("[Wall] sealed exit (%d,%d,%d) rot=%d %d/%d blocks",
                            px, py, pz, exit.rotation, placed, wallData.blocks.size());
                    return;
                }
                LOGGER.at(Level.WARNING).log("[Wall] failed to read wall prefab: %s", wallPath);
            }
        }
        try {
            activeWorld.setBlock(exit.worldX, exit.worldY, exit.worldZ, "Empty", 0);
        } catch (Exception ignored) {
        }
    }

    private int collectExits(@Nonnull PrefabData data, @Nonnull Vector3i pastePos,
                             int parentRot, @Nonnull List<OpenExit> openExits) {
        int count = 0;
        for (SpawnerLocal sp : data.spawners) {
            int wx = pastePos.x + rotX(sp.x, sp.z, parentRot);
            int wy = pastePos.y + sp.y;
            int wz = pastePos.z + rotZ(sp.x, sp.z, parentRot);
            int exitRot = (parentRot + sp.rot) & 3;
            openExits.add(new OpenExit(wx, wy, wz, exitRot));
            count++;
        }
        return count;
    }

    private PrefabData readPrefabData(@Nonnull Path path) {
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            int ax = root.has("anchorX") ? root.get("anchorX").getAsInt() : 0;
            int ay = root.has("anchorY") ? root.get("anchorY").getAsInt() : 0;
            int az = root.has("anchorZ") ? root.get("anchorZ").getAsInt() : 0;

            List<BlockLocal> blocks = new ArrayList<>();
            List<SpawnerLocal> spawners = new ArrayList<>();
            List<PrefabMobMarkerLocal> mobMarkers = new ArrayList<>();
            JsonArray arr = root.getAsJsonArray("blocks");
            if (arr != null) {
                for (JsonElement el : arr) {
                    JsonObject b = el.getAsJsonObject();
                    int x = b.get("x").getAsInt() - ax;
                    int y = b.get("y").getAsInt() - ay;
                    int z = b.get("z").getAsInt() - az;
                    String name = b.has("name") ? b.get("name").getAsString() : "";
                    boolean isSp = "Prefab_Spawner_Block".equals(name);
                    blocks.add(new BlockLocal(x, y, z, name, isSp));
                    if (isSp) {
                        int sRot = b.has("rotation") ? b.get("rotation").getAsInt() : 0;
                        spawners.add(new SpawnerLocal(x, y, z, sRot));
                    }
                }
            }

            JsonArray entities = root.getAsJsonArray("entities");
            if (entities != null) {
                for (JsonElement entityElement : entities) {
                    JsonObject entity = entityElement.getAsJsonObject();
                    JsonObject components = entity.getAsJsonObject("Components");
                    if (components == null) {
                        continue;
                    }

                    JsonObject spawnMarkerComponent = components.getAsJsonObject("SpawnMarkerComponent");
                    JsonObject transformComponent = components.getAsJsonObject("Transform");
                    if (spawnMarkerComponent == null || transformComponent == null) {
                        continue;
                    }

                    JsonObject position = transformComponent.getAsJsonObject("Position");
                    if (position == null) {
                        continue;
                    }

                    String mobId = spawnMarkerComponent.has("SpawnMarker")
                            ? spawnMarkerComponent.get("SpawnMarker").getAsString()
                            : "";
                    double x = position.has("X") ? position.get("X").getAsDouble() - ax : 0.0;
                    double y = position.has("Y") ? position.get("Y").getAsDouble() - ay : 0.0;
                    double z = position.has("Z") ? position.get("Z").getAsDouble() - az : 0.0;
                    mobMarkers.add(new PrefabMobMarkerLocal(x, y, z, mobId));
                }
            }

            return new PrefabData(blocks, spawners, mobMarkers);
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
            if (!block.isSpawner) {
                int wx = pastePos.x + rotX(block.x, block.z, rot);
                int wy = pastePos.y + block.y;
                int wz = pastePos.z + rotZ(block.x, block.z, rot);
                occupiedBlocks.add(packPos(wx, wy, wz));
            }
        }

        for (SpawnerLocal sp : data.spawners) {
            int wx = pastePos.x + rotX(sp.x, sp.z, rot);
            int wy = pastePos.y + sp.y;
            int wz = pastePos.z + rotZ(sp.x, sp.z, rot);
            try {
                world.setBlock(wx, wy, wz, "Empty", 0);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean wouldOverlap(@Nonnull PrefabData data, @Nonnull Vector3i pastePos, int rot) {
        for (BlockLocal block : data.blocks) {
            if (block.isSpawner) {
                continue;
            }
            int wx = pastePos.x + rotX(block.x, block.z, rot);
            int wy = pastePos.y + block.y;
            int wz = pastePos.z + rotZ(block.x, block.z, rot);
            if (occupiedBlocks.contains(packPos(wx, wy, wz))) {
                return true;
            }
        }
        return false;
    }

    private record SpawnerLocal(int x, int y, int z, int rot) {
    }

    private record BlockLocal(int x, int y, int z, String name, boolean isSpawner) {
    }

    private record PrefabMobMarkerLocal(double x, double y, double z, @Nonnull String mobId) {
    }

    private record PrefabData(List<BlockLocal> blocks, List<SpawnerLocal> spawners,
                              List<PrefabMobMarkerLocal> mobMarkers) {
    }

    private record OpenExit(int worldX, int worldY, int worldZ, int rotation) {
    }

    private record OpenExitWithParent(OpenExit exit, @Nullable RoomData parent) {
    }
}
