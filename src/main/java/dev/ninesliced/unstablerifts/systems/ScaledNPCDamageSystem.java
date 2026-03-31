package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Scales outgoing damage from NPCs that have an {@link EntityScaleComponent}
 * with a scale greater than 1. The damage multiplier equals the entity's scale
 * factor (e.g. a 2× scaled Kweebec deals 2× damage).
 *
 * <p>Runs in the {@code InspectDamageGroup} so it applies BEFORE lethal
 * damage / filter checks.
 */
public final class ScaledNPCDamageSystem extends DamageEventSystem {

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

        // Only process NPC-sourced damage
        if (!(damage.getSource() instanceof Damage.EntitySource entitySource)) return;

        Ref<EntityStore> sourceRef = entitySource.getRef();
        if (!sourceRef.isValid()) return;

        // Verify the source is an NPC
        NPCEntity npc = commandBuffer.getComponent(sourceRef, NPCEntity.getComponentType());
        if (npc == null) return;

        // Read scale — skip if default (1.0)
        EntityScaleComponent scaleComp = commandBuffer.getComponent(
                sourceRef, EntityScaleComponent.getComponentType());
        if (scaleComp == null || scaleComp.getScale() <= 1.0f) return;

        damage.setAmount(damage.getAmount() * scaleComp.getScale());
    }
}
