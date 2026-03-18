package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.guns.DamageEffect;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Inspects all damage events. When the source entity has a {@link SummonedEffectComponent},
 * applies the corresponding damage effect (DoT) to the target that was hit.
 */
public final class SummonedNPCDamageEffectSystem extends DamageEventSystem {

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Damage damage) {
        if (damage.isCancelled()) return;

        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) return;

        Ref<EntityStore> sourceRef = entitySource.getRef();
        if (!sourceRef.isValid()) return;

        SummonedEffectComponent summonedEffect = commandBuffer.getComponent(
                sourceRef, SummonedEffectComponent.getComponentType());
        if (summonedEffect == null) return;

        DamageEffect effect = DamageEffect.fromOrdinal(summonedEffect.getEffectOrdinal());
        if (effect == DamageEffect.NONE) return;

        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        if (!targetRef.isValid()) return;

        // Don't apply effect back to the summoned NPC itself
        if (targetRef.equals(sourceRef)) return;

        applyDoT(commandBuffer, targetRef, effect);
    }

    private void applyDoT(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                           @Nonnull Ref<EntityStore> target,
                           @Nonnull DamageEffect effect) {
        if (!effect.hasDoT()) {
            return;
        }

        DamageEffectRuntime.apply(commandBuffer, target, effect);
    }
}
