package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.dungeon.DungeonConfig;
import dev.ninesliced.shotcave.dungeon.Game;
import dev.ninesliced.shotcave.dungeon.GameManager;
import dev.ninesliced.shotcave.dungeon.GameState;
import dev.ninesliced.shotcave.hud.DeathCountdownHud;
import dev.ninesliced.shotcave.hud.ReviveProgressHud;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ECS ticking system that handles:
 * <ul>
 *   <li>Leaving dead players in a free-moving non-interactive state</li>
 *   <li>Showing a death countdown HUD to dead players</li>
 *   <li>Transitioning dead players to ghost state after 30s</li>
 *   <li>Crouch-to-revive: alive players crouching at the death marker for 5s revive them</li>
 *   <li>Showing a revive progress HUD to the reviver</li>
 * </ul>
 * Runs every tick for smooth countdown and revive progress tracking.
 */
public final class ReviveTickSystem extends EntityTickingSystem<EntityStore> {

    private static final double REVIVE_RANGE = 2.0;
    private static final double REVIVE_RANGE_SQ = REVIVE_RANGE * REVIVE_RANGE;

    private static final long HUD_UPDATE_INTERVAL_MS = 200L;

    private final Map<UUID, Long> lastHudUpdateByPlayer = new ConcurrentHashMap<>();

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(
                PlayerRef.getComponentType(),
                DeathComponent.getComponentType(),
                TransformComponent.getComponentType()
        );
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef == null || !playerRef.isValid()) return;

        DeathComponent death = archetypeChunk.getComponent(index, DeathComponent.getComponentType());
        if (death == null) return;

        Shotcave shotcave = Shotcave.getInstance();
        if (shotcave == null) return;

        long now = System.currentTimeMillis();

        GameManager gameManager = shotcave.getGameManager();
        Game game = gameManager.findGameForPlayer(playerRef.getUuid());
        if (game == null) {
            lastHudUpdateByPlayer.remove(playerRef.getUuid());
            hideAllHuds(playerRef, archetypeChunk, index, store);
            return;
        }
        if (game.getState() != GameState.ACTIVE && game.getState() != GameState.BOSS) {
            lastHudUpdateByPlayer.remove(playerRef.getUuid());
            hideAllHuds(playerRef, archetypeChunk, index, store);
            return;
        }

        if (death.isDead()) {
            handleDeadPlayer(dt, index, archetypeChunk, store, commandBuffer, playerRef, death, game, gameManager, now);
        } else {
            handleAlivePlayer(dt, index, archetypeChunk, store, commandBuffer, playerRef, game, gameManager, now);
        }
    }

    private void handleDeadPlayer(float dt, int index,
                                   @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                   @Nonnull PlayerRef playerRef,
                                   @Nonnull DeathComponent death,
                                   @Nonnull Game game,
                                   @Nonnull GameManager gameManager,
                                   long now) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (ref.isValid()) {
            DeathStateController.apply(commandBuffer, store, ref, false);
        }

        if (death.isGhost()) {
            hideDeathCountdownHud(playerRef, archetypeChunk, index, store);
            return;
        }

        if (shouldRunHudUpdate(playerRef.getUuid(), now)) {
            Player player = archetypeChunk.getComponent(index, Player.getComponentType());
            if (player != null) {
                int remaining = Math.round(death.getReviveWindowRemainingSeconds());
                boolean beingRevived = death.getReviverUuid() != null && death.getReviveProgress() > 0;
                DeathCountdownHud hud = new DeathCountdownHud(playerRef, remaining,
                        beingRevived, death.getReviveProgress());
                DeathCountdownHud.applyHud(player, playerRef, hud);
            }
        }

        if (death.isReviveWindowExpired()) {
            death.markGhost();
            gameManager.despawnReviveMarker(commandBuffer, playerRef.getUuid());

            Player player = archetypeChunk.getComponent(index, Player.getComponentType());
            String playerName = player != null && player.getDisplayName() != null
                    ? player.getDisplayName()
                    : playerRef.getUuid().toString();

            Ref<EntityStore> entityref = archetypeChunk.getReferenceTo(index);
            if (entityref.isValid()) {
                setHealthToMax(store, entityref);
                DeathMovementController.restore(store, entityref, playerRef);
            }

            if (player != null) {
                DeathCountdownHud.hideHud(player, playerRef);
            }

            gameManager.broadcastToPartyPublic(game.getPartyId(),
                    playerName + " is now a ghost. They will be revived when the level is completed.", "#9ca3af");
        }
    }

    private void handleAlivePlayer(float dt, int index,
                                    @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                                    @Nonnull Store<EntityStore> store,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull PlayerRef playerRef,
                                    @Nonnull Game game,
                                    @Nonnull GameManager gameManager,
                                    long now) {
        MovementStatesComponent msc = archetypeChunk.getComponent(index,
                MovementStatesComponent.getComponentType());
        if (msc == null) return;

        MovementStates current = msc.getMovementStates();
        if (!current.crouching) {
            boolean cleared = tryExpireReviveProgress(playerRef.getUuid(), game);
            if (cleared) {
                hideReviveProgressHud(playerRef, archetypeChunk, index, store);
            }
            return;
        }

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (!ref.isValid()) return;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        Vector3d myPos = transform.getPosition();

        DeadPlayerInfo nearest = findNearestDeadPlayer(myPos, playerRef.getUuid(), game, gameManager);
        if (nearest == null) {
            boolean cleared = tryExpireReviveProgress(playerRef.getUuid(), game);
            if (cleared) {
                hideReviveProgressHud(playerRef, archetypeChunk, index, store);
            }
            return;
        }

        nearest.deathComponent.addReviveProgress(dt, playerRef.getUuid());

        if (shouldRunHudUpdate(playerRef.getUuid(), now)) {
            Player player = archetypeChunk.getComponent(index, Player.getComponentType());
            if (player != null) {
                String targetName = nearest.name != null ? nearest.name : "teammate";
                ReviveProgressHud hud = new ReviveProgressHud(playerRef, targetName,
                        nearest.deathComponent.getReviveProgress());
                ReviveProgressHud.applyHud(player, playerRef, hud);
            }
        }

        if (nearest.deathComponent.isReviveComplete()) {
            performRevive(nearest, playerRef, game, gameManager, commandBuffer);

            Player player = archetypeChunk.getComponent(index, Player.getComponentType());
            if (player != null) {
                ReviveProgressHud.hideHud(player, playerRef);
            }

            PlayerRef deadPlayerRef = Universe.get().getPlayer(nearest.uuid);
            if (deadPlayerRef != null && deadPlayerRef.isValid()) {
                Ref<EntityStore> dRef = deadPlayerRef.getReference();
                if (dRef != null && dRef.isValid()) {
                    Player deadPlayer = dRef.getStore().getComponent(dRef, Player.getComponentType());
                    if (deadPlayer != null) {
                        DeathCountdownHud.hideHud(deadPlayer, deadPlayerRef);
                    }
                }
            }
        }
    }

    private boolean shouldRunHudUpdate(@Nonnull UUID playerId, long now) {
        Long lastRun = lastHudUpdateByPlayer.get(playerId);
        if (lastRun != null && now - lastRun < HUD_UPDATE_INTERVAL_MS) {
            return false;
        }
        lastHudUpdateByPlayer.put(playerId, now);
        return true;
    }

    private void performRevive(@Nonnull DeadPlayerInfo deadInfo,
                                @Nonnull PlayerRef reviverRef,
                                @Nonnull Game game,
                                @Nonnull GameManager gameManager,
                                @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Vector3d revivePosition = gameManager.getReviveMarkerPosition(deadInfo.uuid);
        if (revivePosition == null) {
            revivePosition = deadInfo.deathComponent.getDeathPosition();
        }

        deadInfo.deathComponent.revive();
        game.removeDeadPlayer(deadInfo.uuid);
        gameManager.despawnReviveMarker(commandBuffer, deadInfo.uuid);
        DeathStateController.clear(commandBuffer, deadInfo.ref);

        if (deadInfo.ref != null && deadInfo.ref.isValid()) {
            TransformComponent transform = deadInfo.ref.getStore().getComponent(deadInfo.ref, TransformComponent.getComponentType());
            if (revivePosition != null) {
                Vector3f rotation = transform != null ? transform.getRotation().clone() : new Vector3f();
                commandBuffer.addComponent(
                        deadInfo.ref,
                        Teleport.getComponentType(),
                        Teleport.createForPlayer(revivePosition, rotation)
                );
            }
            setHealthToMax(deadInfo.ref.getStore(), deadInfo.ref);
        }

        PlayerRef deadPlayerRef = Universe.get().getPlayer(deadInfo.uuid);
        if (deadPlayerRef != null && deadPlayerRef.isValid()) {
            Ref<EntityStore> dRef = deadPlayerRef.getReference();
            if (dRef != null && dRef.isValid()) {
                Store<EntityStore> dStore = dRef.getStore();
                DeathMovementController.restore(dStore, dRef, deadPlayerRef);
                Player deadPlayer = dStore.getComponent(dRef, Player.getComponentType());
                if (deadPlayer != null) {
                    Shotcave shotcave = Shotcave.getInstance();
                    if (shotcave != null) {
                        DungeonConfig config = shotcave.loadDungeonConfig();
                        gameManager.giveStartEquipmentPublic(deadPlayerRef, deadPlayer, config);
                    }
                }
            }
        }

        Player reviverPlayer = null;
        Ref<EntityStore> reviverEntityRef = reviverRef.getReference();
        if (reviverEntityRef != null && reviverEntityRef.isValid()) {
            reviverPlayer = reviverEntityRef.getStore().getComponent(reviverEntityRef, Player.getComponentType());
        }
        String reviverName = reviverPlayer != null && reviverPlayer.getDisplayName() != null
                ? reviverPlayer.getDisplayName()
                : reviverRef.getUuid().toString();
        String deadName = deadInfo.name != null ? deadInfo.name : deadInfo.uuid.toString();

        gameManager.broadcastToPartyPublic(game.getPartyId(),
                deadName + " has been revived by " + reviverName + "!", "#a9f5b3");
    }

    private void hideAllHuds(@Nonnull PlayerRef playerRef,
                              @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                              int index,
                              @Nonnull Store<EntityStore> store) {
        hideDeathCountdownHud(playerRef, archetypeChunk, index, store);
        hideReviveProgressHud(playerRef, archetypeChunk, index, store);
    }

    private void hideDeathCountdownHud(@Nonnull PlayerRef playerRef,
                                        @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                                        int index,
                                        @Nonnull Store<EntityStore> store) {
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        if (player != null) {
            DeathCountdownHud.hideHud(player, playerRef);
        }
    }

    private void hideReviveProgressHud(@Nonnull PlayerRef playerRef,
                                        @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                                        int index,
                                        @Nonnull Store<EntityStore> store) {
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        if (player != null) {
            ReviveProgressHud.hideHud(player, playerRef);
        }
    }

    @Nullable
    private DeadPlayerInfo findNearestDeadPlayer(@Nonnull Vector3d fromPos,
                                                  @Nonnull UUID excludeUuid,
                                                  @Nonnull Game game,
                                                  @Nonnull GameManager gameManager) {
        Set<UUID> deadPlayers = game.getDeadPlayers();
        if (deadPlayers.isEmpty()) return null;

        DeadPlayerInfo nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (UUID deadUuid : deadPlayers) {
            if (deadUuid.equals(excludeUuid)) continue;

            PlayerRef deadRef = Universe.get().getPlayer(deadUuid);
            if (deadRef == null || !deadRef.isValid()) continue;

            Ref<EntityStore> ref = deadRef.getReference();
            if (ref == null || !ref.isValid()) continue;

            Store<EntityStore> deadStore = ref.getStore();

            DeathComponent deathComp = deadStore.getComponent(ref, DeathComponent.getComponentType());
            if (deathComp == null || !deathComp.isInReviveWindow()) continue;

            Vector3d deathPos = gameManager.getReviveMarkerPosition(deadUuid);
            if (deathPos == null) continue;

            double dx = fromPos.x - deathPos.x;
            double dy = fromPos.y - deathPos.y;
            double dz = fromPos.z - deathPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < REVIVE_RANGE_SQ && distSq < nearestDistSq) {
                Player deadPlayer = deadStore.getComponent(ref, Player.getComponentType());
                String name = deadPlayer != null ? deadPlayer.getDisplayName() : null;
                nearest = new DeadPlayerInfo(deadUuid, name, deathComp, ref);
                nearestDistSq = distSq;
            }
        }

        return nearest;
    }

    /**
     * Attempts to clear revive progress for any dead player being revived by the given player.
     * Respects the grace period so brief crouch interruptions don't reset progress.
     *
     * @return {@code true} if progress was actually cleared (grace period expired)
     */
    private boolean tryExpireReviveProgress(@Nonnull UUID reviverId, @Nonnull Game game) {
        boolean anyCleared = false;
        for (UUID deadUuid : game.getDeadPlayers()) {
            PlayerRef deadRef = Universe.get().getPlayer(deadUuid);
            if (deadRef == null || !deadRef.isValid()) continue;

            Ref<EntityStore> ref = deadRef.getReference();
            if (ref == null || !ref.isValid()) continue;

            DeathComponent deathComp = ref.getStore().getComponent(ref, DeathComponent.getComponentType());
            if (deathComp != null && reviverId.equals(deathComp.getReviverUuid())) {
                if (deathComp.clearReviveProgressIfExpired()) {
                    anyCleared = true;
                }
            }
        }
        return anyCleared;
    }

    private void setHealthToMax(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap != null) {
            int healthIdx = DefaultEntityStatTypes.getHealth();
            EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
            if (healthStat != null) {
                statMap.setStatValue(healthIdx, healthStat.getMax());
            }
        }
    }

    private record DeadPlayerInfo(
            @Nonnull UUID uuid,
            @Nullable String name,
            @Nonnull DeathComponent deathComponent,
            @Nonnull Ref<EntityStore> ref
    ) {
    }
}
