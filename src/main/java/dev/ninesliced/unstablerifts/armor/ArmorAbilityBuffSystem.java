package dev.ninesliced.unstablerifts.armor;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Logger;

/**
 * Activates and expires armor set ability buffs.
 * Each ability stores its state in the player's {@link ArmorChargeComponent}.
 * Damage/speed modifiers are read from the charge component by the relevant
 * runtime systems (damage pipeline, roll, revive, etc.).
 */
public final class ArmorAbilityBuffSystem {

    private static final Logger LOGGER = Logger.getLogger(ArmorAbilityBuffSystem.class.getName());
    private static final String EFFECT_ID = "Immune";

    private ArmorAbilityBuffSystem() {
    }

    /**
     * Called when the player activates a fully charged 4/4 set ability.
     */
    public static void activateAbility(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull ComponentAccessor<EntityStore> accessor,
                                       @Nonnull ArmorSetAbility ability,
                                       @Nullable PlayerRef playerRef) {
        ArmorChargeComponent charge = accessor.getComponent(ref, ArmorChargeComponent.getComponentType());
        if (charge == null) return;

        charge.startBuff(ability);

        LOGGER.info("[UnstableRifts] Armor ability activated: " + ability.getDisplayName()
                + " (" + ability.getDurationSeconds() + "s)"
                + (playerRef != null ? " for " + playerRef.getUuid() : ""));

        if (playerRef != null) {
            Message msg = Message.raw(ability.getDisplayName() + " activated! (" + ability.getDurationSeconds() + "s)")
                    .color(ability.getColorHex()).bold(true);
            playerRef.sendMessage(msg);
        }

        applyBuffEffect(ref, accessor, ability.getDurationSeconds());

        switch (ability) {
            case BERSERKER -> {
            } // +40% damage read by UnstableRiftsDamageInteraction/MeleeDamageEffectSystem
            case REGENERATION -> {
            } // HP regen ticked in ArmorChargeSystem
            case GUARDIAN -> {
            } // 50% damage reduction read by DungeonLethalDamageSystem
            case PURIFICATION -> {
                accessor.tryRemoveComponent(ref,
                        dev.ninesliced.unstablerifts.systems.DamageEffectComponent.getComponentType());
            }
            case SWIFTNESS -> applySpeedBuff(ref, accessor, 1.5f);
            case WARDEN -> {
            } // +25% spike damage read by DungeonLethalDamageSystem
            default -> {
            }
        }
    }

    /**
     * Called when a buff expires after its duration.
     */
    public static void expireBuff(@Nonnull Ref<EntityStore> ref,
                                  @Nonnull ComponentAccessor<EntityStore> accessor,
                                  @Nonnull ArmorSetAbility ability) {
        LOGGER.info("[UnstableRifts] Armor ability expired: " + ability.getDisplayName());

        switch (ability) {
            case SWIFTNESS -> applySpeedBuff(ref, accessor, 1.0f);
            default -> {
            }
        }
    }

    private static void applyBuffEffect(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull ComponentAccessor<EntityStore> accessor,
                                        float durationSeconds) {
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(EFFECT_ID);
        if (effect == null) return;

        EffectControllerComponent effectController = accessor.ensureAndGetComponent(
                ref, EffectControllerComponent.getComponentType());
        if (effectController != null) {
            effectController.addEffect(ref, effect, durationSeconds, OverlapBehavior.OVERWRITE, accessor);
        }
    }

    /**
     * Checks if the player currently has the given buff active.
     */
    public static boolean isBuffActive(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull ArmorSetAbility ability) {
        if (!ref.isValid()) return false;
        ArmorChargeComponent charge = ref.getStore().getComponent(ref, ArmorChargeComponent.getComponentType());
        if (charge == null) return false;
        return charge.hasActiveBuff() && charge.getActiveAbility() == ability;
    }

    /**
     * Returns the damage multiplier from active berserker buff (1.0 if not active).
     */
    public static float getDamageMultiplier(@Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) return 1.0f;
        ArmorChargeComponent charge = ref.getStore().getComponent(ref, ArmorChargeComponent.getComponentType());
        if (charge == null || !charge.hasActiveBuff()) return 1.0f;
        return charge.getActiveAbility() == ArmorSetAbility.BERSERKER ? 1.4f : 1.0f;
    }

    /**
     * Returns the damage reduction multiplier from guardian buff (1.0 if not active).
     */
    public static float getGuardianReduction(@Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) return 1.0f;
        ArmorChargeComponent charge = ref.getStore().getComponent(ref, ArmorChargeComponent.getComponentType());
        if (charge == null || !charge.hasActiveBuff()) return 1.0f;
        return charge.getActiveAbility() == ArmorSetAbility.GUARDIAN ? 0.5f : 1.0f;
    }

    /**
     * Returns true if purification buff is active (DoT immunity).
     */
    public static boolean isPurificationActive(@Nonnull Ref<EntityStore> ref) {
        return isBuffActive(ref, ArmorSetAbility.PURIFICATION);
    }

    /**
     * Returns the spike damage reflection multiplier from warden buff (0.0 if not active).
     */
    public static float getWardenReflection(@Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) return 0.0f;
        ArmorChargeComponent charge = ref.getStore().getComponent(ref, ArmorChargeComponent.getComponentType());
        if (charge == null || !charge.hasActiveBuff()) return 0.0f;
        return charge.getActiveAbility() == ArmorSetAbility.WARDEN ? 0.25f : 0.0f;
    }

    private static void applySpeedBuff(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull ComponentAccessor<EntityStore> accessor,
                                       float multiplier) {
        EntityStatMap statMap = accessor.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) return;
        int speedIdx = EntityStatType.getAssetMap().getIndex("MovementSpeed");
        if (speedIdx < 0) return;
        EntityStatValue speedStat = statMap.get(speedIdx);
        if (speedStat == null) return;
        float baseSpeed = speedStat.get();
        statMap.setStatValue(speedIdx, baseSpeed * multiplier);
    }
}
