package dev.ninesliced.unstablerifts.dungeon.map;

import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.protocol.packets.worldmap.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.dungeon.GameState;
import dev.ninesliced.unstablerifts.dungeon.Level;
import dev.ninesliced.unstablerifts.dungeon.RoomData;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class DungeonMapService {

    private static final Logger LOGGER = Logger.getLogger(DungeonMapService.class.getName());
    private static final int SEND_BATCH_SIZE = 15;
    private static final String MARKER_ICON = "User1.png";

    private final DungeonMapLegendService legendService;
    private final Map<UUID, Map<Long, MapChunk>> cachedChunks = new ConcurrentHashMap<>();
    private final Map<UUID, MapMarker[]> cachedMarkers = new ConcurrentHashMap<>();
    private final Map<UUID, SmoothedDungeonGrid> cachedGrids = new ConcurrentHashMap<>();

    public DungeonMapService(@Nonnull DungeonMapLegendService legendService) {
        this.legendService = legendService;
    }

    @Nonnull
    private static MapMarker[] buildMarkers(@Nonnull Level level) {
        List<MapMarker> markers = new ArrayList<>();
        addRoomMarker(markers, level.getEntranceRoom(), "Spawn", (byte) 74, (byte) -34, (byte) -128);
        return markers.toArray(new MapMarker[0]);
    }

    private static void addRoomMarker(@Nonnull List<MapMarker> markers,
                                      @Nullable RoomData room,
                                      @Nonnull String label,
                                      byte r, byte g, byte b) {
        if (room == null || !room.hasBounds()) return;

        float centerX = (room.getBoundsMinX() + room.getBoundsMaxX()) / 2.0f + 0.5f;
        float centerZ = (room.getBoundsMinZ() + room.getBoundsMaxZ()) / 2.0f + 0.5f;

        Transform transform = new Transform();
        transform.position = new Position(centerX, room.getAnchor().y, centerZ);
        transform.orientation = new Direction(0, 0, 0);

        FormattedMessage name = new FormattedMessage();
        name.rawText = label;

        MapMarker marker = new MapMarker(
                "unstablerifts_" + label.toLowerCase(),
                name,
                MARKER_ICON,
                transform,
                null,
                new MapMarkerComponent[]{new TintComponent(new Color(r, g, b))}
        );
        markers.add(marker);
    }

    private static void broadcastPacket(@Nonnull Game game, @Nonnull UpdateWorldMap packet) {
        for (UUID playerId : game.getPlayersInInstance()) {
            PlayerRef playerRef = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(playerId);
            if (playerRef != null && playerRef.isValid()) {
                try {
                    playerRef.getPacketHandler().write(packet);
                } catch (Exception e) {
                    LOGGER.warning("Failed to send map update to player " + playerId + ": " + e.getMessage());
                }
            }
        }
    }

    public void buildMap(@Nonnull Game game) {
        Level level = game.getCurrentLevel();
        if (level == null) {
            LOGGER.warning("Cannot build dungeon map: no current level");
            return;
        }

        // Collect old chunks and markers to clear from client
        Map<Long, MapChunk> oldChunks = cachedChunks.get(game.getPartyId());
        MapMarker[] oldMarkers = cachedMarkers.get(game.getPartyId());

        SmoothedDungeonGrid grid = SmoothedDungeonGrid.build(level);
        cachedGrids.put(game.getPartyId(), grid);

        Map<Long, MapImage> images = DungeonMapRenderer.renderAll(level, grid);
        Map<Long, MapChunk> chunks = new ConcurrentHashMap<>();
        for (Map.Entry<Long, MapImage> entry : images.entrySet()) {
            int cx = DungeonMapRenderer.unpackChunkX(entry.getKey());
            int cz = DungeonMapRenderer.unpackChunkZ(entry.getKey());
            chunks.put(entry.getKey(), new MapChunk(cx, cz, entry.getValue()));
        }
        cachedChunks.put(game.getPartyId(), chunks);

        MapMarker[] markers = buildMarkers(level);
        cachedMarkers.put(game.getPartyId(), markers);

        // If there were old chunks, clear them on all clients before sending the new map
        if (oldChunks != null && !oldChunks.isEmpty()) {
            // Build clear packets for old chunks that are not in the new map
            List<MapChunk> clearChunks = new ArrayList<>();
            for (Map.Entry<Long, MapChunk> entry : oldChunks.entrySet()) {
                if (!chunks.containsKey(entry.getKey())) {
                    MapChunk old = entry.getValue();
                    clearChunks.add(new MapChunk(old.chunkX, old.chunkZ, null));
                }
            }

            // Collect old marker IDs to remove
            String[] removedMarkerIds = null;
            if (oldMarkers != null && oldMarkers.length > 0) {
                removedMarkerIds = new String[oldMarkers.length];
                for (int i = 0; i < oldMarkers.length; i++) {
                    removedMarkerIds[i] = oldMarkers[i].id;
                }
            }

            if (!clearChunks.isEmpty() || removedMarkerIds != null) {
                UpdateWorldMap clearPacket = new UpdateWorldMap(
                        clearChunks.isEmpty() ? null : clearChunks.toArray(new MapChunk[0]),
                        null,
                        removedMarkerIds
                );
                broadcastPacket(game, clearPacket);
            }

            // Send the new map to all players
            for (UUID playerId : game.getPlayersInInstance()) {
                PlayerRef playerRef = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(playerId);
                if (playerRef != null && playerRef.isValid()) {
                    sendMapToPlayer(playerRef, game);
                }
            }
        }

        LOGGER.info("Dungeon map built: " + chunks.size() + " chunks, " + markers.length + " markers");
    }

    public void sendMapToPlayer(@Nonnull PlayerRef playerRef, @Nonnull Game game) {
        Map<Long, MapChunk> chunks = cachedChunks.get(game.getPartyId());
        MapMarker[] markers = cachedMarkers.get(game.getPartyId());
        if (chunks == null || chunks.isEmpty()) {
            if (game.getCurrentLevel() == null || game.getState() == GameState.COMPLETE) {
                legendService.clear(playerRef);
            }
            return;
        }

        List<MapChunk> allChunks = new ArrayList<>(chunks.values());

        for (int i = 0; i < allChunks.size(); i += SEND_BATCH_SIZE) {
            int end = Math.min(i + SEND_BATCH_SIZE, allChunks.size());
            MapChunk[] batch = allChunks.subList(i, end).toArray(new MapChunk[0]);
            MapMarker[] batchMarkers = (i == 0 && markers != null && markers.length > 0) ? markers : null;
            UpdateWorldMap packet = new UpdateWorldMap(batch, batchMarkers, null);
            playerRef.getPacketHandler().write(packet);
        }

        legendService.sendLegend(playerRef, game);
    }

    public void onRoomCleared(@Nonnull Game game, @Nonnull RoomData room) {
        Map<Long, MapChunk> cached = cachedChunks.get(game.getPartyId());
        SmoothedDungeonGrid grid = cachedGrids.get(game.getPartyId());
        if (cached == null || grid == null) return;

        Map<Long, MapImage> updated = DungeonMapRenderer.renderChunksForRoom(grid, room);
        List<MapChunk> updatedChunks = new ArrayList<>();
        for (Map.Entry<Long, MapImage> entry : updated.entrySet()) {
            int cx = DungeonMapRenderer.unpackChunkX(entry.getKey());
            int cz = DungeonMapRenderer.unpackChunkZ(entry.getKey());
            MapChunk chunk = new MapChunk(cx, cz, entry.getValue());
            cached.put(entry.getKey(), chunk);
            updatedChunks.add(chunk);
        }

        if (updatedChunks.isEmpty()) return;

        MapChunk[] chunkArray = updatedChunks.toArray(new MapChunk[0]);
        UpdateWorldMap packet = new UpdateWorldMap(chunkArray, null, null);
        broadcastPacket(game, packet);
    }

    public void cleanup(@Nonnull UUID partyId) {
        cachedChunks.remove(partyId);
        cachedMarkers.remove(partyId);
        cachedGrids.remove(partyId);
    }
}
