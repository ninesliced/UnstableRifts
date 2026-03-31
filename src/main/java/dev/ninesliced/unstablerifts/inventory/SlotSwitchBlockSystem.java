package dev.ninesliced.unstablerifts.inventory;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.event.events.ecs.SwitchActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Prevents locked players from switching to hotbar slots beyond the weapon slot range (0-2).
 */
public final class SlotSwitchBlockSystem extends EntityEventSystem<EntityStore, SwitchActiveSlotEvent> {

    private final InventoryLockService lockService;

    public SlotSwitchBlockSystem(@Nonnull InventoryLockService lockService) {
        super(SwitchActiveSlotEvent.class);
        this.lockService = lockService;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull SwitchActiveSlotEvent event) {
        if (event.getInventorySectionId() != InventoryComponent.HOTBAR_SECTION_ID) return;
        if (event.getNewSlot() < InventoryLockService.MAX_WEAPON_SLOTS) return;

        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) return;

        if (lockService.isLocked(uuidComponent.getUuid())) {
            event.setCancelled(true);
        }
    }
}
