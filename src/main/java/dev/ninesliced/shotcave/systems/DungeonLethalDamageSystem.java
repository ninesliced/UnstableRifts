package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.dungeon.Game;
import dev.ninesliced.shotcave.dungeon.GameManager;
import dev.ninesliced.shotcave.dungeon.GameState;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Prevents lethal damage from entering the engine death pipeline for players
 * currently running a dungeon. This keeps the vanilla death screen from ever
 * being opened and immediately routes the player into Shotcave's custom death
 * state instead.
 */
public final class DungeonLethalDamageSystem extends DamageEventSystem {

    private static final Query<EntityStore> QUERY = Query.and(
            Player.getComponentType(),
            PlayerRef.getComponentType(),
            EntityStatMap.getComponentType(),
            DeathComponent.getComponentType()
    );

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Damage damage) {
        if (damage.isCancelled()) return;

        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        DeathComponent death = archetypeChunk.getComponent(index, DeathComponent.getComponentType());
        EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (playerRef == null || player == null || death == null || statMap == null || !playerRef.isValid()) return;

        Shotcave shotcave = Shotcave.getInstance();
        if (shotcave == null) return;

        GameManager gameManager = shotcave.getGameManager();
        Game game = gameManager.findGameForPlayer(playerRef.getUuid());
        if (game == null) return;
        if (game.getState() != GameState.ACTIVE && game.getState() != GameState.BOSS) return;

        if (death.isDead()) {
            damage.setCancelled(true);
            return;
        }

        int healthIdx = DefaultEntityStatTypes.getHealth();
        EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
        if (healthStat == null) return;

        float pendingDamage = Math.round(damage.getAmount());
        float currentHealth = healthStat.get();
        if ((currentHealth - pendingDamage) > healthStat.getMin()) return;

        damage.setCancelled(true);

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (!ref.isValid()) return;

        PlayerDeathSystem.handleDungeonDeath(commandBuffer, store, ref, playerRef, player, death);
    }
}