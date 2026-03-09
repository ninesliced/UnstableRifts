package dev.ninesliced.shotcave.dungeon;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.PrefabUtil;

import javax.annotation.Nonnull;
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
import java.util.logging.Logger;

public class DungeonGenerator {

    private static final Logger LOGGER = Logger.getLogger(DungeonGenerator.class.getName());
    private static final Gson GSON = new Gson();
    private static final int MAX_RETRIES = 5;

    private final Set<Long> occupiedBlocks = new HashSet<>();

    private List<Path> wallPaths;
    private World activeWorld;
    private Random activeRandom;

    private record SpawnerLocal(int x, int y, int z, int rot) {}
    private record BlockLocal(int x, int y, int z, String name, boolean isSpawner) {}
    private record PrefabData(List<BlockLocal> blocks, List<SpawnerLocal> spawners) {}
    private record OpenExit(int worldX, int worldY, int worldZ, int rotation) {}

    private static int rotX(int x, int z, int rot) {
        return switch (rot & 3) {
            case 1 ->  z;
            case 2 -> -x;
            case 3 -> -z;
            default -> x;
        };
    }

    private static int rotZ(int x, int z, int rot) {
        return switch (rot & 3) {
            case 1 -> -x;
            case 2 -> -z;
            case 3 ->  x;
            default -> z;
        };
    }

    private static Rotation toEngineRotation(int rot) {
        return switch (rot & 3) {
            case 1  -> Rotation.Ninety;
            case 2  -> Rotation.OneEighty;
            case 3  -> Rotation.TwoSeventy;
            default -> Rotation.None;
        };
    }

    public void generate(@Nonnull World world, long seed,
                         @Nonnull DungeonConfig.LevelConfig levelConfig) {
        Random random = new Random(seed);
        Store<EntityStore> store = world.getEntityStore().getStore();
        occupiedBlocks.clear();

        this.activeWorld = world;
        this.activeRandom = random;

        LOGGER.info("Generating dungeon '" + levelConfig.getName()
                + "' seed=" + seed + " targetRooms=" + levelConfig.getRooms());

        List<Path> entrancePaths = DungeonConfig.resolveGlobs(levelConfig.getPrefabs().getEntrance());
        List<Path> roomPaths = DungeonConfig.resolveGlobs(levelConfig.getPrefabs().getRoom());
        this.wallPaths = DungeonConfig.resolveGlobs(levelConfig.getPrefabs().getWall());
        List<Path> bossPaths = DungeonConfig.resolveGlobs(levelConfig.getPrefabs().getBoss());

        LOGGER.info("Resolved: entrances=" + entrancePaths.size()
                + " rooms=" + roomPaths.size()
                + " walls=" + wallPaths.size()
                + " bosses=" + bossPaths.size());

        if (entrancePaths.isEmpty()) {
            LOGGER.severe("No entrance prefabs!");
            return;
        }
        if (roomPaths.isEmpty()) {
            LOGGER.severe("No room prefabs!");
            return;
        }

        List<OpenExit> openExits = new ArrayList<>();
        int roomsPlaced = 0;

        Path entrancePath = DungeonConfig.pickRandom(random, entrancePaths);
        PrefabData entranceData = readPrefabData(entrancePath);
        if (entranceData == null) {
            LOGGER.severe("Failed to parse entrance!");
            return;
        }

        Vector3i entrancePaste = new Vector3i(0, 65, 0);
        pasteAndRegister(world, store, random, entrancePath, entranceData, entrancePaste, 0);
        collectExits(entranceData, entrancePaste, 0, openExits);

        LOGGER.info("Entrance placed at " + entrancePaste + ", " + openExits.size() + " exit(s)");

        while (roomsPlaced < levelConfig.getRooms() && !openExits.isEmpty()) {
            int idx = random.nextInt(openExits.size());
            OpenExit exit = openExits.remove(idx);

            if (tryPlaceRoom(world, store, random, roomPaths, exit, openExits,
                    "Room_" + (roomsPlaced + 1))) {
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
                if (tryPlaceRoom(world, store, random, bossPaths, exit, openExits, "Boss")) {
                    bossPlaced = true;
                    roomsPlaced++;
                    break;
                }
            }
            if (!bossPlaced && !openExits.isEmpty()) {
                OpenExit forceExit = openExits.remove(random.nextInt(openExits.size()));
                forcePlaceRoom(world, store, random, bossPaths, forceExit, openExits, "Boss(forced)");
                bossPlaced = true;
                roomsPlaced++;
            }
        }

        for (OpenExit exit : openExits) {
            sealExit(exit);
        }
        openExits.clear();

        LOGGER.info("Dungeon complete: " + roomsPlaced + " rooms, boss=" + bossPlaced);
    }

