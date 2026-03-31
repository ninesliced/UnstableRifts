package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.protocol.packets.worldmap.UpdateWorldMapSettings;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.InteractionSimulationHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.armor.ArmorChargeComponent;
import dev.ninesliced.unstablerifts.hud.*;
import dev.ninesliced.unstablerifts.systems.DeathComponent;
import dev.ninesliced.unstablerifts.systems.DeathMovementController;
import dev.ninesliced.unstablerifts.systems.DeathStateController;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages player state transitions: health/stamina reset, movement settings,
 * death component cleanup, and dungeon HUD visibility.
 */
public final class PlayerStateService {

    private static final Logger LOGGER = Logger.getLogger(PlayerStateService.class.getName());

    /**
     * Resets player health, stamina, death state, interaction manager, movement,
     * and armor charge to defaults.
     */
    public void resetPlayerStatus(@Nonnull Player player,
                                  @Nonnull Ref<EntityStore> ref,
                                  @Nonnull Store<EntityStore> store) {
        resetPlayerStatus(player, ref, store, null);
    }

    public void resetPlayerStatus(@Nonnull Player player,
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
                int healthIdx = EntityStatType.getAssetMap().getIndex(DungeonConstants.HEALTH_STAT);
                int staminaIdx = EntityStatType.getAssetMap().getIndex(DungeonConstants.STAMINA_STAT);

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

            ArmorChargeComponent charge = store.getComponent(ref, ArmorChargeComponent.getComponentType());
            if (charge != null) {
                charge.reset();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to reset player status", e);
        }

        restoreDefaultMovementSettings(ref, store);
    }

    /**
     * Applies dungeon-optimized movement physics.
     */
    public void applyDungeonMovementSettings(@Nonnull Ref<EntityStore> ref,
                                             @Nonnull Store<EntityStore> store,
                                             @Nonnull PlayerRef playerRef) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) return;

        MovementSettings s = movementManager.getSettings();

        // Core speed: ~80% faster base, fast-paced dungeon crawler
        s.baseSpeed = 10.0f;

        // Acceleration: snappy direction changes
        s.acceleration = 0.22f;
        s.velocityResistance = 0.14f;

        // Auto-climb: seamless, no slowdown
        s.autoJumpObstacleMaxAngle = 180.0f;
        s.autoJumpObstacleSpeedLoss = 1.0f;
        s.autoJumpObstacleSprintSpeedLoss = 1.0f;
        s.autoJumpObstacleEffectDuration = 0.0f;
        s.autoJumpObstacleSprintEffectDuration = 0.0f;

        // Jump: disabled (replaced by roll system)
        s.jumpForce = 0.0f;
        s.jumpBufferDuration = 0.0f;
        s.variableJumpFallForce = 28.0f;

        // Air control: full authority mid-air
        s.airControlMaxMultiplier = 5.0f;
        s.airControlMaxSpeed = 5.0f;
        s.airFrictionMin = 0.01f;
        s.airFrictionMax = 0.02f;
        s.airSpeedMultiplier = 1.2f;

        // Fall: no punishment in top-down view
        s.fallEffectDuration = 0.0f;
        s.fallMomentumLoss = 0.0f;
        s.fallJumpForce = s.jumpForce;

        // Slide/roll: faster combat dodging
        s.minSlideEntrySpeed = 5.5f;
        s.slideExitSpeed = 3.5f;
        s.rollTimeToComplete = 0.2f;
        s.rollStartSpeedModifier = 4.0f;
        s.rollExitSpeedModifier = 3.0f;

        // Collision: slightly stronger push-out
        s.collisionExpulsionForce = 0.04f;

        movementManager.update(playerRef.getPacketHandler());
    }

    public void restoreDefaultMovementSettings(@Nonnull Ref<EntityStore> ref,
                                               @Nonnull Store<EntityStore> store) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) return;

        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        movementManager.applyDefaultSettings();
        movementManager.update(playerRef.getPacketHandler());
    }

    public void enableMap(@Nonnull PlayerRef playerRef) {
        UpdateWorldMapSettings mapSettings = new UpdateWorldMapSettings();
        mapSettings.enabled = true;
        mapSettings.defaultScale = 16.0f;
        mapSettings.minScale = 4.0f;
        mapSettings.maxScale = 128.0f;
        playerRef.getPacketHandler().writeNoCache(mapSettings);
    }

    public void hideDungeonHuds(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        DungeonInfoHud.hideHud(player, playerRef);
        PartyStatusHud.hideHud(player, playerRef);
        ChallengeHud.hideHud(player, playerRef);
        DeathCountdownHud.hideHud(player, playerRef);
        ReviveProgressHud.hideHud(player, playerRef);
    }
}
