package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.SwitchActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.hud.AmmoHudService;

import javax.annotation.Nonnull;

/**
 * Refreshes Shotcave ammo HUD when the active inventory slot changes.
 */
public final class ActiveSlotHudUpdateSystem extends EntityEventSystem<EntityStore, SwitchActiveSlotEvent> {

    public ActiveSlotHudUpdateSystem() {
        super(SwitchActiveSlotEvent.class);
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
        Ref<EntityStore> ref = chunk.getReferenceTo(index);

        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null || !playerRef.isValid()) {
            return;
        }

        ItemStack heldItem = null;
        if (player.getInventory() != null) {
            heldItem = player.getInventory().getItemInHand();
        }

        AmmoHudService.updateForHeldItem(player, playerRef, heldItem);
    }
}

