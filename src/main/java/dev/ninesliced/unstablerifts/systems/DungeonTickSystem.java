package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.camera.TopCameraService;
import dev.ninesliced.unstablerifts.dungeon.*;
import dev.ninesliced.unstablerifts.hud.ChallengeHud;
import dev.ninesliced.unstablerifts.hud.DungeonInfoHud;
import dev.ninesliced.unstablerifts.hud.PartyStatusHud;
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
    private static final long LOGIC_UPDATE_INTERVAL_MS = 200L;
    private static final long HUD_UPDATE_INTERVAL_MS = 400L;
    private static final long PORTAL_ENTRY_ARM_DELAY_MS = 1500L;

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
    /**
     * Last time each player was observed off any active portal pad footprint.
     */
    private final Map<UUID, Long> lastPortalStepOffAtByPlayer = new ConcurrentHashMap<>();
    /**
     * Prevents double-trigger of portal teleport per party.
     */
    private final Set<UUID> partiesInTransit = ConcurrentHashMap.newKeySet();

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
            lastPortalStepOffAtByPlayer.remove(playerRef.getUuid());
            gameManager.normalizeOutsideDungeonState(playerRef, player, null, commandBuffer);
            unstablerifts.getCameraService().restoreDefault(playerRef);
            DungeonInfoHud.hideHud(player, playerRef);
            PartyStatusHud.hideHud(player, playerRef);
            return;
        }

        // Only process if the game is in an active state
        if (game.getState() != GameState.ACTIVE && game.getState() != GameState.BOSS
                && game.getState() != GameState.TRANSITIONING) {
            lastHudUpdateByPlayer.remove(playerRef.getUuid());
            bossEntryProgressByPlayer.remove(playerRef.getUuid());
            lastPortalStepOffAtByPlayer.remove(playerRef.getUuid());
            gameManager.normalizeOutsideDungeonState(playerRef, player, game, commandBuffer);
            unstablerifts.getCameraService().restoreDefault(playerRef);
            DungeonInfoHud.hideHud(player, playerRef);
            PartyStatusHud.hideHud(player, playerRef);
            return;
        }

        if (game.getInstanceWorld() == null || player.getWorld() != game.getInstanceWorld()) {
            lastHudUpdateByPlayer.remove(playerRef.getUuid());
            bossEntryProgressByPlayer.remove(playerRef.getUuid());
            lastPortalStepOffAtByPlayer.remove(playerRef.getUuid());
            gameManager.normalizeOutsideDungeonState(playerRef, player, game, commandBuffer);
            unstablerifts.getCameraService().restoreDefault(playerRef);
            DungeonInfoHud.hideHud(player, playerRef);
            PartyStatusHud.hideHud(player, playerRef);
            return;
        }

        DungeonConfig config = unstablerifts.loadDungeonConfig();
        long now = System.currentTimeMillis();
        Level level = game.getCurrentLevel();

        // Update HUDs on a per-player cadence so iteration order does not starve one client.
        if (shouldRun(lastHudUpdateByPlayer, playerRef.getUuid(), now, HUD_UPDATE_INTERVAL_MS)) {
            updateHuds(player, playerRef, game, config, unstablerifts);
        }

        // Smoothly rotate camera to match corridor direction when the player enters a new room.
        updateCameraForRoom(ref, store, game, unstablerifts.getCameraService(), playerRef);

        // Detect player entering a locked room -> seal doors.
        detectRoomEntry(ref, store, game, unstablerifts, config, playerRef);

        // Detect boss room proximity per player so multiplayer iteration order
        // cannot block or reset boss entry progress.
        if (game.getState() == GameState.ACTIVE && level != null) {
            checkBossRoomEntry(ref, store, game, level, gameManager, playerRef.getUuid());
        } else {
            bossEntryProgressByPlayer.remove(playerRef.getUuid());
        }

        // Check portal collision whenever any spawned portal is active in the current level.
        if (level != null && unstablerifts.getPortalService().hasActivePortals(level)) {
            checkPortalCollision(ref, store, game, unstablerifts, gameManager, playerRef.getUuid());
        } else {
            lastPortalStepOffAtByPlayer.remove(playerRef.getUuid());
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
                            "Time's up! Returning to the surface...", DungeonConstants.COLOR_SOFT_DANGER);
                    gameManager.endGame(game, false);
                });
            }
        }
    }

    private void updateHuds(@Nonnull Player player, @Nonnull PlayerRef playerRef,
                            @Nonnull Game game, @Nonnull DungeonConfig config,
                            @Nonnull UnstableRifts unstablerifts) {
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

        // Challenge HUD — show if player is in a room with active challenges.
        RoomData currentRoom = playerCurrentRoom.get(playerRef.getUuid());
        if (currentRoom != null && currentRoom.isChallengeActive() && !currentRoom.isCleared()) {
            ChallengeHud challengeHud = ChallengeHud.fromRoom(playerRef, currentRoom);
            ChallengeHud.applyHud(player, playerRef, challengeHud);
        } else {
            ChallengeHud.hideHud(player, playerRef);
        }
    }

    private void trackMobDeaths(@Nonnull Game game, @Nonnull Level level) {
        for (RoomData room : level.getRooms()) {
            if (room.isCleared()) continue;

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
                    instance.getGameManager().broadcastToParty(game.getPartyId(),
                            "Room cleared! Doors are now open.", DungeonConstants.COLOR_SUCCESS);
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

    private void checkPortalCollision(@Nonnull Ref<EntityStore> ref,
                                      @Nonnull Store<EntityStore> store,
                                      @Nonnull Game game,
                                      @Nonnull UnstableRifts unstablerifts,
                                      @Nonnull GameManager gameManager,
                                      @Nonnull UUID playerId) {
        Level level = game.getCurrentLevel();
        if (level == null) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d pos = transform.getPosition();
        int bx = (int) Math.floor(pos.x);
        int by = (int) Math.floor(pos.y);
        int bz = (int) Math.floor(pos.z);
        PortalService.ActivePortal activePortal = unstablerifts.getPortalService().getActivePortalAt(level, bx, by, bz);
        if (activePortal == null) {
            lastPortalStepOffAtByPlayer.put(playerId, System.currentTimeMillis());
            return;
        }

        if (System.currentTimeMillis() - activePortal.activatedAt() < PORTAL_ENTRY_ARM_DELAY_MS) {
            return;
        }

        // Prevent auto-trigger when a portal appears under a player: they must
        // have been observed off the pad at least once after this activation.
        long lastStepOffAt = lastPortalStepOffAtByPlayer.getOrDefault(playerId, Long.MIN_VALUE);
        if (lastStepOffAt <= activePortal.activatedAt()) {
            return;
        }

        lastPortalStepOffAtByPlayer.put(playerId, Long.MIN_VALUE);
        LOGGER.info("Portal collision accepted for party " + game.getPartyId()
                + " player=" + playerId
                + " pos=" + pos
                + " portalMode=" + activePortal.portal().mode()
                + " activatedAt=" + activePortal.activatedAt()
                + " state=" + game.getState());

        if (activePortal.portal().mode() == PortalMode.NEXT_LEVEL) {
            if (game.getState() != GameState.TRANSITIONING) {
                return;
            }
            if (partiesInTransit.contains(game.getPartyId())) return;

            if (partiesInTransit.add(game.getPartyId())) {
                World world = game.getInstanceWorld();
                if (world != null) {
                    UUID partyId = game.getPartyId();
                    world.execute(() -> {
                        gameManager.onPortalEntered(game, playerId);
                        partiesInTransit.remove(partyId);
                    });
                } else {
                    partiesInTransit.remove(game.getPartyId());
                }
            }
            return;
        }

        World world = game.getInstanceWorld();
        RoomData sourceRoom = level.findRoomAt(bx, by, bz);
        RoomData fallbackRoom = activePortal.room();
        if (sourceRoom == null) {
            sourceRoom = fallbackRoom;
        }
        if (world != null) {
            RoomData currentRoom = sourceRoom;
            world.execute(() -> gameManager.onClosestExitPortalEntered(game, playerId, currentRoom, fallbackRoom));
        }
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

        // Only trigger on room change.
        if (previousRoom == room) return;

        // Player just entered a new room — check if it's locked.
        if (room.isLocked() && !room.isCleared() && !room.isDoorsSealed()) {
            DungeonConfig.LevelConfig levelConfig = null;
            String selector = game.getCurrentLevelSelector();
            if (selector != null && !selector.isBlank()) {
                levelConfig = config.findLevel(selector);
            }
            if (levelConfig == null && game.getCurrentLevelIndex() < config.getLevels().size()) {
                levelConfig = config.getLevels().get(game.getCurrentLevelIndex());
            }
            String doorBlock = levelConfig != null ? levelConfig.getDoorBlock() : DungeonConstants.DEFAULT_DOOR_BLOCK;
            unstablerifts.getDoorService().onPlayerEnterRoom(room, game, level, game.getInstanceWorld(), doorBlock);
            unstablerifts.getGameManager().broadcastToParty(game.getPartyId(),
                    "The room has been sealed! Defeat all enemies to escape!", DungeonConstants.COLOR_DANGER);

            // Lock the room entrance at the anchor, and seal every outbound exit spawner.
            World world = game.getInstanceWorld();
            if (world != null && levelConfig != null) {
                DungeonConfig.LevelConfig lc = levelConfig;
                room.setDoorsSealed(true);
                world.execute(() -> {
                    DungeonGenerator.pasteConfiguredDoorMarkers(world, lc, room);
                    DungeonGenerator.pasteLockDoor(world, lc, room, room.getAnchor(), room.getRotation());
                    DungeonGenerator.pasteSealDoorsAtRoomExits(world, lc, room);
                });
            }

            // Spawn pinned mobs on room entry (deferred from level start).
            // Must use world.execute() — can't add entities during tick processing.
            if (world != null && !room.getPinnedMobSpawns().isEmpty()) {
                world.execute(() -> {
                    Store<EntityStore> eStore = world.getEntityStore().getStore();
                    unstablerifts.getGameManager().getMobSpawningService().spawnPinnedMobs(room, eStore);
                });
            }
        }

        // Activate challenge on first entry (any room with challenge markers).
        if (!room.getChallenges().isEmpty()
                && !room.isChallengeActive() && !room.isCleared()) {
            room.setChallengeActive(true);
            // Spawn mobs for MOB_ACTIVATOR objectives.
            World world = game.getInstanceWorld();
            if (world != null) {
                Store<EntityStore> eStore = world.getEntityStore().getStore();
                for (ChallengeObjective obj : room.getChallenges()) {
                    if (obj.getType() == ChallengeObjective.Type.MOB_ACTIVATOR && !obj.isMobSpawned()) {
                        spawnMobActivator(obj, room, eStore);
                    }
                }
            }
        }
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
                    case MOB_ACTIVATOR -> {
                        // Complete when the summoned mob has actually died.
                        if (obj.isMobSpawned()) {
                            Ref<EntityStore> mobRef = obj.getSpawnedMob();
                            if (room.isMobDefeated(mobRef)) {
                                obj.complete();
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
            }
        }
    }

    private void spawnMobActivator(@Nonnull ChallengeObjective obj,
                                   @Nonnull RoomData room,
                                   @Nonnull Store<EntityStore> store) {
        Map<String, Integer> pool = obj.getMobPool();
        String mobId;
        if (pool != null && !pool.isEmpty()) {
            // Weighted random pick from pool.
            int totalWeight = pool.values().stream().mapToInt(Integer::intValue).sum();
            int roll = (int) (Math.random() * totalWeight);
            mobId = null;
            for (Map.Entry<String, Integer> entry : pool.entrySet()) {
                roll -= entry.getValue();
                if (roll < 0) {
                    mobId = entry.getKey();
                    break;
                }
            }
            if (mobId == null) {
                mobId = pool.keySet().iterator().next();
            }
        } else {
            // Fallback: use first mob from room's mob list.
            if (room.getMobsToSpawn().isEmpty()) return;
            mobId = room.getMobsToSpawn().get(0);
        }

        Vector3i pos = obj.getPosition();
        Vector3d spawnPos = new Vector3d(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5);
        try {
            var result = NPCPlugin.get().spawnNPC(store, mobId, null, spawnPos, Rotation3f.ZERO);
            Ref<EntityStore> ref = result != null ? result.first() : null;
            if (ref != null) {
                KweebecScaleHelper.applyScale(store, ref, mobId);
                obj.setSpawnedMob(ref);
                room.addSpawnedMob(ref, false);
            }
        } catch (Exception e) {
            // Failed to spawn — mark as completed to not block progression.
            obj.complete();
        }
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
}
