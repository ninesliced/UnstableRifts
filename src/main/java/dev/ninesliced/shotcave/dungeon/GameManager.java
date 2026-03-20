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
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.HiddenFromAdventurePlayers;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import dev.ninesliced.shotcave.Shotcave;
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
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

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
            PlayerRef ref = memberRefs.get(memberId);
            if (ref != null) {
                Ref<EntityStore> entityRef = memberEntities.get(memberId);
                Store<EntityStore> store = memberStores.get(memberId);
                if (entityRef != null && store != null) {
                    game.getReturnPoints().put(memberId, DungeonInstanceService.captureReturnPoint(store, entityRef));
                    game.getReturnWorlds().put(memberId, store.getExternalData().getWorld());
                }
            }
        }

        DungeonConfig.LevelConfig firstLevelConfig = config.getLevels().get(0);
        Level firstLevel = new Level(firstLevelConfig.getName(), 0);
        game.addLevel(firstLevel);

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
        DungeonConfig.LevelConfig levelConfig = resolveLevelConfig(config, game.getCurrentLevelIndex());

        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();

            sealBossRoom(game, bossRoom, world);

            if (levelConfig != null && levelConfig.getBossMob() != null && !levelConfig.getBossMob().isBlank()) {
                Vector3i anchor = bossRoom.getAnchor();
                Vector3d bossPos = new Vector3d(anchor.x + 5, anchor.y + 1, anchor.z + 5);
                try {
                    var bossNpcResult = NPCPlugin.get().spawnNPC(store, levelConfig.getBossMob(), null, bossPos, Vector3f.ZERO);
                    Ref<EntityStore> bossRef = bossNpcResult != null ? bossNpcResult.first() : null;
                    if (bossRef != null) {
                        bossRoom.addSpawnedMob(bossRef);
                        LOGGER.info("Boss spawned: " + levelConfig.getBossMob() + " at " + bossPos);
                    }
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.WARNING, "Failed to spawn boss", e);
                }
            }

            broadcastToParty(game.getPartyId(), "⚔ BOSS FIGHT! Defeat the boss to advance!", "#ff6b6b");
        });
    }

    /**
     * Called when the boss is defeated.
     */
    public void onBossDefeated(@Nonnull Game game) {
        if (game.getState() != GameState.BOSS) return;

        Level level = game.getCurrentLevel();
        if (level == null) return;
        RoomData bossRoom = level.getBossRoom();

        World world = game.getInstanceWorld();
        if (world == null) return;

        world.execute(() -> {
            unsealBossRoom(game, bossRoom, world);

            // Revive all dead/ghost players on level completion
            reviveAllDeadPlayers(game);

            if (game.hasNextLevel()) {
                broadcastToParty(game.getPartyId(), "Boss defeated! Advancing to the next level...", "#a9f5b3");

                int nextIndex = game.getCurrentLevelIndex() + 1;
                DungeonConfig config = plugin.loadDungeonConfig();
                if (nextIndex < config.getLevels().size()) {
                    DungeonConfig.LevelConfig nextLevelConfig = config.getLevels().get(nextIndex);
                    Level nextLevel = new Level(nextLevelConfig.getName(), nextIndex);
                    game.addLevel(nextLevel);
                    game.setCurrentLevelIndex(nextIndex);
                    game.setState(GameState.ACTIVE);

                    Store<EntityStore> store = world.getEntityStore().getStore();
                    spawnLevelMobs(nextLevel, store);
                }
            } else {
                broadcastToParty(game.getPartyId(), "🎉 Dungeon Complete! Congratulations!", "#ffd700");
                endGame(game, false);
            }
        });
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

            // Immediately reset death state and remove Invulnerable/Intangible
            // BEFORE deferring the rest to world.execute(). This prevents
            // ReviveTickSystem from re-applying them on the next tick.
            DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
            if (deathComponent != null) {
                deathComponent.reset();
            }
            DeathStateController.clear(store, ref);

            plugin.getCameraService().restoreDefault(playerRef);

            store.getExternalData().getWorld().execute(() -> {
                if (!ref.isValid()) {
                    return;
                }

                Player player = store.getComponent(ref, Player.getComponentType());
                if (player == null) {
                    plugin.getInventoryLockService().remove(playerId);
                    game.setPlayerInInstance(playerId, false);
                    return;
                }

                hideDungeonHuds(player, playerRef);

                plugin.getInventoryLockService().unlock(player, playerId);
                if (game.isPlayerInInstance(playerId)) {
                    restorePlayerInventory(playerId, player, true);
                } else {
                    deleteSavedInventory(playerId);
                }

                resetPlayerStatus(player, ref, store);
                game.setPlayerInInstance(playerId, false);

                if (returnPoint != null) {
                    try {
                        World returnWorld = Universe.get().getDefaultWorld();
                        if (returnWorld != null) {
                            InstancesPlugin.teleportPlayerToInstance(ref, store, returnWorld, returnPoint);
                        }
                    } catch (Exception e) {
                        LOGGER.log(java.util.logging.Level.WARNING, "Failed to teleport player " + playerId + " back", e);
                    }
                }
            });
        }

        activeGames.remove(game.getPartyId());

        if (forced) {
            broadcastToParty(game.getPartyId(), "The dungeon run was cancelled.", "#ffb0b0");
        }

        World instanceWorld = game.getInstanceWorld();
        if (instanceWorld != null) {
            InstancesPlugin.safeRemoveInstance(instanceWorld);
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
        game.setPlayerInInstance(playerId, false);

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
                        restorePlayerInventory(playerId, player, true);
                        resetPlayerStatus(player, ref, store);

                        if (returnPoint != null) {
                            try {
                                World returnWorld = trackedReturnWorld;
                                if (returnWorld == null) {
                                    returnWorld = Universe.get().getDefaultWorld();
                                }
                                if (returnWorld != null && ref.isValid()) {
                                    InstancesPlugin.teleportPlayerToInstance(ref, store, returnWorld, returnPoint);
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

        Transform returnPoint = game.getReturnPoints().get(playerId);
        if (returnPoint == null) {
            returnPoint = DungeonInstanceService.captureReturnPoint(store, ref);
        }

        plugin.getCameraService().scheduleEnableOnNextReady(playerRef);
        plugin.getDungeonInstanceService().sendPlayerToReadyInstance(
                ref,
                store,
                CompletableFuture.completedFuture(instanceWorld),
                returnPoint,
                status -> {
                    plugin.getCameraService().cancelDeferredEnable(playerRef);
                    playerRef.sendMessage(PartyManager.partyPrefix().insert(Message.raw(status).color("#ffb0b0")));
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
        Path savePath = getSaveFilePath(playerId);

        if (Files.exists(savePath)) {
            LOGGER.info("Found saved inventory for " + playerRef.getUsername() + ", scheduling restore...");
            Game activeGame = findGameForPlayer(playerId);

            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                Store<EntityStore> store = ref.getStore();
                store.getExternalData().getWorld().execute(() -> {
                    if (!ref.isValid()) return;
                    Player player = store.getComponent(ref, Player.getComponentType());
                    if (player != null) {
                        restorePlayerInventory(playerId, player, activeGame == null);
                        if (activeGame != null) {
                            activeGame.setPlayerInInstance(playerId, false);
                        }
                        playerRef.sendMessage(
                                PartyManager.partyPrefix()
                                        .insert(Message.raw("Your inventory has been restored from a previous dungeon run.").color("#a9f5b3"))
                        );
                    }
                });
            }
        }
    }

    public void onPlayerAddedToWorld(@Nonnull PlayerRef playerRef, @Nonnull Player player, @Nonnull World world) {
        Game game = findGameForPlayer(playerRef.getUuid());

        if (game != null && game.getState() != GameState.COMPLETE && world != game.getInstanceWorld()) {
            if (hasSavedInventory(playerRef.getUuid())) {
                restorePlayerInventory(playerRef.getUuid(), player, false);
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref != null && ref.isValid()) {
                    resetPlayerStatus(player, ref, ref.getStore());
                }
                LOGGER.info("Restored inventory for " + playerRef.getUsername() + " upon arriving in non-dungeon world.");
            }
            return;
        }

        if (game == null || game.getState() == GameState.COMPLETE || world != game.getInstanceWorld()) {
            return;
        }
        if (game.isPlayerInInstance(playerRef.getUuid()) || !hasSavedInventory(playerRef.getUuid())) {
            return;
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        DungeonConfig config = plugin.loadDungeonConfig();
        preparePlayerForDungeon(game, playerRef.getUuid(), playerRef, player, ref, store, config);
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

    public void onInstanceWorldRemoved(@Nonnull World world) {
        List<Game> removedGames = activeGames.values().stream()
                .filter(game -> game.getInstanceWorld() == world)
                .toList();

        for (Game game : removedGames) {
            LOGGER.warning("Dungeon instance world was removed for party " + game.getPartyId() + ". Ending the run and closing the party.");
            game.setInstanceWorld(null);
            endGame(game, true);
        }
    }

    @Nonnull
    public List<Ref<EntityStore>> getDeadPlayerRefsInStore(@Nonnull Store<EntityStore> store) {
        List<Ref<EntityStore>> deadPlayerRefs = new ArrayList<>();

        for (Game game : activeGames.values()) {
            if (game.getState() != GameState.ACTIVE && game.getState() != GameState.BOSS) {
                continue;
            }

            for (UUID deadPlayerId : game.getDeadPlayers()) {
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

    @SuppressWarnings("removal")
    private void savePlayerInventory(@Nonnull UUID playerId, @Nonnull Player player) {
        Inventory inventory = player.getInventory();
        if (inventory == null) return;

        Path savePath = getSaveFilePath(playerId);
        try {
            Files.createDirectories(savePath.getParent());

            InventorySaveData saveData = new InventorySaveData();
            saveData.hotbarItems = serializeContainer(inventory.getHotbar());
            saveData.storageItems = serializeContainer(inventory.getStorage());
            saveData.armorItems = serializeContainer(inventory.getArmor());
            saveData.utilityItems = serializeContainer(inventory.getUtility());
            saveData.activeHotbarSlot = inventory.getActiveHotbarSlot();

            String json = GSON.toJson(saveData);
            Files.writeString(savePath, json, StandardCharsets.UTF_8);

            Game game = findGameForPlayer(playerId);
            if (game != null) {
                game.getSavedInventoryPaths().put(playerId, savePath.toString());
            }

            LOGGER.info("Saved inventory for player " + playerId + " to " + savePath);
        } catch (IOException e) {
            LOGGER.log(java.util.logging.Level.SEVERE, "CRITICAL: Failed to save inventory for " + playerId, e);
        }
    }

    @SuppressWarnings("removal")
    private void restorePlayerInventory(@Nonnull UUID playerId, @Nonnull Player player, boolean deleteAfterRestore) {
        Path savePath = getSaveFilePath(playerId);
        if (!Files.exists(savePath)) {
            LOGGER.warning("No saved inventory found for " + playerId);
            return;
        }

        try {
            String json = Files.readString(savePath, StandardCharsets.UTF_8);
            InventorySaveData saveData = GSON.fromJson(json, InventorySaveData.class);
            PlayerRef playerRef = Universe.get().getPlayer(playerId);

            Inventory inventory = player.getInventory();
            if (inventory == null) return;

            clearPlayerInventory(player);

            restoreContainer(inventory.getHotbar(), saveData.hotbarItems);
            restoreContainer(inventory.getStorage(), saveData.storageItems);
            restoreContainer(inventory.getArmor(), saveData.armorItems);
            restoreContainer(inventory.getUtility(), saveData.utilityItems);

            if (saveData.activeHotbarSlot >= 0 && saveData.activeHotbarSlot < inventory.getHotbar().getCapacity()) {
                Ref<EntityStore> ref = player.getReference();
                if (ref != null) {
                    inventory.setActiveHotbarSlot(ref, saveData.activeHotbarSlot, ref.getStore());
                }
            }

            syncInventoryAndSelectedSlots(playerRef, inventory);

            if (deleteAfterRestore) {
                Files.deleteIfExists(savePath);
            }

            LOGGER.info("Restored inventory for player " + playerId);
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
        if (!hasSavedInventory(playerId)) {
            savePlayerInventory(playerId, player);
        }
        clearPlayerInventory(player);
        resetPlayerStatus(player, ref, store);
        giveStartEquipment(playerRef, player, config);
        plugin.getInventoryLockService().lock(player, playerId);
        plugin.getCameraService().setEnabled(playerRef, true);
        applyDungeonMovementSettings(ref, store, playerRef);
        enableMap(playerRef);
        game.setPlayerInInstance(playerId, true);
    }

    private boolean hasSavedInventory(@Nonnull UUID playerId) {
        return Files.exists(getSaveFilePath(playerId));
    }

    private void deleteSavedInventory(@Nonnull UUID playerId) {
        try {
            Files.deleteIfExists(getSaveFilePath(playerId));
        } catch (IOException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Failed to delete saved inventory for " + playerId, e);
        }
    }

    @SuppressWarnings("removal")
    private void clearPlayerInventory(@Nonnull Player player) {
        Inventory inventory = player.getInventory();
        if (inventory == null) return;

        clearContainer(inventory.getHotbar());
        clearContainer(inventory.getStorage());
        clearContainer(inventory.getArmor());
        clearContainer(inventory.getUtility());
    }

    private void clearContainer(@Nonnull ItemContainer container) {
        for (short i = 0; i < container.getCapacity(); i++) {
            container.removeItemStackFromSlot(i);
        }
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
                    container.setItemStackForSlot(i, restored);
                } catch (Exception e) {
                    LOGGER.warning("Failed to restore item '" + saved.itemId + "' at slot " + i);
                }
            }
        }
    }

    // ────────────────────────────────────────────────
    //  Player status reset
    // ────────────────────────────────────────────────

    private void resetPlayerStatus(@Nonnull Player player, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            DeathComponent deathComponent = store.getComponent(ref, DeathComponent.getComponentType());
            if (deathComponent != null) {
                deathComponent.reset();
            }

            DeathStateController.clear(store, ref);

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
                store.putComponent(
                        ref,
                        InteractionModule.get().getInteractionManagerComponent(),
                        new InteractionManager(player, playerRef, new InteractionSimulationHandler())
                );
            }
            DeathMovementController.restore(store, ref, playerRef);
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

    @SuppressWarnings("removal")
    private void giveStartEquipment(@Nonnull PlayerRef playerRef, @Nonnull Player player, @Nonnull DungeonConfig config) {
        Inventory inventory = player.getInventory();
        if (inventory == null) return;

        List<String> equipment = config.getStartEquipment();
        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null || hotbar.getCapacity() <= 0) {
            return;
        }

        byte preferredSlot = getPreferredHotbarSlot(inventory, hotbar);
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
                hotbar.setItemStackForSlot(targetSlot, InventoryHelper.createItem(itemId));
            } catch (Exception e) {
                LOGGER.warning("Failed to give start equipment item: " + itemId);
            }
        }

        if (inventory.getActiveHotbarSlot() != preferredSlot) {
            Ref<EntityStore> ref = player.getReference();
            if (ref != null) {
                inventory.setActiveHotbarSlot(ref, preferredSlot, ref.getStore());
            }
        }

        syncInventoryAndSelectedSlots(playerRef, inventory);
    }

    @SuppressWarnings("removal")
    private byte getPreferredHotbarSlot(@Nonnull Inventory inventory, @Nonnull ItemContainer hotbar) {
        byte activeSlot = inventory.getActiveHotbarSlot();
        if (activeSlot >= 0 && activeSlot < InventoryLockService.MAX_WEAPON_SLOTS) {
            return activeSlot;
        }
        return 0;
    }

    @SuppressWarnings("removal")
    private void syncInventoryAndSelectedSlots(@Nullable PlayerRef playerRef, @Nonnull Inventory inventory) {
        if (playerRef != null && playerRef.isValid()) {
            playerRef.getPacketHandler().writeNoCache(new UpdatePlayerInventory(
                    inventory.getStorage() != null ? inventory.getStorage().toPacket() : null,
                    inventory.getArmor() != null ? inventory.getArmor().toPacket() : null,
                    inventory.getHotbar() != null ? inventory.getHotbar().toPacket() : null,
                    inventory.getUtility() != null ? inventory.getUtility().toPacket() : null,
                    inventory.getTools() != null ? inventory.getTools().toPacket() : null,
                    inventory.getBackpack() != null ? inventory.getBackpack().toPacket() : null
            ));
            playerRef.getPacketHandler().writeNoCache(new SetActiveSlot(-1, inventory.getActiveHotbarSlot()));
        }
    }

    // ────────────────────────────────────────────────
    //  Mob spawning
    // ────────────────────────────────────────────────

    private void spawnLevelMobs(@Nonnull Level level, @Nonnull Store<EntityStore> store) {
        int totalSpawned = 0;
        for (RoomData room : level.getRooms()) {
            if (room.getType() == RoomType.BOSS) {
                continue;
            }

            List<String> mobs = room.getMobsToSpawn();
            List<Vector3i> spawners = room.getSpawnerPositions();

            for (int i = 0; i < mobs.size(); i++) {
                String mobId = mobs.get(i);
                if (mobId == null || mobId.isBlank()) continue;

                Vector3d spawnPos;
                if (i < spawners.size()) {
                    Vector3i sp = spawners.get(i);
                    spawnPos = new Vector3d(sp.x + 0.5, sp.y + 1.0, sp.z + 0.5);
                } else {
                    Vector3i anchor = room.getAnchor();
                    spawnPos = new Vector3d(
                            anchor.x + 3 + (i % 5) * 2,
                            anchor.y + 1,
                            anchor.z + 3 + (i / 5) * 2
                    );
                }

                try {
                    var mobResult = NPCPlugin.get().spawnNPC(store, mobId, null, spawnPos, Vector3f.ZERO);
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
        LOGGER.info("Spawned " + totalSpawned + " mobs for level " + level.getName());
    }

    // ────────────────────────────────────────────────
    //  Boss room sealing
    // ────────────────────────────────────────────────

    private void sealBossRoom(@Nonnull Game game, @Nonnull RoomData bossRoom, @Nonnull World world) {
        if (game.isBossRoomSealed()) return;

        Vector3i anchor = bossRoom.getAnchor();
        DungeonConfig config = plugin.loadDungeonConfig();
        String wallBlock = config.getBossWallBlock();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                try {
                    world.setBlock(anchor.x + dx, anchor.y + dy, anchor.z - 1, wallBlock, 0);
                } catch (Exception ignored) {
                }
            }
        }

        game.setBossRoomSealed(true);
        LOGGER.info("Boss room sealed at " + anchor);
    }

    private void unsealBossRoom(@Nonnull Game game, @Nullable RoomData bossRoom, @Nonnull World world) {
        if (!game.isBossRoomSealed() || bossRoom == null) return;

        Vector3i anchor = bossRoom.getAnchor();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                try {
                    world.setBlock(anchor.x + dx, anchor.y + dy, anchor.z - 1, "Empty", 0);
                } catch (Exception ignored) {
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
    private Path getSaveFilePath(@Nonnull UUID playerId) {
        return plugin.getDataDirectory().resolve("saves").resolve(playerId.toString() + ".json");
    }

    @Nullable
    private DungeonConfig.LevelConfig resolveLevelConfig(@Nonnull DungeonConfig config, int levelIndex) {
        List<DungeonConfig.LevelConfig> levels = config.getLevels();
        return levelIndex >= 0 && levelIndex < levels.size() ? levels.get(levelIndex) : null;
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

        LOGGER.info("No players remain inside dungeon instance for party " + game.getPartyId() + ". Ending run.");
        endGame(game, true);
        return true;
    }

    private void broadcastToParty(@Nonnull UUID partyId, @Nonnull String text, @Nonnull String color) {
        Message message = PartyManager.partyPrefix().insert(Message.raw(text).color(color));
        for (Map.Entry<UUID, UUID> entry : playerToParty.entrySet()) {
            if (entry.getValue().equals(partyId)) {
                PlayerRef playerRef = Universe.get().getPlayer(entry.getKey());
                if (playerRef != null) {
                    playerRef.sendMessage(message);
                }
            }
        }
    }

    /**
     * Shutdown: clean up all active games, restore inventories.
     */
    public void shutdown() {
        for (Game game : new ArrayList<>(activeGames.values())) {
            if (game.getState() != GameState.COMPLETE) {
                LOGGER.info("Shutting down active game for party " + game.getPartyId());
                game.setState(GameState.COMPLETE);
            }
        }
        activeGames.clear();
        playerToParty.clear();
        reviveMarkers.clear();
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
        if (player.getInventory() != null) {
            syncInventoryAndSelectedSlots(playerRef, player.getInventory());
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
                new TransformComponent(position.clone(), deadPlayerTransform != null ? deadPlayerTransform.getRotation().clone() : Vector3f.ZERO));
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

        return transform.getPosition().clone();
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
}

