package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.crate.DestructibleBlockConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ensures destructible blocks (crates, barrels) break in one melee hit.
 * <p>
 * The vanilla sword's {@code HitBlock} path fires a {@link DamageBlockEvent},
 * but the sword's gathering power may be insufficient to break the block in a
 * single hit. This system intercepts the event and boosts damage so the block
 * always breaks, causing a {@code BreakBlockEvent} that
 * {@link dev.ninesliced.shotcave.crate.CrateBreakDropSystem} handles.
 */
public final class MeleeBlockBreakSystem extends EntityEventSystem<EntityStore, DamageBlockEvent> {

    public MeleeBlockBreakSystem() {
        super(DamageBlockEvent.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull DamageBlockEvent event) {
        if (event.isCancelled()) {
            return;
        }

        BlockType blockType = event.getBlockType();
        if (blockType == null) {
            return;
        }

        String blockTypeId = blockType.getId();
        if (blockTypeId == null || !DestructibleBlockConfig.isDestructible(blockTypeId)) {
            return;
        }

        // Force one-hit break for all destructible blocks.
        event.setDamage(999f);
    }
}
