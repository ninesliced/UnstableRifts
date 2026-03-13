package dev.ninesliced.shotcave.crate;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Spawns crate drops on {@link BreakBlockEvent} (melee, fist, gathering, etc.).
 * Complements {@code BreakSoftBlockInteraction} which handles projectile
 * breaks.
 */
public final class CrateBreakDropSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    public CrateBreakDropSystem() {
        super(BreakBlockEvent.class);
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
            @Nonnull BreakBlockEvent event) {

        Vector3i targetBlock = event.getTargetBlock();
        if (targetBlock == null) {
            return;
        }

        Ref<EntityStore> playerEntityRef = archetypeChunk.getReferenceTo(index);
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        World world;
        try {
            world = store.getExternalData().getWorld();
        } catch (Exception e) {
            return;
        }

        if (world == null) {
            return;
        }

        BlockType blockType = world.getBlockType(targetBlock.x, targetBlock.y, targetBlock.z);
        if (blockType == null) {
            return;
        }

        String blockTypeId = blockType.getId();
        if (blockTypeId == null || !CrateDropGenerator.isCrate(blockTypeId)) {
            return;
        }

        List<ItemStack> drops = CrateDropGenerator.generateDrops(blockTypeId);
        if (drops.isEmpty()) {
            return;
        }

        // TODO: Currently doesn't work with multiblocks
        Vector3d dropPosition = new Vector3d(
                targetBlock.x + 0.5,
                targetBlock.y,
                targetBlock.z + 0.5);

        world.execute(() -> {
            Store<EntityStore> entityStore = world.getEntityStore().getStore();
            Holder<EntityStore>[] itemEntityHolders = ItemComponent.generateItemDrops(
                    entityStore, drops, dropPosition, Vector3f.ZERO);
            if (itemEntityHolders.length > 0) {
                entityStore.addEntities(itemEntityHolders, AddReason.SPAWN);
            }
        });
    }
}
