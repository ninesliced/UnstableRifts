package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.guns.DamageEffect;

import javax.annotation.Nonnull;

public final class DamageEffectVisualCleanupSystem extends EntityTickingSystem<EntityStore> {

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(EffectControllerComponent.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (!ref.isValid()) {
            return;
        }

        DamageEffectComponent activeComponent = archetypeChunk.getComponent(index, DamageEffectComponent.getComponentType());
        DamageEffect activeEffect = activeComponent != null && !activeComponent.isExpired()
                ? DamageEffect.fromOrdinal(activeComponent.getEffectOrdinal())
                : DamageEffect.NONE;

        for (DamageEffect effect : DamageEffect.values()) {
            if (effect == DamageEffect.NONE || effect == activeEffect || effect.getEntityEffectId() == null) {
                continue;
            }

            DamageEffectRuntime.clearVisual(commandBuffer, ref, effect);
        }
    }
}