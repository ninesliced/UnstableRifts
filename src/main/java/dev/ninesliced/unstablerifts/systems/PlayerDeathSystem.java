package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.dungeon.DungeonConstants;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.dungeon.GameManager;
import dev.ninesliced.unstablerifts.dungeon.GameState;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Safety-net ticking system for custom dungeon death handling.
 *
 * <p>Lethal hits are intercepted earlier by {@link DungeonLethalDamageSystem},
 * but this system still catches any player that reaches the custom death
 * threshold through another path and routes them into the same dungeon death
 * flow.</p>
 */
public final class PlayerDeathSystem extends EntityTickingSystem<EntityStore> {

    static final float HEALTH_FLOOR = 1.0f;

    static boolean handleDungeonDeath(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                      @Nonnull Store<EntityStore> store,
                                      @Nonnull Ref<EntityStore> ref,
                                      @Nonnull PlayerRef playerRef,
                                      @Nonnull Player player,
                                      @Nonnull DeathComponent death) {
        UnstableRifts unstablerifts = UnstableRifts.getInstance();
        if (unstablerifts == null) return false;

        GameManager gameManager = unstablerifts.getGameManager();
        Game game = gameManager.findGameForPlayer(playerRef.getUuid());
        if (game == null) return false;
        if (game.getState() != GameState.ACTIVE && game.getState() != GameState.BOSS) return false;

        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) return false;

        int healthIdx = DefaultEntityStatTypes.getHealth();
        EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
        if (healthStat == null) return false;

        float maxHealth = healthStat.getMax();
        if (healthStat.get() < maxHealth) {
            statMap.setStatValue(healthIdx, maxHealth);
        }

        if (death.isDead()) return true;

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        Vector3d deathPos = transform != null
                ? new Vector3d(transform.getPosition())
                : new Vector3d(0, 0, 0);

        death.markDead(deathPos);
        gameManager.getReviveMarkerService().spawnReviveMarker(commandBuffer, store, ref, playerRef.getUuid(), deathPos);
        DeathStateController.apply(commandBuffer, store, ref, false);
        game.addDeadPlayer(playerRef.getUuid());
        gameManager.hideDeadPlayerFromParty(playerRef.getUuid(), game.getPartyId());
        gameManager.getInventoryService().saveAndClearDeathInventory(player, playerRef);

        if (game.areAllPlayersDead()) {
            commandBuffer.run(_store -> gameManager.onAllPlayersDead(game));
            return true;
        }

        String playerName = player.getDisplayName() != null
                ? player.getDisplayName()
                : playerRef.getUuid().toString();
        gameManager.broadcastToParty(game.getPartyId(),
                playerName + " has fallen! Hold the interaction key near them within 30 seconds to revive them!", DungeonConstants.COLOR_DANGER);
        return true;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(
                PlayerRef.getComponentType(),
                DeathComponent.getComponentType(),
                Player.getComponentType()
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

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (!ref.isValid()) return;

        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) return;

        int healthIdx = DefaultEntityStatTypes.getHealth();
        EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
        if (healthStat == null) return;

        float currentHealth = healthStat.get();

        if (death.isDead()) {
            float maxHealth = healthStat.getMax();
            if (currentHealth < maxHealth) {
                statMap.setStatValue(healthIdx, maxHealth);
            }
            return;
        }

        if (currentHealth < HEALTH_FLOOR) {
            statMap.setStatValue(healthIdx, HEALTH_FLOOR);
            currentHealth = HEALTH_FLOOR;
        }

        if (currentHealth > HEALTH_FLOOR) return;

        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        if (player == null) return;

        handleDungeonDeath(commandBuffer, store, ref, playerRef, player, death);
    }
}
