package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
import dev.ninesliced.unstablerifts.tooltip.ArmorVirtualItems;
import dev.ninesliced.unstablerifts.tooltip.WeaponVirtualItems;
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
    /**
     * Players currently in the process of being teleported back to a dungeon
     * after accepting the rejoin prompt. Prevents normalizeOutsideDungeonState from interfering.
     */
    private final Set<UUID> pendingReconnectPlayers = ConcurrentHashMap.newKeySet();
    /**
     * Players who need a full inventory resync after PlayerReadyEvent fires
     * in the dungeon world. This ensures virtual item definitions reach the
     * client after it has finished loading (post-ClientReady).
     */
    private final Set<UUID> pendingPostReadyResync = ConcurrentHashMap.newKeySet();
    /**
     * Players who have been shown the rejoin-dungeon popup and have not yet
     * responded. While in this set, normalization is deferred.
     */
    private final Set<UUID> pendingRejoinDecision = ConcurrentHashMap.newKeySet();

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
     * Creates the instance and generates the full level chain before entry.
     */
    @Nonnull
    public CompletableFuture<Game> startGame(@Nonnull UUID partyId,
                                             @Nonnull List<UUID> memberIds,
                                             @Nonnull Map<UUID, PlayerRef> memberRefs,
                                             @Nonnull Map<UUID, Ref<EntityStore>> memberEntities,
                                             @Nonnull Map<UUID, Store<EntityStore>> memberStores,
                                             @Nonnull World leaderWorld,
                                             @Nonnull Transform leaderReturnPoint,
                                             @Nullable String levelSelector) {
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

        DungeonConfig.LevelConfig selectedFirstLevelConfig = plugin.getDungeonInstanceService().resolveLevel(levelSelector);
        DungeonConfig.LevelConfig firstLevelConfig = selectedFirstLevelConfig != null
                ? selectedFirstLevelConfig
                : config.getLevels().get(0);
        List<DungeonConfig.LevelConfig> upcomingLevels = collectUpcomingLevels(config, firstLevelConfig);
        int totalLevels = upcomingLevels.size() + 1;
        Level firstLevel = new Level(firstLevelConfig.getName(), 0);
        game.addLevel(firstLevel);
        game.setCurrentLevelSelector(firstLevelConfig.getSelector());

        CompletableFuture<World> worldFuture = plugin.getDungeonInstanceService().spawnGeneratedInstance(
                leaderWorld,
                leaderReturnPoint,
                firstLevelConfig,
                status -> {
                    if (status != null && status.contains("errors")) {
                        broadcastToParty(partyId, status);
                    }
                },
                levelProgress -> updateGenerationProgress(game, levelProgress / totalLevels),
                mobSpawningService
        );

        CompletableFuture<World> readyWorldFuture = worldFuture.thenCompose(world -> {
            game.setInstanceWorld(world);

            Level generatedLevel = plugin.getDungeonInstanceService().getLastGeneratedLevel();
            if (generatedLevel != null) {
                game.setLevel(0, generatedLevel);
                // Store spawn position for level 0
                RoomData entrance = generatedLevel.getEntranceRoom();
                if (entrance != null) {
                    Vector3i anchor = entrance.getAnchor();
                    game.setLevelSpawnPosition(0, new Vector3d(anchor.x + 0.5, anchor.y + 1.0, anchor.z + 0.5));
                }
                // Track rooms whose mobs were already spawned during generation
                Set<RoomData> preSpawned = plugin.getDungeonInstanceService().getLastPreSpawnedRooms();
                if (preSpawned != null && !preSpawned.isEmpty()) {
                    game.setPreSpawnedRooms(preSpawned);
                    LOGGER.info("Carried over " + preSpawned.size() + " pre-spawned rooms for party " + partyId);
                }
                LOGGER.info("Applied generated level graph '" + generatedLevel.getName()
                        + "' with " + generatedLevel.getRooms().size() + " rooms for party " + partyId);
            } else {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "First dungeon level did not finish generating for party " + partyId + "."));
            }

            return generateRemainingLevelsBeforeEntry(
                    game,
                    world,
                    upcomingLevels,
                    totalLevels,
                    status -> broadcastToParty(partyId, status)
            ).thenApply(ignored -> world);
        });

        CompletableFuture<World> trackedReadyWorldFuture = readyWorldFuture.whenComplete((world, throwable) -> {
            if (throwable != null) {
                cleanupFailedGameStart(game);
            }
        });

        game.setGenerationFuture(trackedReadyWorldFuture);

        return trackedReadyWorldFuture.thenApply(world -> {
            updateGenerationProgress(game, 1.0f);
            game.setState(GameState.READY);
            plugin.getDungeonMapService().buildMap(game);
            PartyUiPage.refreshOpenPages();
            LOGGER.info("Game ready for party " + partyId + " in world " + world.getName());

            return game;
        });
    }

    /**
     * Distance between level origins along the X axis to prevent overlap.
     */
    private static final int LEVEL_OFFSET_X = 10000;

    /**
     * Number of rooms (from spawn outward) to spawn mobs in immediately.
     * Remaining rooms' mobs are deferred to the next tick.
     */
    private static final int EARLY_SPAWN_ROOM_COUNT = 3;

    /**
     * Generates all subsequent levels in the same world instance before players enter.
     * Each level is placed at a large X offset to avoid collision with previous levels.
     * Generation is chained: each level starts generating only after the previous one completes.
     */
    @Nonnull
    private CompletableFuture<Void> generateRemainingLevelsBeforeEntry(@Nonnull Game game,
                                                                       @Nonnull World world,
                                                                       @Nonnull List<DungeonConfig.LevelConfig> upcomingLevels,
                                                                       int totalLevels,
                                                                       @Nullable Consumer<String> statusConsumer) {
        if (upcomingLevels.isEmpty()) {
            LOGGER.info("No subsequent levels to generate before entry for party " + game.getPartyId());
            game.setBackgroundGenerating(false);
            return CompletableFuture.completedFuture(null);
        }

        game.setBackgroundGenerating(true);
        LOGGER.info("Generating " + upcomingLevels.size()
                + " additional level(s) before entry for party " + game.getPartyId());

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < upcomingLevels.size(); i++) {
            final int levelIndex = i + 1;
            final DungeonConfig.LevelConfig levelConfig = upcomingLevels.get(i);

            chain = chain.thenCompose(ignored -> {
                if (game.getState() == GameState.COMPLETE) {
                    return CompletableFuture.completedFuture(null);
                }

                if (statusConsumer != null) {
                    statusConsumer.accept("Generating level " + (levelIndex + 1) + "/" + totalLevels
                            + ": " + levelConfig.getName() + "...");
                }

                Vector3i origin = new Vector3i(LEVEL_OFFSET_X * levelIndex, 128, 0);
                return plugin.getDungeonInstanceService()
                        .generateLevelInWorld(world, levelConfig, origin, levelIndex,
                                levelProgress -> updateGenerationProgress(game,
                                        (levelIndex + levelProgress) / totalLevels))
                        .thenAccept(generatedLevel -> {
                            game.setLevel(levelIndex, generatedLevel);

                            // Store spawn position for this level
                            RoomData entrance = generatedLevel.getEntranceRoom();
                            if (entrance != null) {
                                Vector3i anchor = entrance.getAnchor();
                                game.setLevelSpawnPosition(levelIndex,
                                        new Vector3d(anchor.x + 0.5, anchor.y + 1.0, anchor.z + 0.5));
                            }

                            updateGenerationProgress(game, (levelIndex + 1.0f) / totalLevels);
                            LOGGER.info("Generated level '" + generatedLevel.getName()
                                    + "' (index " + levelIndex + ") with " + generatedLevel.getRooms().size()
                                    + " rooms for party " + game.getPartyId());
                        });
            });
        }

        chain.whenComplete((ignored, throwable) -> {
            game.setBackgroundGenerating(false);
            if (throwable != null) {
                LOGGER.log(java.util.logging.Level.WARNING,
                        "Full dungeon generation failed for party " + game.getPartyId(), throwable);
            } else {
                LOGGER.info("All dungeon levels generated before entry for party " + game.getPartyId());
            }
        });

        return chain;
    }

    private void updateGenerationProgress(@Nonnull Game game, float progress) {
        game.setGenerationProgress(progress);
        PartyUiPage.refreshOpenPages();
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
                plugin.getShopService().initializeShops(game, store, config.getShopKeeperMobId());

                // Some rooms may have had mobs pre-spawned during generation.
                // Only spawn mobs for the remaining rooms.
                Set<RoomData> preSpawned = game.getPreSpawnedRooms();
                if (preSpawned.isEmpty()) {
                    // No pre-spawning happened — spawn early rooms now, rest in background.
                    Set<RoomData> earlyRooms = mobSpawningService.spawnEarlyRoomMobs(currentLevel, store, EARLY_SPAWN_ROOM_COUNT);
                    world.execute(() -> {
                        mobSpawningService.spawnRemainingMobs(currentLevel, store, earlyRooms);
                    });
                } else {
                    // Early rooms were pre-spawned during generation — spawn the rest now.
                    mobSpawningService.spawnRemainingMobs(currentLevel, store, preSpawned);
                    game.setPreSpawnedRooms(Set.of()); // clear after use
                }

                // Spawn portals in unlocked rooms at level start (cosmetic only).
                for (RoomData room : currentLevel.getRooms()) {
                    if (room.getType() != RoomType.BOSS
                            && !room.isLocked()
                            && !room.getPortalPositions().isEmpty()) {
                        plugin.getPortalService().spawnPortal(room, world);
                    }
                }

                broadcastKeyLocationsToParty(game, currentLevel);
            }
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
        });
    }

    /**
     * Called when the boss room is cleared. Sets TRANSITIONING state and spawns
     * a portal in the boss room for players to confirm with the interaction key.
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

            if (!hasNextLevelConfig(game)) {
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
     * Called when a player confirms the boss room portal.
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
     * Advances to the next pre-generated level, teleports all players to its entrance, and starts it.
     * If background generation hasn't completed the next level yet, waits for it.
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
        game.setCurrentLevelIndex(nextIndex);
        game.setCurrentLevelSelector(nextLevelConfig.getSelector());

        // Check if the level was already background-generated
        Level nextLevel = (nextIndex < game.getLevels().size()) ? game.getLevels().get(nextIndex) : null;

        if (nextLevel != null && nextLevel.getEntranceRoom() != null) {
            // Level is ready — activate it immediately
            activateLevel(game, world, nextLevel, nextIndex);
        } else {
            // Level not yet generated — generate it now and activate when done
            LOGGER.info("Next level (index " + nextIndex + ") not yet generated for party "
                    + game.getPartyId() + "; generating on demand");
            broadcastToParty(game.getPartyId(), "Generating next level...");

            Vector3i origin = new Vector3i(LEVEL_OFFSET_X * nextIndex, 128, 0);
            plugin.getDungeonInstanceService()
                    .generateLevelInWorld(world, nextLevelConfig, origin, nextIndex, null)
                    .thenAccept(generatedLevel -> {
                        game.setLevel(nextIndex, generatedLevel);
                        RoomData entrance = generatedLevel.getEntranceRoom();
                        if (entrance != null) {
                            Vector3i anchor = entrance.getAnchor();
                            game.setLevelSpawnPosition(nextIndex,
                                    new Vector3d(anchor.x + 0.5, anchor.y + 1.0, anchor.z + 0.5));
                        }
                        activateLevel(game, world, generatedLevel, nextIndex);
                    });
        }
    }

    /**
     * Activates a pre-generated level: spawns mobs, spawns portals, teleports all players
     * to the stored spawn position, and sets the game state to ACTIVE.
     */
    private void activateLevel(@Nonnull Game game, @Nonnull World world,
                               @Nonnull Level level, int levelIndex) {
        DungeonConfig config = plugin.loadDungeonConfig();
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();

            plugin.getShopService().initializeShops(game, store, config.getShopKeeperMobId());

            // Spawn mobs in the first few rooms immediately so players can start playing.
            Set<RoomData> earlyRooms = mobSpawningService.spawnEarlyRoomMobs(level, store, EARLY_SPAWN_ROOM_COUNT);

            // Spawn portals for unlocked rooms in the new level.
            for (RoomData room : level.getRooms()) {
                if (room.getType() != RoomType.BOSS
                        && !room.isLocked()
                        && !room.getPortalPositions().isEmpty()) {
                    plugin.getPortalService().spawnPortal(room, world);
                }
            }

            // Teleport all players to the stored spawn position for this level.
            Vector3d spawnPos = game.getLevelSpawnPosition(levelIndex);
            if (spawnPos == null) {
                // Fallback: read from the entrance room directly
                RoomData entrance = level.getEntranceRoom();
                if (entrance != null) {
                    Vector3i anchor = entrance.getAnchor();
                    spawnPos = new Vector3d(anchor.x + 0.5, anchor.y + 1.0, anchor.z + 0.5);
                    game.setLevelSpawnPosition(levelIndex, spawnPos);
                }
            }

            if (spawnPos != null) {
                teleportAllPlayersToPosition(game, world, store, spawnPos);
            } else {
                LOGGER.warning("No spawn position available for level index " + levelIndex
                        + " for party " + game.getPartyId());
            }

            game.setState(GameState.ACTIVE);
            game.setBossRoomSealed(false);
            plugin.getDungeonMapService().buildMap(game);
            PartyUiPage.refreshOpenPages();
            broadcastKeyLocationsToParty(game, level);
            LOGGER.info("Activated level '" + level.getName() + "' (index " + levelIndex
                    + ") for party " + game.getPartyId());

            // Spawn remaining mobs in background.
            world.execute(() -> {
                mobSpawningService.spawnRemainingMobs(level, store, earlyRooms);
            });
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
        game.clearDisconnectedDungeonInventories();
        showAllPartyMembers(game.getPartyId());

        // Clean up pending reconnect state for any disconnected players
        for (UUID disconnectedId : game.getDisconnectedPlayers()) {
            pendingReconnectPlayers.remove(disconnectedId);
            pendingReadyRecoveryPlayers.remove(disconnectedId);
            pendingRejoinDecision.remove(disconnectedId);
            RejoinDungeonPage.closeForPlayer(disconnectedId);
        }
        game.clearDisconnectedPlayers();
        game.clearDisconnectedPositions();

        List<UUID> partyMembers = playerToParty.entrySet().stream()
                .filter(entry -> entry.getValue().equals(game.getPartyId()))
                .map(Map.Entry::getKey)
                .toList();

        for (UUID playerId : partyMembers) {
            reviveMarkerService.despawnReviveMarker(playerId);
            inventoryService.removeDeathSnapshot(playerId);
            pendingRejoinDecision.remove(playerId);
            pendingReconnectPlayers.remove(playerId);
            pendingPostReadyResync.remove(playerId);
            RejoinDungeonPage.closeForPlayer(playerId);

            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null) continue;

            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) continue;

            Store<EntityStore> store = ref.getStore();
            Transform returnPoint = game.getReturnPoints().get(playerId);
            World trackedReturnWorld = game.getReturnWorlds().get(playerId);
            // wasInInstance covers players actively in the dungeon.
            // wasReconnected covers players who reconnected and are in the
            // home world (popup shown or pending) — they still need to be
            // teleported to their return point and have inventory restored.
            boolean wasInInstance = game.isPlayerInInstance(playerId);
            boolean wasReconnected = !wasInInstance && (game.isDisconnectedPlayer(playerId)
                    || outsideDungeonNormalizedPlayers.contains(playerId));

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

                boolean physicallyInInstance = player.getWorld() == game.getInstanceWorld();
                boolean treatAsInInstance = wasInInstance || physicallyInInstance;
                boolean treatAsReconnected = !treatAsInInstance && wasReconnected;

                plugin.getInventoryLockService().unlock(player, playerId);
                if (treatAsInInstance) {
                    inventoryService.restorePlayerInventory(playerId, player, true);
                } else if (treatAsReconnected) {
                    if (inventoryService.hasSavedInventoryFile(playerId)) {
                        inventoryService.deleteSavedInventoryFiles(playerId);
                    }
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

                if ((treatAsInInstance || treatAsReconnected) && returnPoint != null) {
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
        plugin.getShopService().clearGame(game.getPartyId());
        activeGames.remove(game.getPartyId());

        if (forced) {
            broadcastToParty(game.getPartyId(), "The dungeon run was cancelled.");
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
        boolean wasDisconnected = game.isDisconnectedPlayer(playerId);
        game.removeDisconnectedPlayer(playerId);
        game.setPlayerInInstance(playerId, false);
        LOGGER.info("onPlayerLeftParty: party=" + partyId
                + " player=" + playerId
                + " state=" + game.getState()
                + " wasInInstance=" + wasInInstance
                + " wasDisconnected=" + wasDisconnected
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

                        boolean physicallyInInstance = player.getWorld() == game.getInstanceWorld();
                        boolean treatAsInInstance = wasInInstance || physicallyInInstance;
                        boolean treatAsDisconnected = !treatAsInInstance && wasDisconnected;

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
                        if (treatAsInInstance) {
                            inventoryService.restorePlayerInventory(playerId, player, true);
                        } else if (treatAsDisconnected && inventoryService.hasSavedInventoryFile(playerId)) {
                            // Reconnected player in home world — home inventory is already
                            // restored, just delete the saved file since they're leaving.
                            inventoryService.deleteSavedInventoryFiles(playerId);
                        } else if (inventoryService.shouldPreserveSavedInventoryDuringCleanup(playerId, player, game)) {
                            LOGGER.info("Preserving saved inventory for disconnected player " + playerId
                                    + " during party-leave cleanup.");
                        } else {
                            LOGGER.info("Deleting saved inventory for player " + playerId
                                    + " during party-leave cleanup.");
                            inventoryService.deleteSavedInventoryFiles(playerId);
                        }
                        playerStateService.resetPlayerStatus(player, ref, store);

                        if ((treatAsInInstance || treatAsDisconnected) && returnPoint != null) {
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
     * If the player is in an active dungeon run, their dungeon inventory is
     * snapshotted in-memory and they are marked as disconnected so they can
     * automatically rejoin on reconnect.
     */
    public void onPlayerDisconnect(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        pendingReadyRecoveryPlayers.remove(playerId);
        pendingReconnectPlayers.remove(playerId);
        pendingPostReadyResync.remove(playerId);
        pendingRejoinDecision.remove(playerId);
        RejoinDungeonPage.closeForPlayer(playerId);
        inventoryService.removeDeathSnapshot(playerId);

        UUID partyId = playerToParty.get(playerId);
        Game game = partyId != null ? activeGames.get(partyId) : null;
        boolean wasInActiveDungeon = game != null && game.getState() != GameState.COMPLETE
                && (game.isPlayerInInstance(playerId) || game.isPlayerDead(playerId));

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref != null && ref.isValid()) {
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            world.execute(() -> {
                if (!ref.isValid()) {
                    return;
                }

                // Camera and revive marker cleanup must run on the world thread
                // because they access store components (which assert thread safety).
                plugin.getCameraService().restoreDefault(playerRef);
                reviveMarkerService.despawnReviveMarker(playerId);

                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null && !player.wasRemoved()) {
                    playerStateService.hideDungeonHuds(player, playerRef);
                }
            });
        } else {
            plugin.getCameraService().cancelDeferredEnable(playerRef);
            reviveMarkerService.despawnReviveMarker(playerId);
        }

        plugin.getInventoryLockService().remove(playerId);

        if (game == null) return;

        if (wasInActiveDungeon) {
            showPlayerToParty(playerId, partyId);
            game.setPlayerInInstance(playerId, false);
            game.removeDeadPlayer(playerId);
            game.addDisconnectedPlayer(playerId);
            if (!closeGameIfInstanceEmpty(game)) {
                PartyUiPage.refreshOpenPages();
            }
        } else {
            game.addDisconnectedPlayer(playerId);
            PartyUiPage.refreshOpenPages();
        }
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
     * On player connect, check if they have a saved inventory from a crash/disconnect,
     * or if they are reconnecting to an active dungeon run.
     */
    public void onPlayerConnect(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();

        UUID partyId = playerToParty.get(playerId);
        Game game = partyId != null ? activeGames.get(partyId) : null;
        if (game != null && game.isDisconnectedPlayer(playerId)
                && game.getState() != GameState.COMPLETE) {
            pendingRejoinDecision.add(playerId);
            return;
        }

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

    /**
     * Called after PlayerReadyEvent fires in the dungeon world to re-send
     * inventory with fresh virtual item definitions. During world transition
     * the client may not process UpdateItems packets, so we clear the
     * per-player sent tracking and resync once the client is fully loaded.
     */
    public void handlePostReadyResync(@Nonnull PlayerRef playerRef, @Nonnull Player player) {
        UUID playerId = playerRef.getUuid();
        if (!pendingPostReadyResync.remove(playerId)) return;

        WeaponVirtualItems.onPlayerDisconnect(playerId);
        ArmorVirtualItems.onPlayerDisconnect(playerId);

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            LOGGER.warning("handlePostReadyResync: invalid ref for " + playerId);
            return;
        }
        Store<EntityStore> store = ref.getStore();

        playerStateService.resetPlayerStatus(player, ref, store, null, true);

        plugin.getInventoryLockService().unlock(player, playerId);
        inventoryService.clearPlayerInventory(player);
        Game game = findGameForPlayer(playerId);
        if (game != null) {
            game.removeDisconnectedDungeonInventory(playerId);
        }
        DungeonConfig config = plugin.loadDungeonConfig();
        inventoryService.giveStartEquipment(playerRef, player, config);

        plugin.getInventoryLockService().lock(player, playerId);
        plugin.getCameraService().forceReapply(playerRef);
        // Re-apply dungeon movement after forceReapply so the dungeon values win.
        playerStateService.applyDungeonMovementSettings(ref, store, playerRef);

        playerStateService.enableMap(playerRef);
        if (game != null) {
            plugin.getDungeonMapService().sendMapToPlayer(playerRef, game);
        }

        inventoryService.syncInventoryAndSelectedSlots(playerRef, ref, store);

        if (game != null) {
            Vector3d savedPosition = game.removeDisconnectedPosition(playerId);
            if (savedPosition != null) {
                Teleport tp = Teleport.createForPlayer(savedPosition, new Rotation3f());
                store.putComponent(ref, Teleport.getComponentType(), tp);
            } else {
                Level currentLevel = game.getCurrentLevel();
                if (currentLevel != null) {
                    RoomData entrance = currentLevel.getEntranceRoom();
                    if (entrance != null) {
                        Vector3i anchor = entrance.getAnchor();
                        Vector3d spawnPos = new Vector3d(anchor.x + 0.5, anchor.y + 1.0, anchor.z + 0.5);
                        Teleport tp = Teleport.createForPlayer(spawnPos, new Rotation3f());
                        store.putComponent(ref, Teleport.getComponentType(), tp);
                    }
                }
            }
            PartyUiPage.refreshOpenPages();
        }

        // Re-apply movement one tick later in case engine systems overwrite it.
        World world = player.getWorld();
        if (world != null) {
            world.execute(() -> {
                Ref<EntityStore> delayedRef = playerRef.getReference();
                if (delayedRef != null && delayedRef.isValid()) {
                    Store<EntityStore> delayedStore = delayedRef.getStore();
                    playerStateService.applyDungeonMovementSettings(delayedRef, delayedStore, playerRef);
                }
            });
        }
    }

    /**
     * Final dungeon-entry safeguard that runs after PlayerReady in the dungeon
     * world. If the world transfer left the player without the dungeon lock,
     * inventory snapshot, or in-instance tracking, re-apply the standard
     * dungeon setup now that the client/world load is complete.
     */
    public void reconcileDungeonStateOnReady(@Nonnull PlayerRef playerRef,
                                             @Nonnull Player player,
                                             @Nonnull Game game) {
        if (game.getState() == GameState.COMPLETE) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        World world = player.getWorld();
        if (world == null || world != game.getInstanceWorld()) {
            return;
        }

        if (pendingRejoinDecision.contains(playerId) || pendingReconnectPlayers.contains(playerId)) {
            return;
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            LOGGER.warning("reconcileDungeonStateOnReady: invalid ref for " + playerId);
            return;
        }

        Store<EntityStore> store = ref.getStore();

        boolean inInstance = game.isPlayerInInstance(playerId);
        boolean inventoryLocked = plugin.getInventoryLockService().isLocked(playerId);
        boolean hasSavedInventory = inventoryService.hasSavedInventoryFile(playerId);
        boolean unexpectedInventory = inventoryService.hasUnexpectedDungeonEntryInventory(ref, store);

        if (inInstance && hasSavedInventory && !unexpectedInventory) {
            return;
        }

        DungeonConfig config = plugin.loadDungeonConfig();
        LOGGER.warning("Re-applying dungeon setup after PlayerReady for " + playerRef.getUsername()
                + " inInstance=" + inInstance
                + " inventoryLocked=" + inventoryLocked
                + " hasSavedInventory=" + hasSavedInventory
                + " unexpectedInventory=" + unexpectedInventory);
        preparePlayerForDungeon(game, playerId, playerRef, player, ref, store, config);
        PartyUiPage.refreshOpenPages();
    }

    /**
     * Called when a previously-disconnected player reconnects and is ready.
     * Ensures the player is in their home world with normal inventory, then
     * shows a popup asking if they want to rejoin the dungeon.
     */
    public void handlePlayerReconnect(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        boolean wasInSet = pendingRejoinDecision.remove(playerId);
        if (!wasInSet) return;

        UUID partyId = playerToParty.get(playerId);
        Game game = partyId != null ? activeGames.get(partyId) : null;

        if (game == null || !game.isDisconnectedPlayer(playerId)
                || game.getState() == GameState.COMPLETE) {
            return;
        }

        World instanceWorld = game.getInstanceWorld();
        if (instanceWorld == null) {
            game.removeDisconnectedPlayer(playerId);
            return;
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            LOGGER.warning("handlePlayerReconnect: ref not ready for " + playerId);
            return;
        }

        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        // Exit the instance from PlayerReady, where cross-world teleports are reliable.
        World currentWorld = player.getWorld();
        boolean inDungeonWorld = currentWorld != null && currentWorld == instanceWorld;
        if (inDungeonWorld) {
            try {
                InstancesPlugin.exitInstance(ref, store);
            } catch (Exception e) {
                LOGGER.warning("handlePlayerReconnect: failed to exit instance for " + playerId
                        + ": " + e.getClass().getName() + ": " + e.getMessage());
                Transform returnPoint = game.getReturnPoints().get(playerId);
                World returnWorld = game.getReturnWorlds().get(playerId);
                if (returnWorld == null) returnWorld = Universe.get().getDefaultWorld();
                if (returnWorld != null && returnPoint != null) {
                    teleportPlayerToReturnPoint(ref, store, returnWorld, returnPoint);
                } else {
                    LOGGER.warning("handlePlayerReconnect: no fallback return point for " + playerId);
                }
            }
            pendingRejoinDecision.add(playerId);
            return;
        }

        restoreHomeStateAndShowPopup(game, playerId, playerRef, player, ref, store);
    }

    /**
     * Restores the player's home (pre-dungeon) inventory and opens the rejoin
     * popup. Called after the player is confirmed to be in the home world.
     */
    private void restoreHomeStateAndShowPopup(@Nonnull Game game,
                                              @Nonnull UUID playerId,
                                              @Nonnull PlayerRef playerRef,
                                              @Nonnull Player player,
                                              @Nonnull Ref<EntityStore> ref,
                                              @Nonnull Store<EntityStore> store) {
        outsideDungeonNormalizedPlayers.add(playerId);

        DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
        if (deathComponent != null) deathComponent.reset();
        DeathStateController.clear(store, ref);
        playerStateService.resetPlayerStatus(player, ref, store);

        plugin.getCameraService().restoreDefault(playerRef);
        playerStateService.hideDungeonHuds(player, playerRef);
        plugin.getInventoryLockService().unlock(player, playerId);

        if (inventoryService.hasSavedInventoryFile(playerId)) {
            inventoryService.restorePlayerInventory(playerId, player, false);
        }

        player.getPageManager().openCustomPage(ref, store, new RejoinDungeonPage(plugin, playerRef));
        PartyUiPage.refreshOpenPages();
    }

    /**
     * Called when a reconnecting player accepts the rejoin popup.
     * Saves home inventory, teleports to dungeon, restores dungeon inventory.
     */
    public void handleRejoinAccepted(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        UUID partyId = playerToParty.get(playerId);
        Game game = partyId != null ? activeGames.get(partyId) : null;

        if (game == null || game.getState() == GameState.COMPLETE) {
            PlayerEventNotifier.showEventTitle(playerRef, "The dungeon run has ended.", true);
            return;
        }

        World instanceWorld = game.getInstanceWorld();
        if (instanceWorld == null) {
            PlayerEventNotifier.showEventTitle(playerRef, "The dungeon instance is no longer available.", true);
            game.removeDisconnectedPlayer(playerId);
            return;
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return;
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        if (!inventoryService.hasSavedInventoryFile(playerId)) {
            inventoryService.savePlayerInventory(playerId, player, game);
        }

        pendingReconnectPlayers.add(playerId);
        pendingReadyRecoveryPlayers.add(playerId);

        plugin.getCameraService().scheduleEnableOnNextReady(playerRef);
        sendPlayerToDungeon(
                game,
                playerRef,
                ref,
                store,
                CompletableFuture.completedFuture(instanceWorld),
                status -> {
                    plugin.getCameraService().cancelDeferredEnable(playerRef);
                    pendingReconnectPlayers.remove(playerId);
                    pendingReadyRecoveryPlayers.remove(playerId);
                    PlayerEventNotifier.showEventTitle(playerRef, status, true);
                    LOGGER.warning("handleRejoinAccepted: failed to teleport " + playerId + " to dungeon: " + status);
                }
        );
    }

    /**
     * Called when a reconnecting player declines the rejoin popup (or closes it).
     * Removes them from the party and cleans up disconnected state.
     */
    public void handleRejoinDeclined(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        UUID partyId = playerToParty.get(playerId);
        Game game = partyId != null ? activeGames.get(partyId) : null;

        if (game != null) {
            game.removeDisconnectedPlayer(playerId);
            game.removeDisconnectedDungeonInventory(playerId);
            game.removeDisconnectedPosition(playerId);
        }

        if (inventoryService.hasSavedInventoryFile(playerId)) {
            inventoryService.deleteSavedInventoryFiles(playerId);
        }

        if (game != null) {
            Transform returnPoint = game.getReturnPoints().get(playerId);
            World returnWorld = game.getReturnWorlds().get(playerId);
            if (returnPoint != null) {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref != null && ref.isValid()) {
                    Store<EntityStore> store = ref.getStore();
                    if (returnWorld == null) returnWorld = Universe.get().getDefaultWorld();
                    if (returnWorld != null) {
                        teleportPlayerToReturnPoint(ref, store, returnWorld, returnPoint);
                    }
                }
            }
        }

        plugin.getPartyManager().leave(playerRef);
    }

    /**
     * Restores a reconnecting player's dungeon state (inventory, camera, movement, map)
     * once they arrive in the dungeon world after accepting the rejoin popup.
     */
    private void restorePlayerToDungeon(@Nonnull Game game,
                                        @Nonnull UUID playerId,
                                        @Nonnull PlayerRef playerRef,
                                        @Nonnull Player player,
                                        @Nonnull Ref<EntityStore> ref,
                                        @Nonnull Store<EntityStore> store) {
        outsideDungeonNormalizedPlayers.remove(playerId);
        pendingReconnectPlayers.remove(playerId);
        pendingReadyRecoveryPlayers.remove(playerId);

        DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
        if (deathComponent != null) {
            deathComponent.reset();
        }
        DeathStateController.clear(store, ref);
        playerStateService.resetPlayerStatus(player, ref, store, null, true);

        // Reconnects always start from the base dungeon loadout.
        plugin.getInventoryLockService().unlock(player, playerId);
        inventoryService.clearPlayerInventory(player);
        game.removeDisconnectedDungeonInventory(playerId);
        DungeonConfig config = plugin.loadDungeonConfig();
        inventoryService.giveStartEquipment(playerRef, player, config);

        plugin.getInventoryLockService().lock(player, playerId);

        WeaponVirtualItems.onPlayerDisconnect(playerId);
        ArmorVirtualItems.onPlayerDisconnect(playerId);

        plugin.getCameraService().scheduleEnableOnNextReady(playerRef);
        playerStateService.applyDungeonMovementSettings(ref, store, playerRef);
        playerStateService.enableMap(playerRef);
        plugin.getDungeonMapService().sendMapToPlayer(playerRef, game);
        game.removeDisconnectedPlayer(playerId);
        game.setPlayerInInstance(playerId, true);
        pendingPostReadyResync.add(playerId);

        Vector3d savedPosition = game.removeDisconnectedPosition(playerId);
        if (savedPosition != null) {
            Teleport tp = Teleport.createForPlayer(savedPosition, new Rotation3f());
            store.putComponent(ref, Teleport.getComponentType(), tp);
        } else {
            Level currentLevel = game.getCurrentLevel();
            if (currentLevel != null) {
                RoomData entrance = currentLevel.getEntranceRoom();
                if (entrance != null) {
                    Vector3i anchor = entrance.getAnchor();
                    Vector3d spawnPos = new Vector3d(anchor.x + 0.5, anchor.y + 1.0, anchor.z + 0.5);
                    Teleport tp = Teleport.createForPlayer(spawnPos, new Rotation3f());
                    store.putComponent(ref, Teleport.getComponentType(), tp);
                }
            }
        }
        PartyUiPage.refreshOpenPages();
    }

    public void onPlayerAddedToWorld(@Nonnull PlayerRef playerRef, @Nonnull Player player, @Nonnull World world) {
        UUID playerId = playerRef.getUuid();
        Game game = findGameForPlayer(playerId);
        boolean isReconnecting = game != null && game.isDisconnectedPlayer(playerId);
        boolean inActiveDungeonWorld = game != null
                && game.getState() != GameState.COMPLETE
                && world == game.getInstanceWorld();

        // Defer rejoin handling to PlayerReady, where cross-world teleports are reliable.
        if (pendingRejoinDecision.contains(playerId)) {
            return;
        }

        if (!inActiveDungeonWorld) {
            if (isReconnecting && pendingReconnectPlayers.contains(playerId)) {
                return;
            }
            normalizeOutsideDungeonState(playerRef, player, game);
            return;
        }

        outsideDungeonNormalizedPlayers.remove(playerId);

        // Defer ref-dependent restore work until PlayerReady.
        if (isReconnecting) {
            pendingReconnectPlayers.remove(playerId);
            pendingReadyRecoveryPlayers.remove(playerId);
            game.removeDisconnectedPlayer(playerId);
            game.setPlayerInInstance(playerId, true);
            pendingPostReadyResync.add(playerId);
            return;
        }

        if (game.isPlayerInInstance(playerId)) {
            return;
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        DungeonConfig config = plugin.loadDungeonConfig();
        if (!inventoryService.hasSavedInventoryFile(playerId)) {
            LOGGER.info("Late dungeon setup for " + playerRef.getUsername()
                    + " after world join; the timed start path missed the initial inventory snapshot.");
        }
        preparePlayerForDungeon(game, playerId, playerRef, player, ref, store, config);
        PartyUiPage.refreshOpenPages();
    }

    public void onPlayerRemovedFromWorld(@Nonnull PlayerRef playerRef, @Nonnull Player player, @Nonnull World world) {
        Game game = findGameForPlayer(playerRef.getUuid());
        if (game == null || game.getState() == GameState.COMPLETE || world != game.getInstanceWorld()) {
            return;
        }
        if (!game.isPlayerInInstance(playerRef.getUuid())) {
            return;
        }

        // Snapshot alive players before the entity is removed so reconnect can restore them.
        boolean alive = !game.isPlayerDead(playerRef.getUuid());
        if (alive) {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                Store<EntityStore> store = ref.getStore();
                DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
                if (deathComponent != null && deathComponent.isDead()) {
                    alive = false;
                }
            }
        }
        if (alive) {
            PlayerInventoryService.InventorySaveData snapshot =
                    inventoryService.snapshotCurrentInventory(player);
            if (snapshot != null) {
                game.putDisconnectedDungeonInventory(playerRef.getUuid(), snapshot);
            }
            Ref<EntityStore> refPos = playerRef.getReference();
            if (refPos != null && refPos.isValid()) {
                Store<EntityStore> storePos = refPos.getStore();
                TransformComponent transform = storePos.getComponent(refPos, TransformComponent.getComponentType());
                if (transform != null) {
                    game.putDisconnectedPosition(playerRef.getUuid(),
                            new Vector3d(transform.getPosition()));
                }
            }
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

        if (pendingReconnectPlayers.contains(playerId)) {
            return false;
        }

        if (pendingRejoinDecision.contains(playerId)) {
            return false;
        }

        if (game != null && game.isPlayerInInstance(playerId)) {
            return false;
        }

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

                // Only capture return point if not already set (reconnecting players keep their original home)
                if (!game.getReturnPoints().containsKey(playerId)) {
                    Transform returnPoint = DungeonInstanceService.captureReturnPoint(store, ref);
                    game.getReturnPoints().put(playerId, returnPoint);
                    game.getReturnWorlds().put(playerId, currentWorld);
                }
                Transform returnPoint = game.getReturnPoints().get(playerId);

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

    @Nonnull
    private List<DungeonConfig.LevelConfig> collectUpcomingLevels(@Nonnull DungeonConfig config,
                                                                  @Nonnull DungeonConfig.LevelConfig firstLevelConfig) {
        List<DungeonConfig.LevelConfig> upcomingLevels = new ArrayList<>();
        Set<String> visitedSelectors = new LinkedHashSet<>();

        String firstSelector = firstLevelConfig.getSelector();
        if (firstSelector != null && !firstSelector.isBlank()) {
            visitedSelectors.add(firstSelector);
        }

        DungeonConfig.LevelConfig currentConfig = firstLevelConfig;
        while (true) {
            String nextLevelSelector = currentConfig.getNextLevel();
            if (nextLevelSelector == null || nextLevelSelector.isBlank()) {
                return upcomingLevels;
            }

            if (!visitedSelectors.add(nextLevelSelector)) {
                throw new IllegalStateException("Dungeon level chain contains a loop at selector '" + nextLevelSelector + "'.");
            }

            DungeonConfig.LevelConfig nextLevelConfig = config.findLevel(nextLevelSelector);
            if (nextLevelConfig == null) {
                throw new IllegalStateException("Level '" + currentConfig.getSelector()
                        + "' points to unknown nextLevel '" + nextLevelSelector + "'.");
            }

            upcomingLevels.add(nextLevelConfig);
            currentConfig = nextLevelConfig;
        }
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

        // Don't close the game if disconnected players may still reconnect
        if (!game.getDisconnectedPlayers().isEmpty()) {
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

    private void cleanupFailedGameStart(@Nonnull Game game) {
        if (activeGames.remove(game.getPartyId()) == null) {
            return;
        }

        game.setState(GameState.COMPLETE);
        game.setBackgroundGenerating(false);
        game.clearDisconnectedPlayers();
        game.clearDisconnectedDungeonInventories();
        game.clearDisconnectedPositions();

        plugin.getDungeonMapService().cleanup(game.getPartyId());
        plugin.getShopService().clearGame(game.getPartyId());

        World instanceWorld = game.getInstanceWorld();
        if (instanceWorld != null) {
            instanceWorld.execute(() -> InstancesPlugin.safeRemoveInstance(instanceWorld));
        }

        playerToParty.entrySet().removeIf(e -> e.getValue().equals(game.getPartyId()));
        PartyUiPage.refreshOpenPages();

        LOGGER.warning("Cleaned up failed dungeon start for party " + game.getPartyId());
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

    public void broadcastToParty(@Nonnull UUID partyId, @Nonnull String text) {
        for (Map.Entry<UUID, UUID> entry : playerToParty.entrySet()) {
            if (entry.getValue().equals(partyId)) {
                PlayerRef playerRef = Universe.get().getPlayer(entry.getKey());
                if (playerRef != null) {
                    PlayerEventNotifier.showEventTitle(playerRef, text, true);
                }
            }
        }
    }

    public void broadcastToParty(@Nonnull UUID partyId, @Nonnull String title, @Nullable String subtitle) {
        for (Map.Entry<UUID, UUID> entry : playerToParty.entrySet()) {
            if (entry.getValue().equals(partyId)) {
                PlayerRef playerRef = Universe.get().getPlayer(entry.getKey());
                if (playerRef != null) {
                    PlayerEventNotifier.showEventTitle(playerRef, title, subtitle, true);
                }
            }
        }
    }

    private void broadcastChatToParty(@Nonnull UUID partyId, @Nonnull String text) {
        for (Map.Entry<UUID, UUID> entry : playerToParty.entrySet()) {
            if (!entry.getValue().equals(partyId)) {
                continue;
            }

            PlayerRef playerRef = Universe.get().getPlayer(entry.getKey());
            if (playerRef != null) {
                playerRef.sendMessage(Message.raw(text));
            }
        }
    }

    private void broadcastKeyLocationsToParty(@Nonnull Game game, @Nonnull Level level) {
        List<Vector3i> keySpawnPositions = level.getKeySpawnPositions();
        if (keySpawnPositions.isEmpty()) {
            broadcastChatToParty(game.getPartyId(),
                    "[DEBUG] Level " + (level.getIndex() + 1) + " has no spawned keys.");
            return;
        }

        for (int i = 0; i < keySpawnPositions.size(); i++) {
            Vector3i pos = keySpawnPositions.get(i);
            broadcastChatToParty(game.getPartyId(),
                    "[DEBUG] Level " + (level.getIndex() + 1)
                            + " key " + (i + 1) + "/" + keySpawnPositions.size()
                            + " at (" + pos.x + ", " + pos.y + ", " + pos.z + ")");
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
                "All players have fallen! The dungeon run is over.");
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
                "All fallen players have been revived!");
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
