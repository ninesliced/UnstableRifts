package dev.ninesliced.shotcave.dungeon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import org.joml.Vector3d;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3i;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.InteractionSimulationHandler;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMapSettings;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import dev.ninesliced.shotcave.OnlinePlayers;
import dev.ninesliced.shotcave.PlayerEventNotifier;
import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.armor.ArmorChargeComponent;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.inventory.InventoryLockService;
import dev.ninesliced.shotcave.hud.DungeonInfoHud;
import dev.ninesliced.shotcave.hud.DeathCountdownHud;
import dev.ninesliced.shotcave.hud.PartyStatusHud;
import dev.ninesliced.shotcave.hud.ReviveProgressHud;
import dev.ninesliced.shotcave.party.PartyManager;
import dev.ninesliced.shotcave.party.PartyUiPage;
import dev.ninesliced.shotcave.systems.DeathComponent;
import dev.ninesliced.shotcave.systems.DeathMovementController;
import dev.ninesliced.shotcave.systems.DeathStateController;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.function.Consumer;

import org.bson.BsonDocument;

/**
 * Orchestrates the dungeon game lifecycle: generation, start, boss, end, cleanup.
 */
public final class GameManager {

    private static final Logger LOGGER = Logger.getLogger(GameManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String REVIVE_MARKER_MODEL_ID = "NPC_Spawn_Marker";
    private static final String REVIVE_MARKER_DOWN_ANIMATION = "Death";

    private final Shotcave plugin;
    /**
     * Active games indexed by party ID.
     */
    private final Map<UUID, Game> activeGames = new ConcurrentHashMap<>();
    /**
     * Reverse lookup: player UUID → party ID for fast game resolution.
     */
    private final Map<UUID, UUID> playerToParty = new ConcurrentHashMap<>();
    /**
     * Revive marker entity refs keyed by the downed player's UUID.
     */
    private final Map<UUID, Ref<EntityStore>> reviveMarkers = new ConcurrentHashMap<>();
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

    public GameManager(@Nonnull Shotcave plugin) {
        this.plugin = plugin;
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
                status -> broadcastToParty(partyId, status, status.startsWith("Dungeon ready") ? "#a9f5b3" : "#ffd38a")
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
                spawnLevelMobs(currentLevel, store);
                // Spawn portals in unlocked rooms at level start (cosmetic only).
                for (RoomData room : currentLevel.getRooms()) {
                    if (room.getType() != RoomType.BOSS
                            && !room.isLocked()
                            && !room.getPortalPositions().isEmpty()) {
                        plugin.getPortalService().spawnPortal(room, world);
                    }
                }
            }

            broadcastToParty(game.getPartyId(), "The dungeon awaits! Fight your way through!", "#a9f5b3");
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
                String doorBlock = levelConfig != null ? levelConfig.getDoorBlock() : "Stone_Brick_Wall";
                plugin.getDoorService().sealRoom(bossRoom, world, doorBlock);
                game.setBossRoomSealed(true);
            } else {
                sealBossRoom(game, bossRoom, world);
            }

