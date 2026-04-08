package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.camera.TopCameraService;
import dev.ninesliced.unstablerifts.dungeon.*;
import dev.ninesliced.unstablerifts.hud.ChallengeHud;
import dev.ninesliced.unstablerifts.hud.DungeonInfoHud;
import dev.ninesliced.unstablerifts.hud.PartyStatusHud;
import dev.ninesliced.unstablerifts.hud.PortalPromptHudService;
import dev.ninesliced.unstablerifts.party.PartyManager;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * ECS ticking system that drives the dungeon game logic for players in a dungeon world.
 * <ul>
 *   <li>Updates HUDs (dungeon info + party status)</li>
 *   <li>Detects boss room entry and triggers boss phase</li>
 *   <li>Tracks mob deaths and triggers boss-room completion</li>
 *   <li>Awards money for kills</li>
 * </ul>
 */
public final class DungeonTickSystem extends EntityTickingSystem<EntityStore> {

    private static final Logger LOGGER = Logger.getLogger(DungeonTickSystem.class.getName());
    private static final double BOSS_ROOM_WALK_THRESHOLD = 10.0;
    private static final int LOCKED_ROOM_ENTRY_MARGIN = 5;
    private static final long LOGIC_UPDATE_INTERVAL_MS = 200L;
    private static final long HUD_UPDATE_INTERVAL_MS = 400L;

