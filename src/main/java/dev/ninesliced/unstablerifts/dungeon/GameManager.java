package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.party.PartyManager;
import dev.ninesliced.unstablerifts.party.PartyUiPage;
import dev.ninesliced.unstablerifts.player.OnlinePlayers;
import dev.ninesliced.unstablerifts.player.PlayerEventNotifier;
import dev.ninesliced.unstablerifts.systems.DeathComponent;
import dev.ninesliced.unstablerifts.systems.DeathStateController;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static dev.ninesliced.unstablerifts.dungeon.RotationUtil.rotateLocalX;
import static dev.ninesliced.unstablerifts.dungeon.RotationUtil.rotateLocalZ;

/**
 * Orchestrates the dungeon game lifecycle: generation, start, boss, end, cleanup.
 */
public final class GameManager {

    private static final Logger LOGGER = Logger.getLogger(GameManager.class.getName());

    private final UnstableRifts plugin;
    private final PlayerInventoryService inventoryService;
    private final MobSpawningService mobSpawningService;
    private final ReviveMarkerService reviveMarkerService;
    private final PlayerStateService playerStateService;
    /**
     * Active games indexed by party ID.
     */
    private final Map<UUID, Game> activeGames = new ConcurrentHashMap<>();
    /**
     * Reverse lookup: player UUID → party ID for fast game resolution.
     */
    private final Map<UUID, UUID> playerToParty = new ConcurrentHashMap<>();
    /**
     * Players who have already been normalized after landing outside a dungeon world.
     * Prevents repeated inventory restores if multiple world/ready/tick hooks fire.
     */
    private final Set<UUID> outsideDungeonNormalizedPlayers = ConcurrentHashMap.newKeySet();
    /**
     * Players reconnecting with a saved inventory whose recovery must wait
     * until PlayerReady so the engine does not overwrite restored containers.
     */
    private final Set<UUID> pendingReadyRecoveryPlayers = ConcurrentHashMap.newKeySet();

    public GameManager(@Nonnull UnstableRifts plugin) {
        this.plugin = plugin;
        this.inventoryService = new PlayerInventoryService(plugin);
        this.mobSpawningService = new MobSpawningService();
        this.reviveMarkerService = new ReviveMarkerService();
        this.playerStateService = new PlayerStateService();
    }

    // ────────────────────────────────────────────────
    //  Game lifecycle
    // ────────────────────────────────────────────────

    /**
     * Starts a new game for the given party.
     * Creates the instance and begins background generation.
     */
    @Nonnull
    public CompletableFuture<Game> startGame(@Nonnull UUID partyId,
                                             @Nonnull List<UUID> memberIds,
                                             @Nonnull Map<UUID, PlayerRef> memberRefs,
                                             @Nonnull Map<UUID, Ref<EntityStore>> memberEntities,
                                             @Nonnull Map<UUID, Store<EntityStore>> memberStores,
                                             @Nonnull World leaderWorld,
                                             @Nonnull Transform leaderReturnPoint) {
        if (activeGames.containsKey(partyId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("A game is already active for this party."));
        }

        DungeonConfig config = plugin.loadDungeonConfig();
        if (config.getLevels().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No dungeon levels configured."));
        }

        Game game = new Game(partyId);
        activeGames.put(partyId, game);

        for (UUID memberId : memberIds) {
            playerToParty.put(memberId, partyId);
        }

        DungeonConfig.LevelConfig firstLevelConfig = config.getLevels().get(0);
        Level firstLevel = new Level(firstLevelConfig.getName(), 0);
        game.addLevel(firstLevel);
        game.setCurrentLevelSelector(firstLevelConfig.getSelector());

        CompletableFuture<World> worldFuture = plugin.getDungeonInstanceService().spawnGeneratedInstance(
                leaderWorld,
                leaderReturnPoint,
                firstLevelConfig,
                status -> broadcastToParty(partyId, status,
                        status.startsWith("Dungeon ready") ? DungeonConstants.COLOR_SUCCESS : DungeonConstants.COLOR_WARNING)
        );

        game.setGenerationFuture(worldFuture);

        return worldFuture.thenApply(world -> {
            game.setInstanceWorld(world);

            Level generatedLevel = plugin.getDungeonInstanceService().getLastGeneratedLevel();
            if (generatedLevel != null) {
                game.setLevel(0, generatedLevel);
                LOGGER.info("Applied generated level graph '" + generatedLevel.getName()
                        + "' with " + generatedLevel.getRooms().size() + " rooms for party " + partyId);
            } else {
                LOGGER.warning("No generated level graph was available for party " + partyId + "; using placeholder level state.");
            }

            game.setGenerationProgress(1.0f);
            game.setState(GameState.READY);
            plugin.getDungeonMapService().buildMap(game);
            PartyUiPage.refreshOpenPages();
            LOGGER.info("Game ready for party " + partyId + " in world " + world.getName());
            return game;
        });
    }

    /**
     * Called when all party members have loaded into the dungeon world.
     * Saves inventories, resets status, gives equipment, and spawns mobs.
     */
    public void onGameStart(@Nonnull Game game) {
        if (game.getState() != GameState.READY) {
            LOGGER.warning("Attempted to start game in state: " + game.getState());
            return;
        }

        game.setState(GameState.ACTIVE);
        game.setStartTime(System.currentTimeMillis());
        game.setLevelStartTime(System.currentTimeMillis());
        PartyUiPage.refreshOpenPages();

        World world = game.getInstanceWorld();
        if (world == null) {
            LOGGER.severe("Game instance world is null on start!");
            return;
        }

        DungeonConfig config = plugin.loadDungeonConfig();

        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();

            for (Map.Entry<UUID, UUID> entry : playerToParty.entrySet()) {
                if (!entry.getValue().equals(game.getPartyId())) continue;

                UUID playerId = entry.getKey();
                PlayerRef playerRef = Universe.get().getPlayer(playerId);
                if (playerRef == null) continue;

                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || !ref.isValid()) continue;

                Store<EntityStore> playerStore = ref.getStore();
                Player player = playerStore.getComponent(ref, Player.getComponentType());
                if (player == null) continue;

                preparePlayerForDungeon(game, playerId, playerRef, player, ref, playerStore, config);
            }

            Level currentLevel = game.getCurrentLevel();
            if (currentLevel != null) {
                mobSpawningService.spawnLevelMobs(currentLevel, store);
                // Spawn portals in unlocked rooms at level start (cosmetic only).
                for (RoomData room : currentLevel.getRooms()) {
                    if (room.getType() != RoomType.BOSS
                            && !room.isLocked()
                            && !room.getPortalPositions().isEmpty()) {
                        plugin.getPortalService().spawnPortal(room, world);
                    }
                }
            }