            broadcastToParty(game.getPartyId(), "BOSS ROOM! Clear the room to advance!", "#ff6b6b");
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
                broadcastToParty(game.getPartyId(), "Boss defeated! Step into the portal to advance!", "#a9f5b3");
            } else {
                broadcastToParty(game.getPartyId(), "Dungeon Complete! Step into the portal to return home!", "#ffd700");
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
            spawnLevelMobs(nextLevel, store);

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
            broadcastToParty(game.getPartyId(), "Welcome to " + nextLevelConfig.getName() + "!", "#a9f5b3");
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

        game.setState(GameState.COMPLETE);
        game.clearDeadPlayers();

        List<UUID> partyMembers = playerToParty.entrySet().stream()
                .filter(entry -> entry.getValue().equals(game.getPartyId()))
                .map(Map.Entry::getKey)
                .toList();

        for (UUID playerId : partyMembers) {
            despawnReviveMarker(playerId);

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

                hideDungeonHuds(player, playerRef);

                plugin.getInventoryLockService().unlock(player, playerId);
                if (wasInInstance) {
                    restorePlayerInventory(playerId, player, true);
                } else if (shouldPreserveSavedInventoryDuringCleanup(playerId, player, game)) {
                    LOGGER.info("Preserving saved inventory for disconnected player " + playerId
                            + " during endGame cleanup.");
                } else {
                    LOGGER.info("Deleting saved inventory for player " + playerId
                            + " during endGame cleanup.");
                    deleteSavedInventory(playerId);
                }

                resetPlayerStatus(player, ref, store);
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
            broadcastToParty(game.getPartyId(), "The dungeon run was cancelled.", "#ffb0b0");
        }

        World instanceWorld = game.getInstanceWorld();
        if (instanceWorld != null) {
            instanceWorld.execute(() -> InstancesPlugin.safeRemoveInstance(instanceWorld));
        }

        playerToParty.entrySet().removeIf(e -> e.getValue().equals(game.getPartyId()));
        plugin.getPartyManager().closePartyForSystem(game.getPartyId(), "The dungeon run ended, so the party was closed.");
        PartyUiPage.refreshOpenPages();


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
        despawnReviveMarker(playerId);

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

                    hideDungeonHuds(player, playerRef);

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
                            restorePlayerInventory(playerId, player, true);
                        } else if (shouldPreserveSavedInventoryDuringCleanup(playerId, player, game)) {
                            LOGGER.info("Preserving saved inventory for disconnected player " + playerId
                                    + " during party-leave cleanup.");
                        } else {
                            LOGGER.info("Deleting saved inventory for player " + playerId
                                    + " during party-leave cleanup.");
                            deleteSavedInventory(playerId);
                        }
                        resetPlayerStatus(player, ref, store);

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
        plugin.getCameraService().restoreDefault(playerRef);
        plugin.getInventoryLockService().remove(playerId);
        despawnReviveMarker(playerId);

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
                    hideDungeonHuds(player, playerRef);
                }
            });
        }

        UUID partyId = playerToParty.get(playerId);
        if (partyId == null) return;

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

        if (!hasSavedInventory(playerId)) {
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
        boolean hasFileSnapshot = hasSavedInventoryFile(playerId);

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
        boolean hasSavedInventoryFile = hasSavedInventoryFile(playerId);
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

        despawnReviveMarker(playerRef.getUuid());

        plugin.getCameraService().restoreDefault(playerRef);
        hideDungeonHuds(player, playerRef);

        plugin.getInventoryLockService().unlock(player, playerRef.getUuid());
        restorePlayerInventory(playerRef.getUuid(), player, false);

        ref = playerRef.getReference();
        if (ref != null && ref.isValid()) {
            Store<EntityStore> store = ref.getStore();
            resetPlayerStatus(player, ref, store);
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
        boolean hasSavedInventory = hasSavedInventory(playerId);
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
        hideDungeonHuds(player, playerRef);
        plugin.getInventoryLockService().unlock(player, playerId);

        if (hasSavedInventory) {
            boolean deleteAfterRestore = game == null || game.getState() == GameState.COMPLETE;
            restorePlayerInventory(playerId, player, deleteAfterRestore);
        }

        if (ref != null && ref.isValid() && store != null) {
            resetPlayerStatus(player, ref, store, commandBuffer);
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

    private void savePlayerInventory(@Nonnull UUID playerId, @Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null) return;
        Store<EntityStore> store = ref.getStore();

        Path savePath = getPrimarySaveFilePath(playerId);
        try {
            Files.createDirectories(savePath.getParent());

            InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
            InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
            InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());

            InventorySaveData saveData = new InventorySaveData();
            saveData.hotbarItems = hotbarComp != null ? serializeContainer(hotbarComp.getInventory()) : null;
            saveData.storageItems = storageComp != null ? serializeContainer(storageComp.getInventory()) : null;
            saveData.armorItems = armorComp != null ? serializeContainer(armorComp.getInventory()) : null;
            saveData.utilityItems = utilityComp != null ? serializeContainer(utilityComp.getInventory()) : null;
            saveData.activeHotbarSlot = hotbarComp != null ? hotbarComp.getActiveSlot() : 0;

            String json = GSON.toJson(saveData);
            Files.writeString(savePath, json, StandardCharsets.UTF_8);

            Game game = findGameForPlayer(playerId);
            if (game != null) {
                game.getSavedInventoryPaths().put(playerId, savePath.toString());
            }

            LOGGER.info("Saved inventory for player " + playerId + " to " + savePath.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.log(java.util.logging.Level.SEVERE, "CRITICAL: Failed to save inventory for " + playerId, e);
        }
    }

    private void restorePlayerInventory(@Nonnull UUID playerId, @Nonnull Player player, boolean deleteAfterRestore) {
        try {
            Ref<EntityStore> ref = player.getReference();
            if (ref == null) return;
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            LoadedInventorySave loadedSave = loadInventorySave(playerId, ref, store);
            if (loadedSave == null || loadedSave.data == null) {
                LOGGER.warning("No saved inventory found for " + playerId
                        + " fileSnapshot=" + hasSavedInventoryFile(playerId));
                return;
            }
            InventorySaveData saveData = loadedSave.data;

            plugin.getInventoryLockService().unlock(player, playerId);

            clearPlayerInventory(player);

            InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
            InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
            InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());

            if (hotbarComp != null) restoreContainer(hotbarComp.getInventory(), saveData.hotbarItems);
            if (storageComp != null) restoreContainer(storageComp.getInventory(), saveData.storageItems);
            if (armorComp != null) restoreContainer(armorComp.getInventory(), saveData.armorItems);
            if (utilityComp != null) restoreContainer(utilityComp.getInventory(), saveData.utilityItems);

            if (hotbarComp != null && saveData.activeHotbarSlot >= 0
                    && saveData.activeHotbarSlot < hotbarComp.getInventory().getCapacity()) {
                hotbarComp.setActiveSlot(saveData.activeHotbarSlot);
            }

            syncInventoryAndSelectedSlots(playerRef, ref, store);

            if (deleteAfterRestore) {
                deleteSavedInventoryFiles(playerId);
            }

            LOGGER.info("Restored inventory for player " + playerId + " from " + loadedSave.source
                    + " deleteAfterRestore=" + deleteAfterRestore);
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.SEVERE, "Failed to restore inventory for " + playerId, e);
        }
    }

    private void preparePlayerForDungeon(@Nonnull Game game,
                                         @Nonnull UUID playerId,
                                         @Nonnull PlayerRef playerRef,
                                         @Nonnull Player player,
                                         @Nonnull Ref<EntityStore> ref,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull DungeonConfig config) {
        outsideDungeonNormalizedPlayers.remove(playerId);
        if (!hasSavedInventory(playerId)) {
            savePlayerInventory(playerId, player);
        }
        plugin.getInventoryLockService().unlock(player, playerId);
        clearPlayerInventory(player);
        resetPlayerStatus(player, ref, store);
        giveStartEquipment(playerRef, player, config);
        plugin.getInventoryLockService().lock(player, playerId);
        plugin.getCameraService().setEnabled(playerRef, true);
        applyDungeonMovementSettings(ref, store, playerRef);
        enableMap(playerRef);
        plugin.getDungeonMapService().sendMapToPlayer(playerRef, game);
        game.setPlayerInInstance(playerId, true);
    }

    private boolean hasSavedInventory(@Nonnull UUID playerId) {
        return hasSavedInventoryFile(playerId);
    }

    private void deleteSavedInventory(@Nonnull UUID playerId) {
        deleteSavedInventoryFiles(playerId);
    }

    private boolean shouldPreserveSavedInventoryDuringCleanup(@Nonnull UUID playerId,
                                                              @Nonnull Player player,
                                                              @Nullable Game game) {
        if (!hasSavedInventory(playerId)) {
            return false;
        }

        if (player.wasRemoved()) {
            return true;
        }

        World currentWorld = player.getWorld();
        return currentWorld == null || (game != null && currentWorld == game.getInstanceWorld());
    }

    private void clearPlayerInventory(@Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null) return;
        Store<EntityStore> store = ref.getStore();

        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());

        if (hotbarComp != null) clearContainer(hotbarComp.getInventory());
        if (storageComp != null) clearContainer(storageComp.getInventory());
        if (armorComp != null) clearContainer(armorComp.getInventory());
        if (utilityComp != null) clearContainer(utilityComp.getInventory());
    }

    private void clearContainer(@Nonnull ItemContainer container) {
        for (short i = 0; i < container.getCapacity(); i++) {
            container.removeItemStackFromSlot(i);
        }
    }

    @Nullable
    private LoadedInventorySave loadInventorySave(@Nonnull UUID playerId,
                                                  @Nonnull Ref<EntityStore> ref,
                                                  @Nonnull Store<EntityStore> store) throws IOException {
        if (ref == null || store == null) {
            return null;
        }
        Path savePath = findExistingSaveFilePath(playerId);
        if (Files.exists(savePath)) {
            String json = Files.readString(savePath, StandardCharsets.UTF_8);
            return new LoadedInventorySave("file:" + savePath, GSON.fromJson(json, InventorySaveData.class));
        }

        return null;
    }

    @Nonnull
    private SavedItemStack[] serializeContainer(@Nonnull ItemContainer container) {
        SavedItemStack[] items = new SavedItemStack[container.getCapacity()];
        for (short i = 0; i < container.getCapacity(); i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                SavedItemStack saved = new SavedItemStack();
                saved.itemId = stack.getItemId();
                saved.quantity = stack.getQuantity();
                saved.durability = stack.getDurability();
                saved.maxDurability = stack.getMaxDurability();
                saved.overrideDroppedItemAnimation = stack.getOverrideDroppedItemAnimation();
                BsonDocument metadata = stack.getMetadata();
                saved.metadataJson = metadata != null ? metadata.toJson() : null;
                items[i] = saved;
            }
        }
        return items;
    }

    private void restoreContainer(@Nonnull ItemContainer container, @Nullable SavedItemStack[] items) {
        if (items == null) return;
        for (short i = 0; i < Math.min(items.length, container.getCapacity()); i++) {
            SavedItemStack saved = items[i];
            if (saved != null && saved.itemId != null && !saved.itemId.isEmpty()) {
                try {
                    BsonDocument metadata = saved.metadataJson != null && !saved.metadataJson.isBlank()
                            ? BsonDocument.parse(saved.metadataJson)
                            : null;
                    ItemStack restored = new ItemStack(
                            saved.itemId,
                            Math.max(1, saved.quantity),
                            saved.durability,
                            saved.maxDurability,
                            metadata
                    );
                    restored.setOverrideDroppedItemAnimation(saved.overrideDroppedItemAnimation);
                    container.setItemStackForSlot(i, restored, false);
                } catch (Exception e) {
                    LOGGER.warning("Failed to restore item '" + saved.itemId + "' at slot " + i);
                }
            }
        }
    }

    // ────────────────────────────────────────────────
    //  Player status reset
    // ────────────────────────────────────────────────

    private void resetPlayerStatus(@Nonnull Player player,
                                   @Nonnull Ref<EntityStore> ref,
                                   @Nonnull Store<EntityStore> store) {
        resetPlayerStatus(player, ref, store, null);
    }

    private void resetPlayerStatus(@Nonnull Player player,
                                   @Nonnull Ref<EntityStore> ref,
                                   @Nonnull Store<EntityStore> store,
                                   @Nullable CommandBuffer<EntityStore> commandBuffer) {
        try {
            DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
            if (deathComponent != null) {
                deathComponent.reset();
            }

            DeathStateController.clear(commandBuffer, store, ref);

            EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
            if (statMap != null) {
                int healthIdx = EntityStatType.getAssetMap().getIndex("Health");
                int staminaIdx = EntityStatType.getAssetMap().getIndex("Stamina");

                EntityStatValue healthStat = statMap.get(healthIdx);
                if (healthStat != null) {
                    statMap.setStatValue(healthIdx, healthStat.getMax());
                }

                EntityStatValue staminaStat = statMap.get(staminaIdx);
                if (staminaStat != null) {
                    statMap.setStatValue(staminaIdx, staminaStat.getMax());
                }
            }

            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null) {
                InteractionManager im = new InteractionManager(player, playerRef, new InteractionSimulationHandler());
                var type = InteractionModule.get().getInteractionManagerComponent();
                if (commandBuffer != null) {
                    commandBuffer.putComponent(ref, type, im);
                } else {
                    store.putComponent(ref, type, im);
                }
            }
            DeathMovementController.restore(store, ref, playerRef);

            // Reset armor charge if component already present (added by HolderSystem at spawn)
            ArmorChargeComponent charge = store.getComponent(ref, ArmorChargeComponent.getComponentType());
            if (charge != null) {
                charge.reset();
            }
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Failed to reset player status", e);
        }

        restoreDefaultMovementSettings(ref, store);
    }

    // ────────────────────────────────────────────────
    //  Movement settings
    // ────────────────────────────────────────────────

    private void applyDungeonMovementSettings(@Nonnull Ref<EntityStore> ref,
                                               @Nonnull Store<EntityStore> store,
                                               @Nonnull PlayerRef playerRef) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) return;

        MovementSettings s = movementManager.getSettings();

        // --- Core speed: ~80% faster base, fast-paced dungeon crawler ---
        s.baseSpeed = 10.0f;                         // default 5.5

        // --- Acceleration: snappy direction changes ---
        s.acceleration = 0.22f;                      // default 0.1
        s.velocityResistance = 0.14f;                // default 0.242 — lower = more momentum

        // --- Auto-climb: seamless, no slowdown ---
        s.autoJumpObstacleMaxAngle = 180.0f;         // all directions
        s.autoJumpObstacleSpeedLoss = 1.0f;          // default 0.95 — 1.0 = no loss
        s.autoJumpObstacleSprintSpeedLoss = 1.0f;    // default 0.75
        s.autoJumpObstacleEffectDuration = 0.0f;     // default 0.2
        s.autoJumpObstacleSprintEffectDuration = 0.0f; // default 0.1

        // --- Jump: disabled — replaced by roll system ---
        s.jumpForce = 0.0f;                          // no vertical jump
        s.jumpBufferDuration = 0.0f;
        s.variableJumpFallForce = 28.0f;             // default 35 — less punishing falls

        // --- Air control: full authority mid-air ---
        s.airControlMaxMultiplier = 5.0f;            // default 3.13
        s.airControlMaxSpeed = 5.0f;                 // default 3
        s.airFrictionMin = 0.01f;                    // default 0.02
        s.airFrictionMax = 0.02f;                    // default 0.045
        s.airSpeedMultiplier = 1.2f;                 // default 1.0

        // --- Fall: no punishment in top-down view ---
        s.fallEffectDuration = 0.0f;                 // default 0.0 (already fine)
        s.fallMomentumLoss = 0.0f;                   // default 0.1 — no landing stagger
        s.fallJumpForce = s.jumpForce;               // default 7 — allow full jump after landing

        // --- Slide/roll: faster combat dodging ---
        s.minSlideEntrySpeed = 5.5f;                 // default 6.99 — easier to trigger
        s.slideExitSpeed = 3.5f;                     // default 2.5 — keep momentum out of slide
        s.rollTimeToComplete = 0.2f;                 // default — roll is fully custom, not using built-in
        s.rollStartSpeedModifier = 4.0f;             // default 3.5
        s.rollExitSpeedModifier = 3.0f;              // default 2.2

        // --- Collision: slightly stronger push-out to avoid getting stuck ---
        s.collisionExpulsionForce = 0.04f;           // default 0.02

        movementManager.update(playerRef.getPacketHandler());
    }

    private void restoreDefaultMovementSettings(@Nonnull Ref<EntityStore> ref,
                                                 @Nonnull Store<EntityStore> store) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) return;

        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        movementManager.applyDefaultSettings();
        movementManager.update(playerRef.getPacketHandler());
    }

    // ────────────────────────────────────────────────
    //  Map
    // ────────────────────────────────────────────────

    private void enableMap(@Nonnull PlayerRef playerRef) {
        UpdateWorldMapSettings mapSettings = new UpdateWorldMapSettings();
        mapSettings.enabled = true;
        mapSettings.defaultScale = 16.0f;
        mapSettings.minScale = 4.0f;
        mapSettings.maxScale = 128.0f;
        playerRef.getPacketHandler().writeNoCache(mapSettings);
    }

    // ────────────────────────────────────────────────
    //  Equipment
    // ────────────────────────────────────────────────

    private void giveStartEquipment(@Nonnull PlayerRef playerRef, @Nonnull Player player, @Nonnull DungeonConfig config) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null) return;
        Store<EntityStore> store = ref.getStore();

        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp == null) return;

        List<String> equipment = config.getStartEquipment();
        ItemContainer hotbar = hotbarComp.getInventory();
        if (hotbar.getCapacity() <= 0) {
            return;
        }

        byte preferredSlot = getPreferredHotbarSlot(hotbarComp);
        boolean placedPrimaryItem = false;
        short nextFallbackSlot = 0;

        for (String itemId : equipment) {
            if (itemId == null || itemId.isBlank()) continue;

            short targetSlot;
            if (!placedPrimaryItem) {
                targetSlot = (short) preferredSlot;
                placedPrimaryItem = true;
            } else {
                while (nextFallbackSlot < hotbar.getCapacity() && nextFallbackSlot == preferredSlot) {
                    nextFallbackSlot++;
                }
                if (nextFallbackSlot >= hotbar.getCapacity()) {
                    break;
                }
                targetSlot = nextFallbackSlot++;
            }

            try {
                ItemStack item = createStartEquipmentItem(itemId);
                if (item == null) {
                    LOGGER.warning("Start equipment item creation returned null: " + itemId);
                    continue;
                }
                hotbar.setItemStackForSlot(targetSlot, item, false);
            } catch (Exception e) {
                LOGGER.warning("Failed to give start equipment item: " + itemId + " - " + e.getMessage());
            }
        }

        if (hotbarComp.getActiveSlot() != preferredSlot) {
            hotbarComp.setActiveSlot(preferredSlot);
        }

        syncInventoryAndSelectedSlots(playerRef, ref, store);
    }

    @Nullable
    private ItemStack createStartEquipmentItem(@Nonnull String itemId) {
        try {
            return GunItemMetadata.initializeFullAmmo(new ItemStack(itemId, 1));
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Invalid start equipment item id: " + itemId);
            return null;
        }
    }

    private byte getPreferredHotbarSlot(@Nonnull InventoryComponent.Hotbar hotbarComp) {
        byte activeSlot = hotbarComp.getActiveSlot();
        if (activeSlot >= 0 && activeSlot < InventoryLockService.MAX_WEAPON_SLOTS) {
            return activeSlot;
        }
        return 0;
    }

    private void syncInventoryAndSelectedSlots(@Nullable PlayerRef playerRef,
                                                  @Nonnull Ref<EntityStore> ref,
                                                  @Nonnull Store<EntityStore> store) {
        if (playerRef != null && playerRef.isValid()) {
            InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
            InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
            InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
            InventoryComponent.Tool toolComp = store.getComponent(ref, InventoryComponent.Tool.getComponentType());
            InventoryComponent.Backpack backpackComp = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());

            playerRef.getPacketHandler().writeNoCache(new UpdatePlayerInventory(
                    storageComp != null ? storageComp.getInventory().toPacket() : null,
                    armorComp != null ? armorComp.getInventory().toPacket() : null,
                    hotbarComp != null ? hotbarComp.getInventory().toPacket() : null,
                    utilityComp != null ? utilityComp.getInventory().toPacket() : null,
                    toolComp != null ? toolComp.getInventory().toPacket() : null,
                    backpackComp != null ? backpackComp.getInventory().toPacket() : null
            ));
            playerRef.getPacketHandler().writeNoCache(new SetActiveSlot(-1,
                    hotbarComp != null ? hotbarComp.getActiveSlot() : (byte) 0));
        }
    }

    // ────────────────────────────────────────────────
    //  Mob spawning
    // ────────────────────────────────────────────────

    /** Max distance (squared) between two spawn points to be in the same cluster. */
    private static final double CLUSTER_RADIUS_SQ = 10.0 * 10.0;
    /** Max random XZ offset from the cluster center when spawning a mob. */
    private static final double CLUSTER_SPREAD = 2.5;

    private void spawnLevelMobs(@Nonnull Level level, @Nonnull Store<EntityStore> store) {
        int totalSpawned = 0;
        Random random = new Random();

        for (RoomData room : level.getRooms()) {
            // Spawn pinned mobs at their exact block positions (from configured spawners).
            // Locked rooms defer pinned spawns until room entry.
            if (!room.isLocked()) {
                spawnPinnedMobs(room, store);
            }

            List<String> mobs = room.getMobsToSpawn();
            List<Vector3i> allPoints = room.getMobSpawnPoints();
            if (mobs.isEmpty() || allPoints.isEmpty()) continue;

            room.setExpectedMobCount(mobs.size());

            // Randomly pick which spawn points to use (as many as there are mobs)
            List<Vector3i> chosen = new ArrayList<>(allPoints);
            java.util.Collections.shuffle(chosen, random);
            if (chosen.size() > mobs.size()) {
                chosen = chosen.subList(0, mobs.size());
            }

            // Group chosen points into spatial clusters so mobs form packs
            List<List<Integer>> clusters = buildClusters(chosen);

            // Spawn mobs at cluster centers with small offsets
            int mobIndex = 0;
            for (List<Integer> cluster : clusters) {
                // Compute cluster center
                double cx = 0, cy = 0, cz = 0;
                for (int idx : cluster) {
                    Vector3i sp = chosen.get(idx);
                    cx += sp.x;
                    cy += sp.y;
                    cz += sp.z;
                }
                cx /= cluster.size();
                cy /= cluster.size();
                cz /= cluster.size();

                // Spawn one mob per chosen point in this cluster, offset around center
                for (int k = 0; k < cluster.size() && mobIndex < mobs.size(); k++, mobIndex++) {
                    String mobId = mobs.get(mobIndex);
                    if (mobId == null || mobId.isBlank()) continue;

                    double ox = (random.nextDouble() - 0.5) * 2.0 * CLUSTER_SPREAD;
                    double oz = (random.nextDouble() - 0.5) * 2.0 * CLUSTER_SPREAD;
                    Vector3d spawnPos = new Vector3d(cx + 0.5 + ox, cy + 1.0, cz + 0.5 + oz);

                    try {
                        var mobResult = NPCPlugin.get().spawnNPC(store, mobId, null, spawnPos, Rotation3f.ZERO);
                        Ref<EntityStore> mobRef = mobResult != null ? mobResult.first() : null;
                        if (mobRef != null) {
                            room.addSpawnedMob(mobRef);
                            totalSpawned++;
                        }
                    } catch (Exception e) {
                        LOGGER.log(java.util.logging.Level.WARNING, "Failed to spawn mob: " + mobId, e);
                    }
                }
            }
        }
        LOGGER.info("Spawned " + totalSpawned + " mobs for level " + level.getName());
    }

    /**
     * Spawn pinned mobs (from configured Shotcave_Mob_Spawner blocks) at their exact positions.
     */
    public void spawnPinnedMobs(@Nonnull RoomData room, @Nonnull Store<EntityStore> store) {
        List<RoomData.PinnedMobSpawn> pinned = room.getPinnedMobSpawns();
        if (pinned.isEmpty()) return;

        for (RoomData.PinnedMobSpawn p : pinned) {
            Vector3d spawnPos = new Vector3d(
                    p.position().x + 0.5, p.position().y + 1.0, p.position().z + 0.5);
            try {
                var mobResult = NPCPlugin.get().spawnNPC(store, p.mobId(), null, spawnPos, Rotation3f.ZERO);
                Ref<EntityStore> mobRef = mobResult != null ? mobResult.first() : null;
                if (mobRef != null) {
                    room.addSpawnedMob(mobRef);
                }
            } catch (Exception e) {
                LOGGER.log(java.util.logging.Level.WARNING, "Failed to spawn pinned mob: " + p.mobId(), e);
            }
        }
        room.setExpectedMobCount(room.getExpectedMobCount() + pinned.size());
    }

    /**
     * Groups spawn point indices into spatial clusters. Two points belong to the same
     * cluster if at least one existing member is within {@link #CLUSTER_RADIUS_SQ}.
     */
    @Nonnull
    private List<List<Integer>> buildClusters(@Nonnull List<Vector3i> points) {
        List<List<Integer>> clusters = new ArrayList<>();
        boolean[] assigned = new boolean[points.size()];

        for (int i = 0; i < points.size(); i++) {
            if (assigned[i]) continue;

            List<Integer> cluster = new ArrayList<>();
            cluster.add(i);
            assigned[i] = true;

            // Expand: check all unassigned points against any member of this cluster
            for (int c = 0; c < cluster.size(); c++) {
                Vector3i member = points.get(cluster.get(c));
                for (int j = 0; j < points.size(); j++) {
                    if (assigned[j]) continue;
                    Vector3i candidate = points.get(j);
                    double dx = member.x - candidate.x;
                    double dy = member.y - candidate.y;
                    double dz = member.z - candidate.z;
                    if (dx * dx + dy * dy + dz * dz <= CLUSTER_RADIUS_SQ) {
                        cluster.add(j);
                        assigned[j] = true;
                    }
                }
            }

            clusters.add(cluster);
        }
        return clusters;
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
                    world.setBlock(wx, anchor.y + dy, wz, "Empty", 0);
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.FINE, "Failed to unseal block at offset " + dx + "," + dy, e);
                }
            }
        }

        game.setBossRoomSealed(false);
        LOGGER.info("Boss room unsealed at " + anchor);
    }

    private static int rotateLocalX(int x, int z, int rotation) {
        return switch (rotation & 3) {
            case 1 -> z;
            case 2 -> -x;
            case 3 -> -z;
            default -> x;
        };
    }

    private static int rotateLocalZ(int x, int z, int rotation) {
        return switch (rotation & 3) {
            case 1 -> -x;
            case 2 -> -z;
            case 3 -> x;
            default -> z;
        };
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

    public void hideDungeonHuds(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        DungeonInfoHud.hideHud(player, playerRef);
        PartyStatusHud.hideHud(player, playerRef);
        DeathCountdownHud.hideHud(player, playerRef);
        ReviveProgressHud.hideHud(player, playerRef);
    }

    @Nonnull
    public Map<UUID, Game> getActiveGames() {
        return activeGames;
    }

    // ────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────

    @Nonnull
    private Path getPrimarySaveFilePath(@Nonnull UUID playerId) {
        return plugin.getDataDirectory()
                .toAbsolutePath()
                .normalize()
                .resolve("saves")
                .resolve(playerId.toString() + ".json");
    }

    @Nonnull
    private Path getUniverseSaveFilePath(@Nonnull UUID playerId) {
        Universe universe = Universe.get();
        Path root = universe != null
                ? universe.getPath().toAbsolutePath().normalize().resolve("Shotcave")
                : plugin.getDataDirectory().toAbsolutePath().normalize();
        return root.resolve("saves").resolve(playerId.toString() + ".json");
    }

    @Nonnull
    private Path getHomeSaveFilePath(@Nonnull UUID playerId) {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return getPrimarySaveFilePath(playerId);
        }

        return Path.of(userHome)
                .resolve(".shotcave")
                .resolve("saves")
                .resolve(playerId.toString() + ".json");
    }

    private boolean hasSavedInventoryFile(@Nonnull UUID playerId) {
        Path primary = getPrimarySaveFilePath(playerId);
        if (Files.exists(primary)) {
            return true;
        }

        Path universe = getUniverseSaveFilePath(playerId);
        if (!universe.equals(primary) && Files.exists(universe)) {
            return true;
        }

        Path home = getHomeSaveFilePath(playerId);
        return !home.equals(primary) && Files.exists(home);
    }

    @Nonnull
    private Path findExistingSaveFilePath(@Nonnull UUID playerId) {
        Path primary = getPrimarySaveFilePath(playerId);
        if (Files.exists(primary)) {
            return primary;
        }

        Path universe = getUniverseSaveFilePath(playerId);
        if (!universe.equals(primary) && Files.exists(universe)) {
            return universe;
        }

        Path home = getHomeSaveFilePath(playerId);
        if (!home.equals(primary) && Files.exists(home)) {
            return home;
        }

        return primary;
    }

    private void deleteSavedInventoryFiles(@Nonnull UUID playerId) {
        Path primary = getPrimarySaveFilePath(playerId);
        Path universe = getUniverseSaveFilePath(playerId);
        Path home = getHomeSaveFilePath(playerId);

        try {
            Files.deleteIfExists(primary);
            if (!universe.equals(primary)) {
                Files.deleteIfExists(universe);
            }
            if (!home.equals(primary)) {
                Files.deleteIfExists(home);
            }
        } catch (IOException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Failed to delete saved inventory for " + playerId, e);
        }
    }

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

    private void broadcastToParty(@Nonnull UUID partyId, @Nonnull String text, @Nonnull String color) {
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

                boolean hasSavedInventory = hasSavedInventory(playerId);
                boolean inventoryLocked = plugin.getInventoryLockService().isLocked(playerId);
                DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
                boolean customDead = deathComponent != null && deathComponent.isDead();

                if (game == null && !hasSavedInventory && !inventoryLocked && !customDead) {
                    return;
                }

                despawnReviveMarker(playerId);
                plugin.getCameraService().restoreDefault(playerRef);
                hideDungeonHuds(player, playerRef);
                plugin.getInventoryLockService().unlock(player, playerId);

                if (hasSavedInventory) {
                    restorePlayerInventory(playerId, player, false);
                }

                resetPlayerStatus(player, ref, store);

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
        reviveMarkers.clear();
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
                "All players have fallen! The dungeon run is over.", "#ff6b6b");
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
            despawnReviveMarker(playerId);

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
            resetPlayerStatus(player, ref, store);

            // Give back start equipment
            plugin.getInventoryLockService().unlock(player, playerId);
            clearPlayerInventory(player);
            giveStartEquipment(playerRef, player, config);
            plugin.getInventoryLockService().lock(player, playerId);
        }

        game.clearDeadPlayers();
        broadcastToParty(game.getPartyId(),
                "All fallen players have been revived!", "#a9f5b3");
    }

    /**
     * Clears the player's inventory and syncs the change to the client.
     * Called by death systems that don't have direct access to private helpers.
     */
    public void clearPlayerInventoryPublic(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        plugin.getInventoryLockService().unlock(player, playerRef.getUuid());
        clearPlayerInventory(player);
        Ref<EntityStore> ref = player.getReference();
        if (ref != null) {
            syncInventoryAndSelectedSlots(playerRef, ref, ref.getStore());
        }
    }

    /**
     * Public accessor for {@link #resetPlayerStatus} used by death/revive systems.
     */
    public void resetPlayerStatusPublic(@Nonnull Player player) {
        resetPlayerStatusPublic(player, null);
    }

    /**
     * Public accessor for {@link #resetPlayerStatus} used by death/revive systems.
     */
    public void resetPlayerStatusPublic(@Nonnull Player player,
                                          @Nullable CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = player.getReference();
        if (ref != null && ref.isValid()) {
            resetPlayerStatus(player, ref, ref.getStore(), commandBuffer);
        }
    }

    /**
     * Public accessor for {@link #broadcastToParty} used by death/revive systems.
     */
    public void broadcastToPartyPublic(@Nonnull UUID partyId, @Nonnull String text, @Nonnull String color) {
        broadcastToParty(partyId, text, color);
    }

    /**
     * Public accessor for {@link #giveStartEquipment} used by revive systems.
     */
    public void giveStartEquipmentPublic(@Nonnull PlayerRef playerRef, @Nonnull Player player,
                                          @Nonnull DungeonConfig config) {
        giveStartEquipment(playerRef, player, config);
        plugin.getInventoryLockService().lock(player, playerRef.getUuid());
    }

    public void spawnReviveMarker(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull Ref<EntityStore> deadPlayerRef,
                                  @Nonnull UUID playerId,
                                  @Nonnull Vector3d position) {
        despawnReviveMarker(commandBuffer, playerId);

        Holder<EntityStore> holder = store.getRegistry().newHolder();
        PlayerSkinComponent playerSkinComponent = store.getComponent(deadPlayerRef, PlayerSkinComponent.getComponentType());
        TransformComponent deadPlayerTransform = store.getComponent(deadPlayerRef, TransformComponent.getComponentType());
        holder.addComponent(TransformComponent.getComponentType(),
                new TransformComponent(new Vector3d(position), deadPlayerTransform != null ? deadPlayerTransform.getRotation().clone() : Rotation3f.ZERO));
        holder.addComponent(UUIDComponent.getComponentType(), UUIDComponent.randomUUID());
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(Nameplate.getComponentType(), new Nameplate("downed"));
        holder.addComponent(Intangible.getComponentType(), Intangible.INSTANCE);
        holder.addComponent(Invulnerable.getComponentType(), Invulnerable.INSTANCE);
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
        if (playerSkinComponent != null) {
            holder.addComponent(PlayerSkinComponent.getComponentType(),
                    new PlayerSkinComponent(playerSkinComponent.getPlayerSkin()));
        }
        Model markerModel = buildReviveMarkerModel(playerSkinComponent);
        if (markerModel != null) {
            holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(markerModel));
            holder.addComponent(PersistentModel.getComponentType(), new PersistentModel(markerModel.toReference()));
            if (markerModel.getFirstBoundAnimationId(REVIVE_MARKER_DOWN_ANIMATION) != null) {
                ActiveAnimationComponent activeAnimationComponent = new ActiveAnimationComponent();
                activeAnimationComponent.setPlayingAnimation(AnimationSlot.Status, REVIVE_MARKER_DOWN_ANIMATION);
                holder.addComponent(ActiveAnimationComponent.getComponentType(), activeAnimationComponent);
            }
        }

        Ref<EntityStore> markerRef = commandBuffer.addEntity(holder, AddReason.SPAWN);
        reviveMarkers.put(playerId, markerRef);
    }

    public void despawnReviveMarker(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull UUID playerId) {
        Ref<EntityStore> markerRef = reviveMarkers.remove(playerId);
        if (markerRef == null || !markerRef.isValid()) {
            return;
        }

        if (markerRef.getStore() == commandBuffer.getStore()) {
            commandBuffer.tryRemoveEntity(markerRef, RemoveReason.REMOVE);
            return;
        }

        removeReviveMarkerOnOwningWorld(markerRef);
    }

    public void despawnReviveMarker(@Nonnull UUID playerId) {
        Ref<EntityStore> markerRef = reviveMarkers.remove(playerId);
        if (markerRef == null || !markerRef.isValid()) {
            return;
        }

        removeReviveMarkerOnOwningWorld(markerRef);
    }

    @Nullable
    public Vector3d getReviveMarkerPosition(@Nonnull UUID playerId) {
        Ref<EntityStore> markerRef = reviveMarkers.get(playerId);
        if (markerRef == null || !markerRef.isValid()) {
            reviveMarkers.remove(playerId, markerRef);
            return null;
        }

        TransformComponent transform = markerRef.getStore().getComponent(markerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }

        return new Vector3d(transform.getPosition());
    }

    private void removeReviveMarkerOnOwningWorld(@Nonnull Ref<EntityStore> markerRef) {
        Store<EntityStore> markerStore = markerRef.getStore();
        markerStore.getExternalData().getWorld().execute(() -> {
            if (markerRef.isValid()) {
                markerStore.removeEntity(markerRef, RemoveReason.REMOVE);
            }
        });
    }

    @Nullable
    private Model buildReviveMarkerModel(@Nullable PlayerSkinComponent playerSkinComponent) {
        if (playerSkinComponent != null) {
            Model playerModel = CosmeticsModule.get().createModel(playerSkinComponent.getPlayerSkin());
            if (playerModel != null) {
                return playerModel;
            }
        }

        ModelAsset markerModelAsset = ModelAsset.getAssetMap().getAsset(REVIVE_MARKER_MODEL_ID);
        return markerModelAsset != null ? Model.createUnitScaleModel(markerModelAsset) : null;
    }

    // ────────────────────────────────────────────────
    //  Inventory save data structure
    // ────────────────────────────────────────────────

    private static final class InventorySaveData {
        SavedItemStack[] hotbarItems;
        SavedItemStack[] storageItems;
        SavedItemStack[] armorItems;
        SavedItemStack[] utilityItems;
        byte activeHotbarSlot;
    }

    private static final class SavedItemStack {
        String itemId;
        int quantity;
        double durability;
        double maxDurability;
        boolean overrideDroppedItemAnimation;
        String metadataJson;
    }

    private static final class LoadedInventorySave {
        final String source;
        final InventorySaveData data;

        private LoadedInventorySave(@Nonnull String source, @Nonnull InventorySaveData data) {
            this.source = source;
            this.data = data;
        }
    }
}