    private final Map<UUID, Long> lastLogicUpdateByParty = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHudUpdateByPlayer = new ConcurrentHashMap<>();
    /**
     * Tracks which room each player is currently in, for room-enter detection.
     */
    private final Map<UUID, RoomData> playerCurrentRoom = new ConcurrentHashMap<>();
    /**
     * Tracks how far each player has walked inside the boss room.
     */
    private final Map<UUID, BossEntryProgress> bossEntryProgressByPlayer = new ConcurrentHashMap<>();

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (!ref.isValid()) return;

        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null || !playerRef.isValid()) return;

        UnstableRifts unstablerifts = UnstableRifts.getInstance();
        if (unstablerifts == null) return;

        GameManager gameManager = unstablerifts.getGameManager();
        Game game = gameManager.findGameForPlayer(playerRef.getUuid());
        if (game == null) {
            lastHudUpdateByPlayer.remove(playerRef.getUuid());
            playerCurrentRoom.remove(playerRef.getUuid());
            bossEntryProgressByPlayer.remove(playerRef.getUuid());
            unstablerifts.getPortalInteractionService().clearPlayer(playerRef.getUuid());
            gameManager.normalizeOutsideDungeonState(playerRef, player, null, commandBuffer);
            unstablerifts.getCameraService().restoreDefault(playerRef);
            DungeonInfoHud.hideHud(player, playerRef);
            PartyStatusHud.hideHud(player, playerRef);
            PortalPromptHudService.hide(player, playerRef);
            return;
        }

        // Only process if the game is in an active state
        if (game.getState() != GameState.ACTIVE && game.getState() != GameState.BOSS
                && game.getState() != GameState.TRANSITIONING) {
            lastHudUpdateByPlayer.remove(playerRef.getUuid());
            bossEntryProgressByPlayer.remove(playerRef.getUuid());
            unstablerifts.getPortalInteractionService().clearPlayer(playerRef.getUuid());
            gameManager.normalizeOutsideDungeonState(playerRef, player, game, commandBuffer);
            unstablerifts.getCameraService().restoreDefault(playerRef);
            DungeonInfoHud.hideHud(player, playerRef);
            PartyStatusHud.hideHud(player, playerRef);
            PortalPromptHudService.hide(player, playerRef);
            return;
        }

        if (game.getInstanceWorld() == null || player.getWorld() != game.getInstanceWorld()) {
            lastHudUpdateByPlayer.remove(playerRef.getUuid());
            bossEntryProgressByPlayer.remove(playerRef.getUuid());
            unstablerifts.getPortalInteractionService().clearPlayer(playerRef.getUuid());
            gameManager.normalizeOutsideDungeonState(playerRef, player, game, commandBuffer);
            unstablerifts.getCameraService().restoreDefault(playerRef);
            DungeonInfoHud.hideHud(player, playerRef);
            PartyStatusHud.hideHud(player, playerRef);
            PortalPromptHudService.hide(player, playerRef);
            return;
        }

        DungeonConfig config = unstablerifts.loadDungeonConfig();
        long now = System.currentTimeMillis();
        Level level = game.getCurrentLevel();

        // Smoothly rotate camera to match corridor direction when the player enters a new room.
        updateCameraForRoom(ref, store, game, unstablerifts.getCameraService(), playerRef);

        // Detect player entering a locked room -> seal doors.
        detectRoomEntry(ref, store, game, unstablerifts, config, playerRef);

        RoomData currentRoom = level != null ? resolveCurrentRoom(ref, store, level) : null;
        RoomData hudRoom = level != null ? resolveChallengeHudRoom(ref, store, level, currentRoom) : null;

        // Update HUDs on a per-player cadence so iteration order does not starve one client.
        if (shouldRun(lastHudUpdateByPlayer, playerRef.getUuid(), now, HUD_UPDATE_INTERVAL_MS)) {
            updateHuds(player, playerRef, game, config, unstablerifts, hudRoom);
        }

        // Detect boss room proximity per player so multiplayer iteration order
        // cannot block or reset boss entry progress.
        if (game.getState() == GameState.ACTIVE && level != null) {
            checkBossRoomEntry(ref, store, game, level, gameManager, playerRef.getUuid());
        } else {
            bossEntryProgressByPlayer.remove(playerRef.getUuid());
        }

        // Update the portal confirmation HUD whenever an active portal is nearby.
        if (level != null && unstablerifts.getPortalService().hasActivePortals(level)) {
            updatePortalPrompt(ref, store, player, playerRef, game, unstablerifts);
        } else {
            unstablerifts.getPortalInteractionService().clearPlayer(playerRef.getUuid());
            PortalPromptHudService.hide(player, playerRef);
        }

        // Run shared dungeon logic once per party cadence instead of once per entity.
        if (!shouldRun(lastLogicUpdateByParty, game.getPartyId(), now, LOGIC_UPDATE_INTERVAL_MS)) {
            return;
        }

        if (level == null) return;

        // Track mob deaths and resolve room clear state.
        trackMobDeaths(game, level);

        // Update active challenges
        updateChallenges(game, level, unstablerifts);

        // Detect boss-room completion
        if (game.getState() == GameState.BOSS || game.getState() == GameState.ACTIVE) {
            RoomData bossRoom = level.getBossRoom();
            if (bossRoom != null && bossRoom.getExpectedMobCount() > 0 && bossRoom.areAllMobsDead()) {
                LOGGER.info("Boss room cleared for party " + game.getPartyId()
                        + " level=" + level.getName()
                        + " state=" + game.getState()
                        + " expected=" + bossRoom.getExpectedMobCount()
                        + " alive=" + bossRoom.getAliveMobCount()
                        + " spawnedRefs=" + bossRoom.getSpawnedMobs().size());
                bossRoom.setCleared(true);
                gameManager.onBossDefeated(game);
            }
        }

        // 30-second auto-close after last-level victory
        if (game.getVictoryTimestamp() > 0
                && game.getState() == GameState.TRANSITIONING
                && System.currentTimeMillis() - game.getVictoryTimestamp() > 30_000L) {
            World world = game.getInstanceWorld();
            if (world != null) {
                world.execute(() -> {
                    gameManager.broadcastToParty(game.getPartyId(),
                            "Time's up! Returning to the surface...");
                    gameManager.endGame(game, false);
                });
            }
        }
    }

    private void updateHuds(@Nonnull Player player, @Nonnull PlayerRef playerRef,
                            @Nonnull Game game, @Nonnull DungeonConfig config,
                            @Nonnull UnstableRifts unstablerifts,
                            @Nullable RoomData currentRoom) {
        // Dungeon Info HUD
        DungeonInfoHud infoHud = DungeonInfoHud.fromGame(playerRef, game, config.getDungeonName());
        DungeonInfoHud.applyHud(player, playerRef, infoHud);

        // Party Status HUD
        PartyManager partyManager = unstablerifts.getPartyManager();
        PartyManager.PartySnapshot snapshot = partyManager.getPartySnapshot(playerRef.getUuid());
        if (snapshot != null && !snapshot.members().isEmpty()) {
            Set<UUID> deadPlayers = game.getDeadPlayers();
            List<PartyStatusHud.MemberStatus> members = new ArrayList<>();
            for (PartyManager.PartyMemberSnapshot member : snapshot.members()) {
                members.add(PartyStatusHud.MemberStatus.fromUuid(
                        UUID.fromString(member.id()), member.name(), deadPlayers));
            }
            PartyStatusHud partyHud = new PartyStatusHud(playerRef, members);
            PartyStatusHud.applyHud(player, playerRef, partyHud);
        } else {
            PartyStatusHud.hideHud(player, playerRef);
        }

        // Challenge HUD — show while the player is inside a locked, uncleared room.
        if (currentRoom != null
                && (currentRoom.isLocked() || currentRoom.isChallengeActive())
                && !currentRoom.isCleared()
                && !currentRoom.getChallenges().isEmpty()) {
            ChallengeHud challengeHud = ChallengeHud.fromRoom(playerRef, currentRoom);
            ChallengeHud.applyHud(player, playerRef, challengeHud);
        } else {
            ChallengeHud.hideHud(player, playerRef);
        }
    }

    @Nullable
    private RoomData resolveCurrentRoom(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull Store<EntityStore> store,
                                        @Nonnull Level level) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }

        int px = (int) Math.floor(transform.getPosition().x);
        int py = (int) Math.floor(transform.getPosition().y);
        int pz = (int) Math.floor(transform.getPosition().z);
        return level.findRoomAt(px, py, pz);
    }

    @Nullable
    private RoomData resolveChallengeHudRoom(@Nonnull Ref<EntityStore> ref,
                                             @Nonnull Store<EntityStore> store,
                                             @Nonnull Level level,
                                             @Nullable RoomData fallbackRoom) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return fallbackRoom;
        }

        int px = (int) Math.floor(transform.getPosition().x);
        int py = (int) Math.floor(transform.getPosition().y);
        int pz = (int) Math.floor(transform.getPosition().z);

        RoomData bestRoom = null;
        int bestScore = Integer.MIN_VALUE;
        int bestArea = Integer.MAX_VALUE;

        for (RoomData room : level.getRooms()) {
            if (!room.hasBounds() || !room.contains(px, py, pz)) {
                continue;
            }

            int score = scoreChallengeHudRoom(room);
            int area = roomArea(room);
            if (score > bestScore || (score == bestScore && area < bestArea)) {
                bestRoom = room;
                bestScore = score;
                bestArea = area;
            }
        }

        if (bestRoom != null) {
            return bestRoom;
        }
        return fallbackRoom;
    }

    private int scoreChallengeHudRoom(@Nonnull RoomData room) {
        int score = 0;
        if (!room.getChallenges().isEmpty()) {
            score += 100;
        }
        if (room.isLocked() && !room.isCleared()) {
            score += 80;
        }
        if (room.isChallengeActive()) {
            score += 60;
        }
        if (room.getType() == RoomType.CHALLENGE) {
            score += 40;
        }
        if (room.isDoorsSealed()) {
            score += 20;
        }
        return score;
    }

    private int roomArea(@Nonnull RoomData room) {
        if (!room.hasBounds()) {
            return Integer.MAX_VALUE;
        }
        int width = Math.max(1, room.getBoundsMaxX() - room.getBoundsMinX() + 1);
        int depth = Math.max(1, room.getBoundsMaxZ() - room.getBoundsMinZ() + 1);
        return width * depth;
    }

    private void trackMobDeaths(@Nonnull Game game, @Nonnull Level level) {
        for (RoomData room : level.getRooms()) {
            if (room.isCleared()) continue;
            // Locked rooms are handled entirely by the challenge system.
            if (room.isLocked()) continue;

            if (room.areAllMobsDead() && room.getExpectedMobCount() > 0) {
                room.setCleared(true);
                UnstableRifts instance = UnstableRifts.getInstance();
                if (instance != null) {
                    instance.getDungeonMapService().onRoomCleared(game, room);
                    // Unseal doors when room is cleared.
                    World w = game.getInstanceWorld();
                    if (w != null) {
                        instance.getDoorService().onRoomCleared(room, w);
                        // Spawn portals in locked rooms after clearing.
                        if (room.getType() != RoomType.BOSS && !room.getPortalPositions().isEmpty()) {
                            w.execute(() -> instance.getPortalService().spawnPortal(room, w));
                        }
                    }
                    broadcastRoomMessage(instance, game, room.getUnlockTitle(), room.getUnlockSubtitle());
                }
            }
        }
    }

    private void checkBossRoomEntry(@Nonnull Ref<EntityStore> ref,
                                    @Nonnull Store<EntityStore> store,
                                    @Nonnull Game game, @Nonnull Level level,
                                    @Nonnull GameManager gameManager,
                                    @Nonnull UUID playerId) {
        RoomData bossRoom = level.getBossRoom();
        if (bossRoom == null) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d pos = transform.getPosition();
        int px = (int) Math.floor(pos.x);
        int py = (int) Math.floor(pos.y);
        int pz = (int) Math.floor(pos.z);
        RoomData currentRoom = level.findRoomAt(px, py, pz);

        if (currentRoom != bossRoom) {
            bossEntryProgressByPlayer.remove(playerId);
            return;
        }

        BossEntryProgress progress = bossEntryProgressByPlayer.computeIfAbsent(playerId, ignored -> new BossEntryProgress());
        Vector3d lastPos = progress.lastPosition;
        if (lastPos != null) {
            double dx = pos.x - lastPos.x;
            double dy = pos.y - lastPos.y;
            double dz = pos.z - lastPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            progress.distanceWalked += dist;
        }
        progress.lastPosition = new Vector3d(pos);

        if (progress.distanceWalked >= BOSS_ROOM_WALK_THRESHOLD) {
            bossEntryProgressByPlayer.remove(playerId);
            gameManager.enterBossPhase(game);
        }
    }

    private void updatePortalPrompt(@Nonnull Ref<EntityStore> ref,
                                    @Nonnull Store<EntityStore> store,
                                    @Nonnull Player player,
                                    @Nonnull PlayerRef playerRef,
                                    @Nonnull Game game,
                                    @Nonnull UnstableRifts unstablerifts) {
        DeathComponent death = store.getComponent(ref, DeathComponent.getComponentType());
        if (death != null && death.isDead()) {
            PortalPromptHudService.hide(player, playerRef);
            return;
        }

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            PortalPromptHudService.hide(player, playerRef);
            return;
        }

        PortalInteractionService.PortalPrompt prompt = unstablerifts.getPortalInteractionService()
                .resolvePrompt(game, playerRef.getUuid(), transform.getPosition());
        if (prompt == null) {
            PortalPromptHudService.hide(player, playerRef);
            return;
        }

        PortalPromptHudService.show(player, playerRef, prompt.title(), prompt.detail());
    }

    private void updateCameraForRoom(@Nonnull Ref<EntityStore> ref,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull Game game,
                                     @Nonnull TopCameraService cameraService,
                                     @Nonnull PlayerRef playerRef) {
        Level level = game.getCurrentLevel();
        if (level == null) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        int px = (int) Math.floor(transform.getPosition().x);
        int py = (int) Math.floor(transform.getPosition().y);
        int pz = (int) Math.floor(transform.getPosition().z);
        RoomData room = level.findRoomAt(px, py, pz);
        if (room == null) return;

        cameraService.updateCameraForRoom(playerRef, room.getRotation());
    }

    private void detectRoomEntry(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull Game game,
                                 @Nonnull UnstableRifts unstablerifts,
                                 @Nonnull DungeonConfig config,
                                 @Nonnull PlayerRef playerRef) {
        Level level = game.getCurrentLevel();
        if (level == null) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        int px = (int) Math.floor(transform.getPosition().x);
        int py = (int) Math.floor(transform.getPosition().y);
        int pz = (int) Math.floor(transform.getPosition().z);
        RoomData room = level.findRoomAt(px, py, pz);
        if (room == null) return;

        UUID playerId = playerRef.getUuid();
        RoomData previousRoom = playerCurrentRoom.put(playerId, room);
        boolean roomChanged = previousRoom != room;

        if (roomChanged) {
            // Broadcast exit message for the room the player just left.
            if (previousRoom != null) {
                broadcastRoomMessage(unstablerifts, game, previousRoom.getExitTitle(), previousRoom.getExitSubtitle());
            }

            // Broadcast enter message for the new room.
            broadcastRoomMessage(unstablerifts, game, room.getEnterTitle(), room.getEnterSubtitle());

            if (room.getType() == RoomType.SHOP) {
                World world = game.getInstanceWorld();
                if (world != null) {
                    world.execute(() -> {
                        Store<EntityStore> roomStore = world.getEntityStore().getStore();
                        unstablerifts.getShopService().refreshOnFirstRoomEntry(game, room, roomStore);
                    });
                }
            }
        }

        trySealLockedRoomInsideTriggerBox(px, py, pz, room, level, game, unstablerifts, config);

        // Unlocked rooms activate on first entry. Locked rooms wait until the seal actually happens.
        if (!room.getChallenges().isEmpty()
                && !room.isChallengeActive()
                && !room.isCleared()
                && ((!room.isLocked() && roomChanged) || room.isDoorsSealed())) {
            room.setChallengeActive(true);
        }
    }

    private void trySealLockedRoomInsideTriggerBox(int px, int py, int pz,
                                                   @Nonnull RoomData room,
                                                   @Nonnull Level level,
                                                   @Nonnull Game game,
                                                   @Nonnull UnstableRifts unstablerifts,
                                                   @Nonnull DungeonConfig config) {
        if (!room.isLocked() || room.isCleared() || room.isDoorsSealed()) {
            return;
        }

        if (unstablerifts.getDoorService().hasRemainingKeyDoors(level, room)) {
            return;
        }

        if (!isInsideLockedRoomTriggerBox(room, px, py, pz)) {
            return;
        }

        sealLockedRoom(room, game, unstablerifts, config);
    }

    private boolean isInsideLockedRoomTriggerBox(@Nonnull RoomData room, int x, int y, int z) {
        if (!room.contains(x, y, z)) {
            return false;
        }
        if (!room.hasBounds()) {
            return true;
        }

        LockedRoomEntrySide entrySide = resolveLockedRoomEntrySide(room);
        int minX = room.getBoundsMinX();
        int maxX = room.getBoundsMaxX();
        int minZ = room.getBoundsMinZ();
        int maxZ = room.getBoundsMaxZ();

        switch (entrySide) {
            case MIN_X -> minX = Math.min(maxX, minX + LOCKED_ROOM_ENTRY_MARGIN);
            case MAX_X -> maxX = Math.max(minX, maxX - LOCKED_ROOM_ENTRY_MARGIN);
            case MIN_Z -> minZ = Math.min(maxZ, minZ + LOCKED_ROOM_ENTRY_MARGIN);
            case MAX_Z -> maxZ = Math.max(minZ, maxZ - LOCKED_ROOM_ENTRY_MARGIN);
        }

        return x >= minX && x <= maxX
                && z >= minZ && z <= maxZ
                && room.containsY(y);
    }

    private LockedRoomEntrySide resolveLockedRoomEntrySide(@Nonnull RoomData room) {
        // Dungeon room prefabs are authored with their entrance on the local +Z edge.
        // Rotating the room tells us which world-space side should keep the 5-block grace zone.
        return switch (room.getRotation() & 3) {
            case 1 -> LockedRoomEntrySide.MAX_X;
            case 2 -> LockedRoomEntrySide.MIN_Z;
            case 3 -> LockedRoomEntrySide.MIN_X;
            default -> LockedRoomEntrySide.MAX_Z;
        };
    }

    private void sealLockedRoom(@Nonnull RoomData room,
                                @Nonnull Game game,
                                @Nonnull UnstableRifts unstablerifts,
                                @Nonnull DungeonConfig config) {
        if (!room.isLocked() || room.isCleared() || room.isDoorsSealed()) {
            return;
        }

        DungeonConfig.LevelConfig levelConfig = null;
        String selector = game.getCurrentLevelSelector();
        if (selector != null && !selector.isBlank()) {
            levelConfig = config.findLevel(selector);
        }
        if (levelConfig == null && game.getCurrentLevelIndex() < config.getLevels().size()) {
            levelConfig = config.getLevels().get(game.getCurrentLevelIndex());
        }

        // Lock the room using prefab-based blockers only so we do not
        // race a plain block seal against the door prefab pass.
        World world = game.getInstanceWorld();
        if (world != null && levelConfig != null) {
            DungeonConfig.LevelConfig lc = levelConfig;
            room.setDoorsSealed(true);
            world.execute(() -> {
                DungeonGenerator.pasteConfiguredDoorMarkers(world, lc, room, EnumSet.of(DoorMode.ACTIVATOR), true);
                DungeonGenerator.pasteLockDoor(world, lc, room, room.getAnchor(), room.getRotation());
                DungeonGenerator.pasteSealDoorsAtRoomExits(world, lc, room);
            });
        }

        // Spawn ALL deferred mobs on room entry (pinned + random pool).
        // Must use world.execute() — can't add entities during tick processing.
        if (world != null) {
            world.execute(() -> {
                Store<EntityStore> eStore = world.getEntityStore().getStore();
                unstablerifts.getGameManager().getMobSpawningService().spawnRoomMobs(room, eStore);
            });
        }

        // Locked rooms with mobs should not instantly clear just because the
        // prefab has no explicit challenge marker. Fall back to a mob-clear
        // objective so the doors stay shut until the encounter is finished.
        if (room.getChallenges().isEmpty() && hasDeferredEncounterMobs(room)) {
            room.addChallenge(new ChallengeObjective(ChallengeObjective.Type.MOB_CLEAR, room.getAnchor()));
        }

        // If no challenges at all → instantly mark as completed and open doors.
        if (room.getChallenges().isEmpty()) {
            room.setCleared(true);
            if (world != null && room.isDoorsSealed()) {
                unstablerifts.getDoorService().onRoomCleared(room, world);
            }
            if (world != null && !room.getPortalPositions().isEmpty()) {
                world.execute(() -> unstablerifts.getPortalService().spawnPortal(room, world));
            }
            unstablerifts.getDungeonMapService().onRoomCleared(game, room);
            broadcastRoomMessage(unstablerifts, game, room.getUnlockTitle(), room.getUnlockSubtitle());
        }
    }

    private boolean hasDeferredEncounterMobs(@Nonnull RoomData room) {
        if (room.getExpectedMobCount() > 0) {
            return true;
        }
        if (!room.getMobsToSpawn().isEmpty()) {
            return true;
        }
        if (!room.getPinnedMobSpawns().isEmpty()) {
            return true;
        }
        return !room.getPrefabMobMarkerPositions().isEmpty();
    }

    private void updateChallenges(@Nonnull Game game, @Nonnull Level level, @Nonnull UnstableRifts unstablerifts) {
        Set<UUID> partyPlayers = game.getPlayersInInstance();
        if (partyPlayers.isEmpty()) return;

        for (RoomData room : level.getRooms()) {
            if (!room.isChallengeActive() || room.isCleared()) continue;

            boolean allComplete = true;
            for (ChallengeObjective obj : room.getChallenges()) {
                if (obj.isCompleted()) continue;

                switch (obj.getType()) {
                    case ACTIVATION_ZONE -> {
                        // Complete if any party player is within 2 blocks of the zone.
                        Vector3i zonePos = obj.getPosition();
                        for (UUID playerId : partyPlayers) {
                            PlayerRef pRef = Universe.get().getPlayer(playerId);
                            if (pRef == null || !pRef.isValid()) continue;
                            Ref<EntityStore> pEntityRef = pRef.getReference();
                            if (pEntityRef == null || !pEntityRef.isValid()) continue;
                            TransformComponent t = pEntityRef.getStore().getComponent(
                                    pEntityRef, TransformComponent.getComponentType());
                            if (t == null) continue;
                            double dx = t.getPosition().x - (zonePos.x + 0.5);
                            double dy = t.getPosition().y - (zonePos.y + 0.5);
                            double dz = t.getPosition().z - (zonePos.z + 0.5);
                            if (dx * dx + dy * dy + dz * dz <= 4.0) { // 2 blocks radius
                                obj.complete();
                                break;
                            }
                        }
                    }
                    case MOB_CLEAR -> {
                        // Complete if all mobs in the room are dead.
                        if (room.areAllMobsDead() && room.getExpectedMobCount() > 0) {
                            obj.complete();
                        }
                    }
                }

                if (!obj.isCompleted()) {
                    allComplete = false;
                }
            }

            if (allComplete) {
                room.setChallengeActive(false);
                room.setCleared(true);
                // Unseal doors.
                World world = game.getInstanceWorld();
                if (world != null && room.isDoorsSealed()) {
                    unstablerifts.getDoorService().onRoomCleared(room, world);
                }
                // Spawn portals in cleared challenge rooms.
                if (world != null && !room.getPortalPositions().isEmpty()) {
                    world.execute(() -> unstablerifts.getPortalService().spawnPortal(room, world));
                }
                unstablerifts.getDungeonMapService().onRoomCleared(game, room);
                broadcastRoomMessage(unstablerifts, game, room.getUnlockTitle(), room.getUnlockSubtitle());
            }
        }
    }

    private static void broadcastRoomMessage(@Nonnull UnstableRifts unstablerifts,
                                               @Nonnull Game game,
                                               @Nonnull String title,
                                               @Nonnull String subtitle) {
        if (title.isEmpty() && subtitle.isEmpty()) return;
        unstablerifts.getGameManager().broadcastToParty(
                game.getPartyId(), title.isEmpty() ? " " : title, subtitle.isEmpty() ? null : subtitle);
    }

    private boolean shouldRun(@Nonnull Map<UUID, Long> lastRunTimes,
                              @Nonnull UUID key,
                              long now,
                              long intervalMs) {
        Long lastRun = lastRunTimes.get(key);
        if (lastRun != null && now - lastRun < intervalMs) {
            return false;
        }
        lastRunTimes.put(key, now);
        return true;
    }

    private static final class BossEntryProgress {
        @Nullable
        private Vector3d lastPosition;
        private double distanceWalked;
    }

    private enum LockedRoomEntrySide {
        MIN_X,
        MAX_X,
        MIN_Z,
        MAX_Z
    }
}
