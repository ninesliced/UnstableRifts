package dev.ninesliced.shotcave.dungeon;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.hud.DungeonInfoHud;
import dev.ninesliced.shotcave.hud.PartyStatusHud;
import dev.ninesliced.shotcave.party.PartyManager;
import dev.ninesliced.shotcave.party.PartyUiPage;
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

    private final Shotcave plugin;
    /**
     * Active games indexed by party ID.
     */
    private final Map<UUID, Game> activeGames = new ConcurrentHashMap<>();
    /**
     * Reverse lookup: player UUID → party ID for fast game resolution.
     */
    private final Map<UUID, UUID> playerToParty = new ConcurrentHashMap<>();

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

        // Register all members
        for (UUID memberId : memberIds) {
            playerToParty.put(memberId, partyId);
            PlayerRef ref = memberRefs.get(memberId);
            if (ref != null) {
                Ref<EntityStore> entityRef = memberEntities.get(memberId);
                Store<EntityStore> store = memberStores.get(memberId);
                if (entityRef != null && store != null) {
                    game.getReturnPoints().put(memberId, DungeonInstanceService.captureReturnPoint(store, entityRef));
                }
            }
        }

        // Resolve level config for the first level
        DungeonConfig.LevelConfig firstLevelConfig = config.getLevels().get(0);
        Level firstLevel = new Level(firstLevelConfig.getName(), 0);
        game.addLevel(firstLevel);

        // Spawn instance and begin generation
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

            // Process each party member
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

            // Spawn all mobs in the current level
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

            // Seal the boss room
            sealBossRoom(game, bossRoom, world);

            // Spawn boss
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
            // Unseal the boss room
            unsealBossRoom(game, bossRoom, world);

            if (game.hasNextLevel()) {
                broadcastToParty(game.getPartyId(), "Boss defeated! Advancing to the next level...", "#a9f5b3");

                // Advance to next level
                int nextIndex = game.getCurrentLevelIndex() + 1;
                DungeonConfig config = plugin.loadDungeonConfig();
                if (nextIndex < config.getLevels().size()) {
                    DungeonConfig.LevelConfig nextLevelConfig = config.getLevels().get(nextIndex);
                    Level nextLevel = new Level(nextLevelConfig.getName(), nextIndex);
                    game.addLevel(nextLevel);
                    game.setCurrentLevelIndex(nextIndex);
                    game.setState(GameState.ACTIVE);

                    // Spawn mobs for the new level
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

        // Restore all tracked party members and teleport them back
        List<UUID> partyMembers = playerToParty.entrySet().stream()
                .filter(entry -> entry.getValue().equals(game.getPartyId()))
                .map(Map.Entry::getKey)
                .toList();

        for (UUID playerId : partyMembers) {
            PlayerRef playerRef = Universe.get().getPlayer(playerId);
            if (playerRef == null) continue;

            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) continue;

            Store<EntityStore> store = ref.getStore();
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) continue;

            plugin.getCameraService().restoreDefault(playerRef);
            hideDungeonHuds(player, playerRef);

            store.getExternalData().getWorld().execute(() -> {
                if (game.isPlayerInInstance(playerId)) {
                    restorePlayerInventory(playerId, player, true);
                    resetPlayerStatus(player, ref, store);
                } else {
                    deleteSavedInventory(playerId);
                }
                game.setPlayerInInstance(playerId, false);
            });

            // Teleport back to return point
            Transform returnPoint = game.getReturnPoints().get(playerId);
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
        }

        activeGames.remove(game.getPartyId());

        if (forced) {
            broadcastToParty(game.getPartyId(), "The dungeon run was cancelled.", "#ffb0b0");
        }

        // Clean up instance
        World instanceWorld = game.getInstanceWorld();
        if (instanceWorld != null) {
            InstancesPlugin.safeRemoveInstance(instanceWorld);
        }

        // Remove tracking
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

    /**
     * Handle player disconnect during an active game.
     */
    public void onPlayerDisconnect(@Nonnull UUID playerId) {
        PlayerRef playerRef = Universe.get().getPlayer(playerId);
        if (playerRef != null) {
            plugin.getCameraService().restoreDefault(playerRef);
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                Store<EntityStore> store = ref.getStore();
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) {
                    hideDungeonHuds(player, playerRef);
                }
            }
        }

        UUID partyId = playerToParty.get(playerId);
        if (partyId == null) return;

        Game game = activeGames.get(partyId);
        if (game == null) return;

        game.setPlayerInInstance(playerId, false);
        if (!closeGameIfInstanceEmpty(game)) {
            PartyUiPage.refreshOpenPages();
        }

        // Inventory is already saved to disk, so it's crash-safe
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

            // Defer restore until the player is fully loaded
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

        // ── Arriving at a NON-dungeon world with a saved inventory → restore it ──
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

        // ── Arriving at the dungeon world → prepare for dungeon ──
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

        // Best-effort camera / HUD cleanup
        plugin.getCameraService().restoreDefault(playerRef);
        hideDungeonHuds(player, playerRef);

        // Restore inventory using the Player component from the event holder (still valid during this event).
        restorePlayerInventory(playerRef.getUuid(), player, false);

        // Reset health/stamina only if the entity ref is still valid
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref != null && ref.isValid()) {
            Store<EntityStore> store = ref.getStore();
            resetPlayerStatus(player, ref, store);
        }

        // Always track instance membership and check for empty dungeon
        game.setPlayerInInstance(playerRef.getUuid(), false);
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

    // ────────────────────────────────────────────────
    //  Inventory save / restore
    // ────────────────────────────────────────────────

    private void savePlayerInventory(@Nonnull UUID playerId, @Nonnull Player player) {
        Inventory inventory = player.getInventory();
        if (inventory == null) return;

        Path savePath = getSaveFilePath(playerId);
        try {
            Files.createDirectories(savePath.getParent());

            // Serialize all containers to a simple JSON structure
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

            // Clear current inventory first
            clearPlayerInventory(player);

            // Restore items
            restoreContainer(inventory.getHotbar(), saveData.hotbarItems);
            restoreContainer(inventory.getStorage(), saveData.storageItems);
            restoreContainer(inventory.getArmor(), saveData.armorItems);
            restoreContainer(inventory.getUtility(), saveData.utilityItems);

            if (saveData.activeHotbarSlot >= 0 && saveData.activeHotbarSlot < inventory.getHotbar().getCapacity()) {
                inventory.setActiveHotbarSlot(saveData.activeHotbarSlot);
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
        plugin.getCameraService().setEnabled(playerRef, true);
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
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Failed to reset player status", e);
        }
    }

    // ────────────────────────────────────────────────
    //  Equipment
    // ────────────────────────────────────────────────

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
            inventory.setActiveHotbarSlot(preferredSlot);
        }

        syncInventoryAndSelectedSlots(playerRef, inventory);
    }

    private byte getPreferredHotbarSlot(@Nonnull Inventory inventory, @Nonnull ItemContainer hotbar) {
        byte activeSlot = inventory.getActiveHotbarSlot();
        if (activeSlot >= 0 && activeSlot < hotbar.getCapacity()) {
            return activeSlot;
        }
        return 0;
    }

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
                // Boss mobs are spawned separately during boss phase
                continue;
            }

            List<String> mobs = room.getMobsToSpawn();
            List<Vector3i> spawners = room.getSpawnerPositions();

            for (int i = 0; i < mobs.size(); i++) {
                String mobId = mobs.get(i);
                if (mobId == null || mobId.isBlank()) continue;

                // Use spawner positions if available, otherwise offset from anchor
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

        // Place wall blocks at the room entrance (approximate: a 5-wide, 4-tall wall at the entrance edge)
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
            case READY, ACTIVE, BOSS -> game.getInstanceWorld() != null;
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
        // Find party members from our tracking map
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
                // Inventories are already persisted to disk — they'll be restored on reconnect
                game.setState(GameState.COMPLETE);
            }
        }
        activeGames.clear();
        playerToParty.clear();
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