    private boolean tryPlaceRoom(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                  @Nonnull Random random, @Nonnull List<Path> roomPaths,
                                  @Nonnull OpenExit exit, @Nonnull List<OpenExit> openExits,
                                  @Nonnull String label) {
        List<Path> shuffled = new ArrayList<>(roomPaths);
        Collections.shuffle(shuffled, random);

        Vector3i pastePos = new Vector3i(exit.worldX, exit.worldY, exit.worldZ);
        int attempts = Math.min(MAX_RETRIES, shuffled.size());

        for (int i = 0; i < attempts; i++) {
            Path chosen = shuffled.get(i);
            PrefabData data = readPrefabData(chosen);
            if (data == null) {
                continue;
            }
            if (wouldOverlap(data, pastePos, exit.rotation)) {
                LOGGER.fine("[" + label + "] overlap retry " + (i + 1) + "/" + attempts
                        + " " + chosen.getFileName()
                        + " paste=" + pastePos + " rot=" + exit.rotation);
                continue;
            }

            try {
                pasteAndRegister(world, store, random, chosen, data, pastePos, exit.rotation);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[" + label + "] paste failed " + chosen.getFileName(), e);
                continue;
            }

            int newExitCount = collectExits(data, pastePos, exit.rotation, openExits);
            LOGGER.info("[" + label + "] " + chosen.getFileName()
                    + " paste=" + pastePos + " rot=" + exit.rotation
                    + " -> " + newExitCount + " new exit(s)");
            return true;
        }

        LOGGER.info("[" + label + "] " + attempts + " retries failed, sealing exit");
        sealExit(exit);
        return false;
    }

    private void forcePlaceRoom(@Nonnull World world, @Nonnull Store<EntityStore> store,
                                 @Nonnull Random random, @Nonnull List<Path> roomPaths,
                                 @Nonnull OpenExit exit, @Nonnull List<OpenExit> openExits,
                                 @Nonnull String label) {
        Path chosen = DungeonConfig.pickRandom(random, roomPaths);
        if (chosen == null) {
            return;
        }
        PrefabData data = readPrefabData(chosen);
        if (data == null) {
            return;
        }

        Vector3i pastePos = new Vector3i(exit.worldX, exit.worldY, exit.worldZ);
        try {
            pasteAndRegister(world, store, random, chosen, data, pastePos, exit.rotation);
            collectExits(data, pastePos, exit.rotation, openExits);
            LOGGER.info("[" + label + "] " + chosen.getFileName() + " force-placed at " + pastePos);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[" + label + "] force-paste failed", e);
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
                    LOGGER.info("[Wall] sealed exit (" + px + "," + py
                            + "," + pz + ") rot=" + exit.rotation
                            + " " + placed + "/" + wallData.blocks.size() + " blocks");
                    return;
                }
                LOGGER.warning("[Wall] failed to read wall prefab: " + wallPath);
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
            return new PrefabData(blocks, spawners);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read prefab: " + path, e);
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

    private static long packPos(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) | (((long) y & 0xFFFFF) << 22) | (((long) z & 0x3FFFFF) << 42);
    }
}
