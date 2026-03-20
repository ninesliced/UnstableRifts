package dev.ninesliced.shotcave.inventory;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Blocks item drops from non-weapon slots for locked players.
 * Drops from the 3 weapon hotbar slots (0-2) are allowed so players
 * can voluntarily discard a weapon during a dungeon run.
 */
public final class DropBlockSystem extends EntityEventSystem<EntityStore, DropItemEvent.PlayerRequest> {

    private final InventoryLockService lockService;

    public DropBlockSystem(@Nonnull InventoryLockService lockService) {
        super(DropItemEvent.PlayerRequest.class);
        this.lockService = lockService;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @SuppressWarnings("removal")
    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull DropItemEvent.PlayerRequest event) {
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) return;

        if (lockService.isLocked(uuidComponent.getUuid())) {
            // Allow drops from the 3 weapon hotbar slots.
            if (event.getInventorySectionId() == Inventory.HOTBAR_SECTION_ID
                    && event.getSlotId() >= 0
                    && event.getSlotId() < InventoryLockService.MAX_WEAPON_SLOTS) {
                return;
            }
            event.setCancelled(true);
        }
    }
}
