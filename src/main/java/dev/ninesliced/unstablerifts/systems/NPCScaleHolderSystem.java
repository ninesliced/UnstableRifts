package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;

/**
 * Applies {@link EntityScaleComponent} to NPC entities at creation time via
 * the {@link HolderSystem} hook, which fires exactly once per entity during
 * {@code Store.addEntity()}.
 * <p>
 * This replaces the tick-system approach which was unreliable due to timing
 * issues with game/level initialization and commandBuffer delays.
 */
public final class NPCScaleHolderSystem extends HolderSystem<EntityStore> {

    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder,
                            @Nonnull AddReason reason,
                            @Nonnull Store<EntityStore> store) {
        NPCEntity npc = holder.getComponent(NPCEntity.getComponentType());
        if (npc == null) return;

        float scale = KweebecScaleHelper.getScaleForRole(npc.getRoleName());
        if (scale <= 0f) return;

        holder.putComponent(EntityScaleComponent.getComponentType(),
                new EntityScaleComponent(scale));
    }

    @Override
    public void onEntityRemoved(@Nonnull Holder<EntityStore> holder,
                                @Nonnull RemoveReason reason,
                                @Nonnull Store<EntityStore> store) {
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }
}
