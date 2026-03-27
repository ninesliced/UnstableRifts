package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.physics.systems.GenericVelocityInstructionSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.systems.NPCVelocityInstructionSystem;
import dev.ninesliced.shotcave.armor.ArmorAbilityBuffSystem;
import dev.ninesliced.shotcave.guns.DamageEffect;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Ticks damage-over-time effects (ACID, FIRE, ICE) applied by weapon shots.
 * Reduces entity health each tick interval until the effect expires, then removes the component.
 */
public final class DamageEffectTickSystem extends EntityTickingSystem<EntityStore> {
    private static final double ICE_SLOW_FACTOR = 0.35;

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, NPCVelocityInstructionSystem.class),
            new SystemDependency<>(Order.BEFORE, GenericVelocityInstructionSystem.class)
    );

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return this.dependencies;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(DamageEffectComponent.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        DamageEffectComponent effect = archetypeChunk.getComponent(index, DamageEffectComponent.getComponentType());
        if (effect == null) {
            return;
        }

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (!ref.isValid()) {
            return;
        }

        DamageEffect activeEffect = DamageEffect.fromOrdinal(effect.getEffectOrdinal());
        if (effect.isExpired()) {
            DamageEffectRuntime.clearVisual(commandBuffer, ref, activeEffect);
            commandBuffer.removeComponent(ref, DamageEffectComponent.getComponentType());
            return;
        }

        // Purification buff: immediately cleanse any active DoT
        if (ArmorAbilityBuffSystem.isPurificationActive(ref)) {
            DamageEffectRuntime.clearVisual(commandBuffer, ref, activeEffect);
            commandBuffer.removeComponent(ref, DamageEffectComponent.getComponentType());
            return;
        }

        applyMovementControl(commandBuffer, ref, activeEffect);

        int deltaMs = Math.round(dt * 1000.0f);

        boolean shouldTick = effect.advance(deltaMs);
        if (shouldTick && effect.getDamagePerTick() > 0.0f) {
            EntityStatMap statMap = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
            if (statMap != null) {
                int healthIdx = DefaultEntityStatTypes.getHealth();
                EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
                if (healthStat != null) {
                    float currentHealth = healthStat.get();
                    float newHealth = Math.max(healthStat.getMin(), currentHealth - effect.getDamagePerTick());
                    statMap.setStatValue(healthIdx, newHealth);
                }
            }
        }

        if (effect.isExpired()) {
            DamageEffectRuntime.clearVisual(commandBuffer, ref, activeEffect);
            commandBuffer.removeComponent(ref, DamageEffectComponent.getComponentType());
        }
    }

    private static void applyMovementControl(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                             @Nonnull Ref<EntityStore> ref,
                                             @Nonnull DamageEffect effect) {
        if (effect != DamageEffect.ELECTRICITY && effect != DamageEffect.ICE) {
            return;
        }

        Velocity velocity = commandBuffer.getComponent(ref, Velocity.getComponentType());
        if (velocity == null) {
            return;
        }

        Vector3d adjustedVelocity = new Vector3d(velocity.getVelocity());
        if (effect == DamageEffect.ELECTRICITY) {
            adjustedVelocity.x = 0.0;
            if (adjustedVelocity.y > 0.0) {
                adjustedVelocity.y = 0.0;
            }
            adjustedVelocity.z = 0.0;
        } else {
            adjustedVelocity.x *= ICE_SLOW_FACTOR;
            if (adjustedVelocity.y > 0.0) {
                adjustedVelocity.y = 0.0;
            }
            adjustedVelocity.z *= ICE_SLOW_FACTOR;
        }

        velocity.addInstruction(adjustedVelocity, null, ChangeVelocityType.Set);
    }
}