            broadcastToParty(game.getPartyId(), "The dungeon awaits! Fight your way through!", DungeonConstants.COLOR_SUCCESS);
        });
    }

    /**
     * Enters boss phase for the current level.
     */
    public void enterBossPhase(@Nonnull Game game) {
        if (game.getState() != GameState.ACTIVE) return;

        game.setState(GameState.BOSS);
        Level level = game.getCurrentLevel();
        if (level == null) return;

        RoomData bossRoom = level.getBossRoom();
        if (bossRoom == null) return;

        World world = game.getInstanceWorld();
        if (world == null) return;

        DungeonConfig config = plugin.loadDungeonConfig();
        DungeonConfig.LevelConfig levelConfig = resolveCurrentLevelConfig(config, game);

        world.execute(() -> {
            // Seal boss room — prefer door positions from prefab, fall back to hardcoded.
            if (!bossRoom.getDoorPositions().isEmpty()) {
                String doorBlock = levelConfig != null ? levelConfig.getDoorBlock() : DungeonConstants.DEFAULT_DOOR_BLOCK;
                plugin.getDoorService().sealRoom(bossRoom, world, doorBlock);
                game.setBossRoomSealed(true);
            } else {
                sealBossRoom(game, bossRoom, world);
            }

            broadcastToParty(game.getPartyId(), "BOSS ROOM! Clear the room to advance!", DungeonConstants.COLOR_DANGER);
        });
    }

    /**
     * Called when the boss room is cleared. Sets TRANSITIONING state and spawns
     * a portal in the boss room for players to walk into.
     */
    public void onBossDefeated(@Nonnull Game game) {
        if (game.getState() != GameState.BOSS && game.getState() != GameState.ACTIVE) return;

        Level level = game.getCurrentLevel();
        RoomData bossRoom = level != null ? level.getBossRoom() : null;
        LOGGER.info("onBossDefeated start: party=" + game.getPartyId()
                + " state=" + game.getState()
                + " levelIndex=" + game.getCurrentLevelIndex()
                + " level=" + (level != null ? level.getName() : "null")
                + " playersInInstance=" + game.getPlayersInInstance().size()
                + " deadPlayers=" + game.getDeadPlayers().size()
                + " bossRoomAnchor=" + (bossRoom != null ? bossRoom.getAnchor() : "null"));
        game.setState(GameState.TRANSITIONING);

        if (level == null) return;

        World world = game.getInstanceWorld();
        if (world == null) return;

        world.execute(() -> {
            // Unseal boss room — use onRoomCleared to handle both door markers and lock door prefab blocks.
            if (bossRoom != null) {
                plugin.getDoorService().onRoomCleared(bossRoom, world);
                game.setBossRoomSealed(false);
            }

            // Revive all dead/ghost players on level completion
            reviveAllDeadPlayers(game);

            // Spawn portal in boss room
            if (bossRoom != null) {
                plugin.getPortalService().spawnPortal(bossRoom, world);
            }
            game.setPortalsActive(true);
            game.setPortalsActivatedAt(System.currentTimeMillis());
            LOGGER.info("Boss portal activated for party " + game.getPartyId()
                    + " level=" + level.getName()
                    + " nextLevel=" + hasNextLevelConfig(game)
                    + " portalPositions=" + (bossRoom != null ? bossRoom.getPortalPositions().size() : 0)
                    + " activatedAt=" + game.getPortalsActivatedAt()
                    + " victoryTimestamp=" + game.getVictoryTimestamp());

            if (hasNextLevelConfig(game)) {
                broadcastToParty(game.getPartyId(), "Boss defeated! Step into the portal to advance!", DungeonConstants.COLOR_SUCCESS);
            } else {
                broadcastToParty(game.getPartyId(), "Dungeon Complete! Step into the portal to return home!", DungeonConstants.COLOR_VICTORY);
                game.setVictoryTimestamp(System.currentTimeMillis());
            }
        });
    }

    /**
     * Checks if there is a next level configured (not yet generated).
     */
    public boolean hasNextLevelConfig(@Nonnull Game game) {
        DungeonConfig config = plugin.loadDungeonConfig();
        return resolveNextLevelConfig(config, game) != null;
    }

    /**
     * Called when a player walks into the boss room portal.
     * Advances to the next level or ends the game.
     */
    public void onPortalEntered(@Nonnull Game game, @Nonnull UUID playerId) {
        if (game.getState() != GameState.TRANSITIONING) return;
        boolean hasNextLevel = hasNextLevelConfig(game);
        LOGGER.info("onPortalEntered: party=" + game.getPartyId()
                + " player=" + playerId
                + " state=" + game.getState()
                + " hasNextLevel=" + hasNextLevel
                + " playersInInstanceBefore=" + game.getPlayersInInstance().size()
                + " victoryTimestamp=" + game.getVictoryTimestamp());

        if (hasNextLevel) {
            game.setPortalsActive(false);
            game.setPortalsActivatedAt(0L);
            advanceToNextLevel(game);
        } else {
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef != null) {
                PlayerEventNotifier.showEventTitle(playerRef, "Returning to the surface...", true);
            }
            onPlayerLeftParty(game.getPartyId(), playerId);
        }
    }

    /**
     * Teleports a player from a Closest Exit portal to the nearest ancestor room
     * that contains one or more portal exit markers.
     */
    public void onClosestExitPortalEntered(@Nonnull Game game,
                                           @Nonnull UUID playerId,
                                           @Nonnull RoomData sourceRoom,
                                           @Nullable RoomData fallbackRoom) {
        Vector3i exitPos = plugin.getPortalService().resolveClosestExitDestination(sourceRoom);
        RoomData resolvedSourceRoom = sourceRoom;
        if (exitPos == null && fallbackRoom != null && fallbackRoom != sourceRoom) {
            exitPos = plugin.getPortalService().resolveClosestExitDestination(fallbackRoom);
            if (exitPos != null) {
                resolvedSourceRoom = fallbackRoom;
            }
        }

        if (exitPos == null) {
            LOGGER.warning("Closest Exit portal used in room " + sourceRoom.getAnchor()
                    + (fallbackRoom != null && fallbackRoom != sourceRoom
                    ? " (fallback owner " + fallbackRoom.getAnchor() + ")"
                    : "")
                    + " but no ancestor room contains portal exit markers.");
            return;
        }

        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (playerRef == null) {
            return;
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        Store<EntityStore> playerStore = ref.getStore();
        Vector3d destination = new Vector3d(exitPos.x + 0.5, exitPos.y + 1.0, exitPos.z + 0.5);
        Teleport tp = Teleport.createForPlayer(destination, new Rotation3f());
        playerStore.putComponent(ref, Teleport.getComponentType(), tp);

        LOGGER.info("Closest Exit portal teleported player " + playerId
                + " from room " + resolvedSourceRoom.getAnchor()
                + " to exit " + exitPos);
    }

    /**
     * Generates the next level, teleports all players to its entrance, and starts it.
     */
    private void advanceToNextLevel(@Nonnull Game game) {
        World world = game.getInstanceWorld();
        if (world == null) return;

        DungeonConfig config = plugin.loadDungeonConfig();
        DungeonConfig.LevelConfig nextLevelConfig = resolveNextLevelConfig(config, game);
        if (nextLevelConfig == null) {
            LOGGER.warning("advanceToNextLevel called for party " + game.getPartyId()
                    + " but no nextLevel is configured for current selector " + game.getCurrentLevelSelector());
            return;
        }

        int nextIndex = game.getCurrentLevelIndex() + 1;
        Level nextLevel = new Level(nextLevelConfig.getName(), nextIndex);
        game.addLevel(nextLevel);
        game.setCurrentLevelIndex(nextIndex);
        game.setCurrentLevelSelector(nextLevelConfig.getSelector());

        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            mobSpawningService.spawnLevelMobs(nextLevel, store);

            // Spawn portals for unlocked rooms in the new level.
            for (RoomData room : nextLevel.getRooms()) {
                if (room.getType() != RoomType.BOSS
                        && !room.isLocked()
                        && !room.getPortalPositions().isEmpty()) {
                    plugin.getPortalService().spawnPortal(room, world);
                }
            }

            // Teleport all players to the entrance of the new level.
            RoomData entrance = nextLevel.getEntranceRoom();
            if (entrance != null) {
                Vector3i anchor = entrance.getAnchor();
                Vector3d spawnPos = new Vector3d(anchor.x + 0.5, anchor.y + 1.0, anchor.z + 0.5);
                teleportAllPlayersToPosition(game, world, store, spawnPos);
            }

            game.setState(GameState.ACTIVE);
            broadcastToParty(game.getPartyId(), "Welcome to " + nextLevelConfig.getName() + "!", DungeonConstants.COLOR_SUCCESS);
        });
    }

    /**
     * Teleports all players currently in the instance to the given position (same world).
     */
    private void teleportAllPlayersToPosition(@Nonnull Game game, @Nonnull World world,
                                              @Nonnull Store<EntityStore> store,
                                              @Nonnull Vector3d position) {
        for (UUID playerId : game.getPlayersInInstance()) {
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null) continue;

            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) continue;

            Store<EntityStore> playerStore = ref.getStore();
            Teleport tp = Teleport.createForPlayer(position, new Rotation3f());
            playerStore.putComponent(ref, Teleport.getComponentType(), tp);
        }
    }

    /**
     * Ends the game, restores inventories, and cleans up.
     *
     * @param forced true if the game was aborted (e.g. party disband)
     */
    public void endGame(@Nonnull Game game, boolean forced) {
        if (game.getState() == GameState.COMPLETE) {
            return;
        }

        // Cancel ongoing generation if the game is still generating
        if (game.getState() == GameState.GENERATING) {
            CompletableFuture<World> genFuture = game.getGenerationFuture();
            if (genFuture != null && !genFuture.isDone()) {
                genFuture.cancel(true);
                LOGGER.info("Cancelled generation future for party " + game.getPartyId());
            }
        }

        game.setState(GameState.COMPLETE);
        game.clearDeadPlayers();
        showAllPartyMembers(game.getPartyId());

        List<UUID> partyMembers = playerToParty.entrySet().stream()
                .filter(entry -> entry.getValue().equals(game.getPartyId()))
                .map(Map.Entry::getKey)
                .toList();

        for (UUID playerId : partyMembers) {
            reviveMarkerService.despawnReviveMarker(playerId);
            inventoryService.removeDeathSnapshot(playerId);

            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null) continue;

            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) continue;

            Store<EntityStore> store = ref.getStore();
            Transform returnPoint = game.getReturnPoints().get(playerId);
            World trackedReturnWorld = game.getReturnWorlds().get(playerId);
            boolean wasInInstance = game.isPlayerInInstance(playerId);

            plugin.getCameraService().restoreDefault(playerRef);

            store.getExternalData().getWorld().execute(() -> {
                if (!ref.isValid()) {
                    return;
                }

                // Reset death state inside world.execute() so we're on the
                // correct thread for store access. ReviveTickSystem also runs
                // on this thread, so the reset still happens before the next tick.
                DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
                if (deathComponent != null) {
                    deathComponent.reset();
                }
                DeathStateController.clear(store, ref);

                Player player = store.getComponent(ref, Player.getComponentType());
                if (player == null) {
                    plugin.getInventoryLockService().remove(playerId);
                    game.setPlayerInInstance(playerId, false);
                    return;
                }

                playerStateService.hideDungeonHuds(player, playerRef);

                plugin.getInventoryLockService().unlock(player, playerId);
                if (wasInInstance) {
                    inventoryService.restorePlayerInventory(playerId, player, true);
                } else if (inventoryService.shouldPreserveSavedInventoryDuringCleanup(playerId, player, game)) {
                    LOGGER.info("Preserving saved inventory for disconnected player " + playerId
                            + " during endGame cleanup.");
                } else {
                    LOGGER.info("Deleting saved inventory for player " + playerId
                            + " during endGame cleanup.");
                    inventoryService.deleteSavedInventoryFiles(playerId);
                }

                playerStateService.resetPlayerStatus(player, ref, store);
                game.setPlayerInInstance(playerId, false);

                if (wasInInstance && returnPoint != null) {
                    try {
                        World returnWorld = trackedReturnWorld;
                        if (returnWorld == null) {
                            returnWorld = Universe.get().getDefaultWorld();
                        }
                        if (returnWorld != null) {
                            teleportPlayerToReturnPoint(ref, store, returnWorld, returnPoint);
                        }
                    } catch (Exception e) {
                        LOGGER.log(java.util.logging.Level.WARNING, "Failed to teleport player " + playerId + " back", e);
                    }
                }
            });
        }

        plugin.getDungeonMapService().cleanup(game.getPartyId());
        activeGames.remove(game.getPartyId());

        if (forced) {
            broadcastToParty(game.getPartyId(), "The dungeon run was cancelled.", DungeonConstants.COLOR_SOFT_DANGER);
        }

        World instanceWorld = game.getInstanceWorld();
        if (instanceWorld != null) {
            instanceWorld.execute(() -> InstancesPlugin.safeRemoveInstance(instanceWorld));
        }

        playerToParty.entrySet().removeIf(e -> e.getValue().equals(game.getPartyId()));
        plugin.getPartyManager().closePartyForSystem(game.getPartyId(), "The dungeon run ended, so the party was closed.");
        PartyUiPage.refreshOpenPages();

        // Remove all mob registry entries belonging to this game's rooms.
        java.util.Set<RoomData> gameRooms = new java.util.HashSet<>();
        for (Level level : game.getLevels()) {
            gameRooms.addAll(level.getRooms());
        }
        mobSpawningService.removeRegistryEntriesForRooms(gameRooms);

        LOGGER.info("Game ended for party " + game.getPartyId() + " (forced=" + forced + ")");
    }

    /**
     * Handle party disband — end the active game immediately.
     */
    public void onPartyDisband(@Nonnull UUID partyId) {
        Game game = activeGames.get(partyId);
        if (game == null) return;

        if (game.getState() != GameState.COMPLETE) {
            endGame(game, true);
        }
    }

    public void onPlayerLeftParty(@Nonnull UUID partyId, @Nonnull UUID playerId) {
        Game game = activeGames.get(partyId);
        playerToParty.remove(playerId, partyId);
        reviveMarkerService.despawnReviveMarker(playerId);

        if (game == null) {
            return;
        }

        game.removeDeadPlayer(playerId);
        boolean wasInInstance = game.isPlayerInInstance(playerId);
        game.setPlayerInInstance(playerId, false);
        LOGGER.info("onPlayerLeftParty: party=" + partyId
                + " player=" + playerId
                + " state=" + game.getState()
                + " wasInInstance=" + wasInInstance
                + " remainingPlayersInInstance=" + game.getPlayersInInstance().size()
                + " victoryTimestamp=" + game.getVictoryTimestamp());

        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (playerRef != null) {
            plugin.getCameraService().restoreDefault(playerRef);

            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                Store<EntityStore> store = ref.getStore();
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) {
                    Transform returnPoint = game.getReturnPoints().get(playerId);
                    World trackedReturnWorld = game.getReturnWorlds().get(playerId);
                    LOGGER.info("Preparing leave-party cleanup for player " + playerId
                            + " currentWorld=" + (player.getWorld() != null ? player.getWorld().getName() : "null")
                            + " returnWorld=" + (trackedReturnWorld != null ? trackedReturnWorld.getName() : "null")
                            + " hasReturnPoint=" + (returnPoint != null));

                    playerStateService.hideDungeonHuds(player, playerRef);

                    store.getExternalData().getWorld().execute(() -> {
                        if (!ref.isValid()) {
                            return;
                        }

                        DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
                        if (deathComponent != null) {
                            deathComponent.reset();
                        }

                        DeathStateController.clear(store, ref);
                        plugin.getInventoryLockService().unlock(player, playerId);
                        if (wasInInstance) {
                            inventoryService.restorePlayerInventory(playerId, player, true);
                        } else if (inventoryService.shouldPreserveSavedInventoryDuringCleanup(playerId, player, game)) {
                            LOGGER.info("Preserving saved inventory for disconnected player " + playerId
                                    + " during party-leave cleanup.");
                        } else {
                            LOGGER.info("Deleting saved inventory for player " + playerId
                                    + " during party-leave cleanup.");
                            inventoryService.deleteSavedInventoryFiles(playerId);
                        }
                        playerStateService.resetPlayerStatus(player, ref, store);

                        if (wasInInstance && returnPoint != null) {
                            try {
                                World returnWorld = trackedReturnWorld;
                                if (returnWorld == null) {
                                    returnWorld = Universe.get().getDefaultWorld();
                                }
                                if (returnWorld != null && ref.isValid()) {
                                    teleportPlayerToReturnPoint(ref, store, returnWorld, returnPoint);
                                }
                            } catch (Exception e) {
                                LOGGER.log(java.util.logging.Level.WARNING, "Failed to teleport player " + playerId + " out of dungeon after leaving party", e);
                            }
                        }
                    });
                }
            }
        }

        game.getReturnPoints().remove(playerId);
        game.getReturnWorlds().remove(playerId);
        game.getSavedInventoryPaths().remove(playerId);
        closeGameIfInstanceEmpty(game);
    }

    /**
     * Handle player disconnect during an active game.
     */
    public void onPlayerDisconnect(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        pendingReadyRecoveryPlayers.remove(playerId);
        inventoryService.removeDeathSnapshot(playerId);
        plugin.getCameraService().restoreDefault(playerRef);
        plugin.getInventoryLockService().remove(playerId);
        reviveMarkerService.despawnReviveMarker(playerId);

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref != null && ref.isValid()) {
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            world.execute(() -> {
                if (!ref.isValid()) {
                    return;
                }

                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null && !player.wasRemoved()) {
                    playerStateService.hideDungeonHuds(player, playerRef);
                }
            });
        }

        UUID partyId = playerToParty.get(playerId);
        if (partyId == null) return;

        showPlayerToParty(playerId, partyId);

        Game game = activeGames.get(partyId);
        if (game == null) return;

        game.setPlayerInInstance(playerId, false);
        game.removeDeadPlayer(playerId);
        if (!closeGameIfInstanceEmpty(game)) {
            PartyUiPage.refreshOpenPages();
        }

        LOGGER.info("Player " + playerId + " disconnected during dungeon run. Inventory saved on disk.");
    }

    @Nonnull
    public PartyManager.ActionResult teleportPlayerToDungeon(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        Game game = findGameForPlayer(playerId);
        if (game == null || !isDungeonJoinable(game)) {
            return PartyManager.ActionResult.error("Your party does not have an active dungeon instance to return to.");
        }

        World instanceWorld = game.getInstanceWorld();
        if (instanceWorld == null) {
            return PartyManager.ActionResult.error("The dungeon instance is no longer available.");
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return PartyManager.ActionResult.error("You must be fully loaded before teleporting to the dungeon.");
        }

        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return PartyManager.ActionResult.error("You must be fully loaded before teleporting to the dungeon.");
        }

        if (player.getWorld() == instanceWorld) {
            game.setPlayerInInstance(playerId, true);
            return PartyManager.ActionResult.error("You are already inside the dungeon.");
        }

        if (!inventoryService.hasSavedInventoryFile(playerId)) {
            return PartyManager.ActionResult.error("You cannot return to this dungeon because your saved inventory could not be found.");
        }

        plugin.getCameraService().scheduleEnableOnNextReady(playerRef);
        sendPlayerToDungeon(
                game,
                playerRef,
                ref,
                store,
                CompletableFuture.completedFuture(instanceWorld),
                status -> {
                    plugin.getCameraService().cancelDeferredEnable(playerRef);
                    PlayerEventNotifier.showEventTitle(playerRef, status, true);
                    PartyUiPage.refreshOpenPages();
                }
        );
        PartyUiPage.refreshOpenPages();
        return PartyManager.ActionResult.success("Teleporting you back into the dungeon.");
    }

    /**
     * On player connect, check if they have a saved inventory from a crash/disconnect.
     */
    public void onPlayerConnect(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        boolean hasFileSnapshot = inventoryService.hasSavedInventoryFile(playerId);

        if (hasFileSnapshot) {
            pendingReadyRecoveryPlayers.add(playerId);
            LOGGER.info("Found saved inventory for " + playerRef.getUsername()
                    + "; recovery will run once the player is fully loaded."
                    + " fileSnapshot=" + hasFileSnapshot);
        } else {
            pendingReadyRecoveryPlayers.remove(playerId);
        }
    }

    public void releasePendingRecovery(@Nonnull UUID playerId) {
        pendingReadyRecoveryPlayers.remove(playerId);
    }

    public void onPlayerAddedToWorld(@Nonnull PlayerRef playerRef, @Nonnull Player player, @Nonnull World world) {
        UUID playerId = playerRef.getUuid();
        Game game = findGameForPlayer(playerId);
        boolean hasSavedInventoryFile = inventoryService.hasSavedInventoryFile(playerId);
        boolean hasSavedInventory = hasSavedInventoryFile;
        boolean inActiveDungeonWorld = game != null
                && game.getState() != GameState.COMPLETE
                && world == game.getInstanceWorld();
        LOGGER.info("onPlayerAddedToWorld: player=" + playerId
                + " world=" + world.getName()
                + " gameState=" + (game != null ? game.getState() : null)
                + " inActiveDungeonWorld=" + inActiveDungeonWorld
                + " isPlayerInInstance=" + (game != null && game.isPlayerInInstance(playerId))
                + " hasSavedInventory=" + hasSavedInventory
                + " fileSnapshot=" + hasSavedInventoryFile);

        if (!inActiveDungeonWorld) {
            normalizeOutsideDungeonState(playerRef, player, game);
            return;
        }

        outsideDungeonNormalizedPlayers.remove(playerId);

        if (game.isPlayerInInstance(playerId) || !hasSavedInventory) {
            return;
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        DungeonConfig config = plugin.loadDungeonConfig();
        preparePlayerForDungeon(game, playerId, playerRef, player, ref, store, config);
        PartyUiPage.refreshOpenPages();
    }

    public void onPlayerRemovedFromWorld(@Nonnull PlayerRef playerRef, @Nonnull Player player, @Nonnull World world) {
        Game game = findGameForPlayer(playerRef.getUuid());
        LOGGER.info("onPlayerRemovedFromWorld: player=" + playerRef.getUuid()
                + " world=" + world.getName()
                + " gameState=" + (game != null ? game.getState() : null)
                + " isPlayerInInstance=" + (game != null && game.isPlayerInInstance(playerRef.getUuid()))
                + " playerWorld=" + (player.getWorld() != null ? player.getWorld().getName() : "null"));
        if (game == null || game.getState() == GameState.COMPLETE || world != game.getInstanceWorld()) {
            return;
        }
        if (!game.isPlayerInInstance(playerRef.getUuid())) {
            return;
        }

        if (game.isPlayerDead(playerRef.getUuid())) {
            LOGGER.info("Ignoring dungeon removal cleanup for ghosted/dead player " + playerRef.getUuid()
                    + " because they remain part of the active run.");
            return;
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref != null && ref.isValid()) {
            Store<EntityStore> store = ref.getStore();
            DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
            if (deathComponent != null && deathComponent.isDead()) {
                LOGGER.info("Ignoring dungeon removal cleanup for dead player " + playerRef.getUuid()
                        + " because they remain part of the active run.");
                return;
            }
        }

        reviveMarkerService.despawnReviveMarker(playerRef.getUuid());

        plugin.getCameraService().restoreDefault(playerRef);
        playerStateService.hideDungeonHuds(player, playerRef);

        plugin.getInventoryLockService().unlock(player, playerRef.getUuid());
        inventoryService.restorePlayerInventory(playerRef.getUuid(), player, false);

        ref = playerRef.getReference();
        if (ref != null && ref.isValid()) {
            Store<EntityStore> store = ref.getStore();
            playerStateService.resetPlayerStatus(player, ref, store);
        }

        game.setPlayerInInstance(playerRef.getUuid(), false);
        game.removeDeadPlayer(playerRef.getUuid());
        if (!closeGameIfInstanceEmpty(game)) {
            PartyUiPage.refreshOpenPages();
        }
    }

    public boolean normalizeOutsideDungeonState(@Nonnull PlayerRef playerRef,
                                                @Nonnull Player player,
                                                @Nullable Game game) {
        return normalizeOutsideDungeonState(playerRef, player, game, null);
    }

    public boolean normalizeOutsideDungeonState(@Nonnull PlayerRef playerRef,
                                                @Nonnull Player player,
                                                @Nullable Game game,
                                                @Nullable CommandBuffer<EntityStore> commandBuffer) {
        UUID playerId = playerRef.getUuid();
        Ref<EntityStore> ref = playerRef.getReference();
        Store<EntityStore> store = ref != null && ref.isValid() ? ref.getStore() : null;
        boolean inventoryLocked = plugin.getInventoryLockService().isLocked(playerId);
        boolean hasSavedInventory = inventoryService.hasSavedInventoryFile(playerId);
        boolean shouldNormalize = inventoryLocked || hasSavedInventory;
        boolean deadComponentDead = false;
        boolean gamePlayerInInstance = game != null && game.isPlayerInInstance(playerId);
        boolean gamePlayerDead = game != null && game.isPlayerDead(playerId);

        if (store != null && ref != null) {
            DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
            if (deathComponent != null && deathComponent.isDead()) {
                deadComponentDead = true;
                shouldNormalize = true;
            }
        }

        if (game != null) {
            shouldNormalize |= gamePlayerInInstance || gamePlayerDead;
        }

        if (!shouldNormalize) {
            outsideDungeonNormalizedPlayers.remove(playerId);
            return false;
        }

        if (pendingReadyRecoveryPlayers.contains(playerId)) {
            LOGGER.info("Deferring outside-dungeon normalization until PlayerReady for " + playerId);
            return false;
        }

        if (!outsideDungeonNormalizedPlayers.add(playerId)) {
            if (game != null) {
                game.removeDeadPlayer(playerId);
                game.setPlayerInInstance(playerId, false);
            }
            return false;
        }

        LOGGER.info("normalizeOutsideDungeonState: player=" + playerId
                + " username=" + playerRef.getUsername()
                + " currentWorld=" + (player.getWorld() != null ? player.getWorld().getName() : "null")
                + " gameState=" + (game != null ? game.getState() : null)
                + " instanceWorld=" + (game != null && game.getInstanceWorld() != null ? game.getInstanceWorld().getName() : "null")
                + " inventoryLocked=" + inventoryLocked
                + " hasSavedInventory=" + hasSavedInventory
                + " deathComponentDead=" + deadComponentDead
                + " gamePlayerInInstance=" + gamePlayerInInstance
                + " gamePlayerDead=" + gamePlayerDead
                + " deleteAfterRestore=" + (game == null || game.getState() == GameState.COMPLETE));

        plugin.getCameraService().restoreDefault(playerRef);
        playerStateService.hideDungeonHuds(player, playerRef);
        plugin.getInventoryLockService().unlock(player, playerId);

        if (hasSavedInventory) {
            boolean deleteAfterRestore = game == null || game.getState() == GameState.COMPLETE;
            inventoryService.restorePlayerInventory(playerId, player, deleteAfterRestore);
        }

        if (ref != null && ref.isValid() && store != null) {
            playerStateService.resetPlayerStatus(player, ref, store, commandBuffer);
        }

        if (game != null) {
            game.removeDeadPlayer(playerId);
            game.setPlayerInInstance(playerId, false);
        }

        LOGGER.info("Normalized player state for " + playerRef.getUsername()
                + " upon arriving in non-dungeon world.");
        return true;
    }

    public void sendPlayerToDungeon(@Nonnull Game game,
                                    @Nonnull PlayerRef playerRef,
                                    @Nonnull Ref<EntityStore> ref,
                                    @Nonnull Store<EntityStore> store,
                                    @Nonnull CompletableFuture<World> readyFuture,
                                    @Nullable Consumer<String> failureConsumer) {
        UUID playerId = playerRef.getUuid();
        World currentWorld = store.getExternalData().getWorld();

        readyFuture.whenComplete((targetWorld, throwable) -> {
            if (throwable != null) {
                LOGGER.log(java.util.logging.Level.WARNING, "Failed to prepare dungeon instance", throwable);
                if (failureConsumer != null) {
                    failureConsumer.accept("Dungeon creation failed.");
                }
                return;
            }

            currentWorld.execute(() -> {
                if (!ref.isValid()) {
                    return;
                }

                Player currentPlayer = store.getComponent(ref, Player.getComponentType());
                if (currentPlayer == null) {
                    return;
                }

                Transform returnPoint = DungeonInstanceService.captureReturnPoint(store, ref);
                game.getReturnPoints().put(playerId, returnPoint);
                game.getReturnWorlds().put(playerId, currentWorld);

                try {
                    InstancesPlugin.teleportPlayerToInstance(ref, store, targetWorld, returnPoint);
                } catch (Exception exception) {
                    LOGGER.log(java.util.logging.Level.WARNING, "Failed to send player to ready dungeon instance", exception);
                    if (failureConsumer != null) {
                        failureConsumer.accept("Teleport to dungeon failed.");
                    }
                }
            });
        });
    }

    private void teleportPlayerToReturnPoint(@Nonnull Ref<EntityStore> ref,
                                             @Nonnull Store<EntityStore> store,
                                             @Nonnull World returnWorld,
                                             @Nonnull Transform returnPoint) {
        Teleport teleport = Teleport.createForPlayer(returnWorld, returnPoint);
        store.putComponent(ref, Teleport.getComponentType(), teleport);
    }

    public void onInstanceWorldRemoved(@Nonnull World world) {
        List<Game> removedGames = activeGames.values().stream()
                .filter(game -> game.getInstanceWorld() == world)
                .toList();

        for (Game game : removedGames) {
            LOGGER.warning("Dungeon instance world was removed for party " + game.getPartyId() + ". Ending the run and closing the party.");
            game.setInstanceWorld(null);
            // endGame accesses player stores that may live on a different world
            // thread. Defer cleanup so it runs without cross-thread store access.
            try {
                endGame(game, true);
            } catch (Exception e) {
                LOGGER.severe("Failed to end game for party " + game.getPartyId() + " during world removal: " + e.getMessage());
                // Still clean up the game mapping so it doesn't leak.
                activeGames.remove(game.getPartyId());
            }
        }
    }

    @Nonnull
    public List<Ref<EntityStore>> getDeadPlayerRefsInStore(@Nonnull Store<EntityStore> store) {
        List<Ref<EntityStore>> deadPlayerRefs = new ArrayList<>();

        for (Game game : activeGames.values()) {
            if (game.getState() != GameState.ACTIVE && game.getState() != GameState.BOSS) {
                continue;
            }

            for (UUID deadPlayerId : new ArrayList<>(game.getDeadPlayers())) {
                PlayerRef playerRef = Universe.get().getPlayer(deadPlayerId);
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }

                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || !ref.isValid() || ref.getStore() != store) {
                    continue;
                }

                DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
                if (deathComponent == null || !deathComponent.isDead()) {
                    continue;
                }

                deadPlayerRefs.add(ref);
            }
        }

        return deadPlayerRefs;
    }

    // ────────────────────────────────────────────────
    //  Inventory save / restore
    // ────────────────────────────────────────────────

    private void preparePlayerForDungeon(@Nonnull Game game,
                                         @Nonnull UUID playerId,
                                         @Nonnull PlayerRef playerRef,
                                         @Nonnull Player player,
                                         @Nonnull Ref<EntityStore> ref,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull DungeonConfig config) {
        outsideDungeonNormalizedPlayers.remove(playerId);
        if (!inventoryService.hasSavedInventoryFile(playerId)) {
            inventoryService.savePlayerInventory(playerId, player, game);
        }
        plugin.getInventoryLockService().unlock(player, playerId);
        inventoryService.clearPlayerInventory(player);
        playerStateService.resetPlayerStatus(player, ref, store);
        inventoryService.giveStartEquipment(playerRef, player, config);
        plugin.getInventoryLockService().lock(player, playerId);
        plugin.getCameraService().setEnabled(playerRef, true);
        playerStateService.applyDungeonMovementSettings(ref, store, playerRef);
        playerStateService.enableMap(playerRef);
        plugin.getDungeonMapService().sendMapToPlayer(playerRef, game);
        game.setPlayerInInstance(playerId, true);
    }

    // ────────────────────────────────────────────────
    //  Boss room sealing
    // ────────────────────────────────────────────────

    private void sealBossRoom(@Nonnull Game game, @Nonnull RoomData bossRoom, @Nonnull World world) {
        if (game.isBossRoomSealed()) return;

        Vector3i anchor = bossRoom.getAnchor();
        int rotation = bossRoom.getRotation();
        DungeonConfig config = plugin.loadDungeonConfig();
        String wallBlock = config.getBossWallBlock();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                try {
                    int wx = anchor.x + rotateLocalX(dx, -1, rotation);
                    int wz = anchor.z + rotateLocalZ(dx, -1, rotation);
                    world.setBlock(wx, anchor.y + dy, wz, wallBlock, 0);
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.FINE, "Failed to seal block at offset " + dx + "," + dy, e);
                }
            }
        }

        game.setBossRoomSealed(true);
        LOGGER.info("Boss room sealed at " + anchor);
    }

    private void unsealBossRoom(@Nonnull Game game, @Nullable RoomData bossRoom, @Nonnull World world) {
        if (!game.isBossRoomSealed() || bossRoom == null) return;

        Vector3i anchor = bossRoom.getAnchor();
        int rotation = bossRoom.getRotation();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                try {
                    int wx = anchor.x + rotateLocalX(dx, -1, rotation);
                    int wz = anchor.z + rotateLocalZ(dx, -1, rotation);
                    world.setBlock(wx, anchor.y + dy, wz, DungeonConstants.EMPTY_BLOCK, 0);
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.FINE, "Failed to unseal block at offset " + dx + "," + dy, e);
                }
            }
        }

        game.setBossRoomSealed(false);
        LOGGER.info("Boss room unsealed at " + anchor);
    }

    // ────────────────────────────────────────────────
    //  Lookups
    // ────────────────────────────────────────────────

    @Nullable
    public Game findGameForPlayer(@Nonnull UUID playerId) {
        UUID partyId = playerToParty.get(playerId);
        return partyId != null ? activeGames.get(partyId) : null;
    }

    @Nullable
    public Game findGameForParty(@Nonnull UUID partyId) {
        return activeGames.get(partyId);
    }

    @Nullable
    public Game findGameForWorld(@Nonnull World world) {
        for (Game game : activeGames.values()) {
            if (game.getInstanceWorld() == world && game.getState() != GameState.COMPLETE) {
                return game;
            }
        }
        return null;
    }

    public boolean hasActiveGame(@Nonnull UUID partyId) {
        return activeGames.containsKey(partyId);
    }

    @Nonnull
    public Map<UUID, Game> getActiveGames() {
        return activeGames;
    }

    // ────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────

    @Nullable
    private DungeonConfig.LevelConfig resolveLevelConfig(@Nonnull DungeonConfig config, int levelIndex) {
        List<DungeonConfig.LevelConfig> levels = config.getLevels();
        return levelIndex >= 0 && levelIndex < levels.size() ? levels.get(levelIndex) : null;
    }

    @Nullable
    private DungeonConfig.LevelConfig resolveCurrentLevelConfig(@Nonnull DungeonConfig config, @Nonnull Game game) {
        String selector = game.getCurrentLevelSelector();
        if (selector != null && !selector.isBlank()) {
            DungeonConfig.LevelConfig levelConfig = config.findLevel(selector);
            if (levelConfig != null) {
                return levelConfig;
            }
            LOGGER.warning("Could not resolve current level config for selector '" + selector
                    + "' in party " + game.getPartyId() + ". Falling back to index " + game.getCurrentLevelIndex());
        }
        return resolveLevelConfig(config, game.getCurrentLevelIndex());
    }

    @Nullable
    private DungeonConfig.LevelConfig resolveNextLevelConfig(@Nonnull DungeonConfig config, @Nonnull Game game) {
        DungeonConfig.LevelConfig currentLevelConfig = resolveCurrentLevelConfig(config, game);
        if (currentLevelConfig == null) {
            return null;
        }
        String nextLevelSelector = currentLevelConfig.getNextLevel();
        if (nextLevelSelector == null) {
            return null;
        }
        DungeonConfig.LevelConfig nextLevelConfig = config.findLevel(nextLevelSelector);
        if (nextLevelConfig == null) {
            LOGGER.warning("Level '" + currentLevelConfig.getSelector()
                    + "' points to unknown nextLevel '" + nextLevelSelector + "'.");
        }
        return nextLevelConfig;
    }

    private boolean isDungeonJoinable(@Nonnull Game game) {
        return switch (game.getState()) {
            case READY, ACTIVE, BOSS, TRANSITIONING -> game.getInstanceWorld() != null;
            default -> false;
        };
    }

    private boolean closeGameIfInstanceEmpty(@Nonnull Game game) {
        if (!isDungeonJoinable(game) || !game.getPlayersInInstance().isEmpty()) {
            return false;
        }

        if (game.getVictoryTimestamp() > 0 && !hasNextLevelConfig(game)) {
            LOGGER.info("All players exited completed dungeon instance for party " + game.getPartyId() + ". Cleaning up finished run.");
            cleanupCompletedRun(game);
            return true;
        }

        LOGGER.info("No players remain inside dungeon instance for party " + game.getPartyId() + ". Ending run.");
        endGame(game, true);
        return true;
    }

    private void cleanupCompletedRun(@Nonnull Game game) {
        if (game.getState() != GameState.COMPLETE) {
            game.setState(GameState.COMPLETE);
        }
        game.clearDeadPlayers();

        plugin.getDungeonMapService().cleanup(game.getPartyId());
        activeGames.remove(game.getPartyId());

        World instanceWorld = game.getInstanceWorld();
        if (instanceWorld != null) {
            instanceWorld.execute(() -> InstancesPlugin.safeRemoveInstance(instanceWorld));
        }

        playerToParty.entrySet().removeIf(e -> e.getValue().equals(game.getPartyId()));
        plugin.getPartyManager().closePartyForSystem(game.getPartyId(), "The dungeon run ended, so the party was closed.");
        PartyUiPage.refreshOpenPages();

        LOGGER.info("Completed game cleaned up for party " + game.getPartyId());
    }

    public void broadcastToParty(@Nonnull UUID partyId, @Nonnull String text, @Nonnull String color) {
        for (Map.Entry<UUID, UUID> entry : playerToParty.entrySet()) {
            if (entry.getValue().equals(partyId)) {
                PlayerRef playerRef = Universe.get().getPlayer(entry.getKey());
                if (playerRef != null) {
                    PlayerEventNotifier.showEventTitle(playerRef, text, true);
                }
            }
        }
    }

    /**
     * Shutdown: clean up all active games, restore inventories.
     */
    public void shutdown() {
        inventoryService.clearDeathSnapshots();
        for (Game game : activeGames.values()) {
            showAllPartyMembers(game.getPartyId());
        }

        for (PlayerRef playerRef : OnlinePlayers.snapshot()) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }

            UUID playerId = playerRef.getUuid();
            Game game = findGameForPlayer(playerId);
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                continue;
            }

            Store<EntityStore> store = ref.getStore();
            store.getExternalData().getWorld().execute(() -> {
                if (!ref.isValid()) {
                    return;
                }

                Player player = store.getComponent(ref, Player.getComponentType());
                if (player == null) {
                    return;
                }

                boolean hasSavedInventory = inventoryService.hasSavedInventoryFile(playerId);
                boolean inventoryLocked = plugin.getInventoryLockService().isLocked(playerId);
                DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
                boolean customDead = deathComponent != null && deathComponent.isDead();

                if (game == null && !hasSavedInventory && !inventoryLocked && !customDead) {
                    return;
                }

                reviveMarkerService.despawnReviveMarker(playerId);
                plugin.getCameraService().restoreDefault(playerRef);
                playerStateService.hideDungeonHuds(player, playerRef);
                plugin.getInventoryLockService().unlock(player, playerId);

                if (hasSavedInventory) {
                    inventoryService.restorePlayerInventory(playerId, player, false);
                }

                playerStateService.resetPlayerStatus(player, ref, store);

                if (game != null) {
                    game.removeDeadPlayer(playerId);
                    game.setPlayerInInstance(playerId, false);
                }
            });
        }

        for (Game game : new ArrayList<>(activeGames.values())) {
            if (game.getState() != GameState.COMPLETE) {
                LOGGER.info("Shutting down active game for party " + game.getPartyId());
                game.setState(GameState.COMPLETE);
            }
        }
        activeGames.clear();
        playerToParty.clear();
        reviveMarkerService.clear();
        outsideDungeonNormalizedPlayers.clear();
        pendingReadyRecoveryPlayers.clear();
    }

    // ────────────────────────────────────────────────
    //  Death / Revive
    // ────────────────────────────────────────────────

    /**
     * Called when all players in the dungeon instance are dead or ghosts.
     * Ends the run and closes the party.
     */
    public void onAllPlayersDead(@Nonnull Game game) {
        if (game.getState() == GameState.COMPLETE) return;

        broadcastToParty(game.getPartyId(),
                "All players have fallen! The dungeon run is over.", DungeonConstants.COLOR_DANGER);
        endGame(game, true);
    }

    /**
     * Revives all dead/ghost players in the given game, restoring health and equipment.
     */
    public void reviveAllDeadPlayers(@Nonnull Game game) {
        Set<UUID> dead = new java.util.HashSet<>(game.getDeadPlayers());
        if (dead.isEmpty()) return;

        DungeonConfig config = plugin.loadDungeonConfig();

        for (UUID playerId : dead) {
            reviveMarkerService.despawnReviveMarker(playerId);
            showPlayerToParty(playerId, game.getPartyId());

            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null || !playerRef.isValid()) continue;

            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) continue;

            Store<EntityStore> store = ref.getStore();
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) continue;

            // Reset DeathComponent
            DeathComponent deathComp = store.getComponent(ref, DeathComponent.getComponentType());
            if (deathComp != null) {
                deathComp.revive();
            }
            DeathStateController.clear(store, ref);

            // Restore health/stamina
            playerStateService.resetPlayerStatus(player, ref, store);

            // Discard the death snapshot — level-change revive gives fresh start equipment
            inventoryService.discardDeathInventory(playerId);

            // Give back start equipment
            plugin.getInventoryLockService().unlock(player, playerId);
            inventoryService.clearPlayerInventory(player);
            inventoryService.giveStartEquipment(playerRef, player, config);
            plugin.getInventoryLockService().lock(player, playerId);

            // Re-apply dungeon movement and camera
            playerStateService.applyDungeonMovementSettings(ref, store, playerRef);
            plugin.getCameraService().setEnabled(playerRef, true);
        }

        game.clearDeadPlayers();
        broadcastToParty(game.getPartyId(),
                "All fallen players have been revived!", DungeonConstants.COLOR_SUCCESS);
    }

    /**
     * Hides a dead player's entity from all other party members so they cannot
     * see the ghost moving around.
     */
    public void hideDeadPlayerFromParty(@Nonnull UUID deadPlayerId, @Nonnull UUID partyId) {
        for (Map.Entry<UUID, UUID> entry : playerToParty.entrySet()) {
            if (!entry.getValue().equals(partyId)) continue;
            UUID otherId = entry.getKey();
            if (otherId.equals(deadPlayerId)) continue;

            PlayerRef otherRef = Universe.get().getPlayer(otherId);
            if (otherRef != null && otherRef.isValid()) {
                otherRef.getHiddenPlayersManager().hidePlayer(deadPlayerId);
            }
        }
    }

    /**
     * Shows a previously-hidden player to all other party members (e.g. after revive).
     */
    public void showPlayerToParty(@Nonnull UUID playerId, @Nonnull UUID partyId) {
        for (Map.Entry<UUID, UUID> entry : playerToParty.entrySet()) {
            if (!entry.getValue().equals(partyId)) continue;
            UUID otherId = entry.getKey();
            if (otherId.equals(playerId)) continue;

            PlayerRef otherRef = Universe.get().getPlayer(otherId);
            if (otherRef != null && otherRef.isValid()) {
                otherRef.getHiddenPlayersManager().showPlayer(playerId);
            }
        }
    }

    /**
     * Shows all party members to each other — used during game cleanup to
     * undo any death-related hiding.
     */
    private void showAllPartyMembers(@Nonnull UUID partyId) {
        List<UUID> members = playerToParty.entrySet().stream()
                .filter(e -> e.getValue().equals(partyId))
                .map(Map.Entry::getKey)
                .toList();
        for (UUID memberId : members) {
            for (UUID otherId : members) {
                if (memberId.equals(otherId)) continue;
                PlayerRef otherRef = Universe.get().getPlayer(otherId);
                if (otherRef != null && otherRef.isValid()) {
                    otherRef.getHiddenPlayersManager().showPlayer(memberId);
                }
            }
        }
    }

    @Nonnull
    public PlayerInventoryService getInventoryService() {
        return inventoryService;
    }

    @Nonnull
    public MobSpawningService getMobSpawningService() {
        return mobSpawningService;
    }

    @Nonnull
    public ReviveMarkerService getReviveMarkerService() {
        return reviveMarkerService;
    }

    @Nonnull
    public PlayerStateService getPlayerStateService() {
        return playerStateService;
    }
}
