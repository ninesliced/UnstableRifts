package dev.ninesliced.shotcave.pickup;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import javax.annotation.Nonnull;

import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.inventory.InventoryLockService;
import dev.ninesliced.shotcave.systems.DeathComponent;

/**
 * Observes {@link SyncInteractionChains} packets for F-key presses and
 * picks up the closest tracked item on the world thread.
 */
public final class FKeyPickupPacketHandler implements PlayerPacketWatcher {

    private static final int SYNC_INTERACTION_CHAINS_PACKET_ID = 290;

    public FKeyPickupPacketHandler() {
    }

    @Override
    public void accept(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (packet.getId() != SYNC_INTERACTION_CHAINS_PACKET_ID) {
            return;
        }

        SyncInteractionChains interactionChains = (SyncInteractionChains) packet;
        SyncInteractionChain[] updates = interactionChains.updates;

        if (updates == null || updates.length == 0) {
            return;
        }

        boolean hasUsePress = false;
        for (SyncInteractionChain chain : updates) {
            if (chain.interactionType == InteractionType.Use) {
                hasUsePress = true;
                break;
            }
        }

        if (!hasUsePress) {
            return;
        }

        if (ItemPickupTracker.size() == 0) {
            return;
        }

        if (!playerRef.isValid()) {
            return;
        }

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        World world;
        try {
            world = store.getExternalData().getWorld();
        } catch (Exception e) {
            return;
        }

        if (world == null) {
            return;
        }

        world.execute(() -> attemptPickup(playerRef, playerEntityRef));
    }

    /**
     * Finds the closest F-key item within pickup radius and collects it.
     * Only removes the entity after a successful inventory transaction.
     */
    private static void attemptPickup(@Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> playerEntityRef) {
        if (!playerEntityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();

        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null || player.wasRemoved()) {
            return;
        }

        DeathComponent death = store.getComponent(playerEntityRef, DeathComponent.getComponentType());
        if (death != null && death.isDead()) {
            return;
        }

        TransformComponent playerTransform = store.getComponent(
                playerEntityRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            return;
        }

        Vector3d playerPos = playerTransform.getPosition();

        if (ItemPickupTracker.size() == 0) {
            return;
        }

        double pickupRadiusSq = ItemPickupConfig.ITEM_PICKUP_RADIUS
                * ItemPickupConfig.ITEM_PICKUP_RADIUS;

        ItemPickupTracker.TrackedItem closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (ItemPickupTracker.TrackedItem tracked : ItemPickupTracker.getAll()) {
            if (!tracked.isFKeyPickup()) {
                continue;
            }
            if (!tracked.getRef().isValid()) {
                continue;
            }

            Vector3d itemPos = tracked.getPosition(store);
            if (itemPos == null) {
                continue;
            }

            double dx = playerPos.x - itemPos.x;
            double dy = playerPos.y - itemPos.y;
            double dz = playerPos.z - itemPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= pickupRadiusSq && distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = tracked;
            }
        }

        if (closest == null) {
            return;
        }

        collectItem(closest, player, playerRef, playerEntityRef, store);
    }

    /**
     * Collects a single F-key item: untracks atomically, runs the inventory
     * transaction, removes entity only on success, re-tracks on partial/full
     * failure.
     */
    private static void collectItem(@Nonnull ItemPickupTracker.TrackedItem tracked,
            @Nonnull Player player,
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> playerEntityRef,
            @Nonnull Store<EntityStore> store) {

        Ref<EntityStore> itemRef = tracked.getRef();
        if (!itemRef.isValid()) {
            ItemPickupTracker.untrack(itemRef);
            return;
        }

        // Atomic untrack to prevent double-collection.
        ItemPickupTracker.TrackedItem removed = ItemPickupTracker.entries().remove(itemRef);
        if (removed == null) {
            return;
        }

        ItemComponent itemComponent = store.getComponent(itemRef, ItemComponent.getComponentType());
        if (itemComponent == null) {
            return;
        }

        ItemStack itemStack = itemComponent.getItemStack();
        if (ItemStack.isEmpty(itemStack)) {
            store.removeEntity(itemRef, RemoveReason.REMOVE);
            return;
        }

        // When the player's inventory is locked (dungeon), use the 3-slot weapon swap logic.
        Shotcave shotcave = Shotcave.getInstance();
        if (shotcave != null && shotcave.getInventoryLockService().isLocked(playerRef.getUuid())) {
            collectItemLocked(tracked, player, playerRef, playerEntityRef, store, itemRef, itemComponent, itemStack);
            return;
        }

        ItemStackTransaction transaction = player.giveItem(
                itemStack, playerEntityRef, store);
        ItemStack remainder = transaction.getRemainder();

        if (ItemStack.isEmpty(remainder)) {
            // Full pickup.
            itemComponent.setRemovedByPlayerPickup(true);
            store.removeEntity(itemRef, RemoveReason.REMOVE);

            sendPickupNotification(playerRef, tracked, itemStack.getQuantity());

        } else if (!remainder.equals(itemStack)) {
            // Partial pickup — update remaining stack and re-track.
            int pickedUp = itemStack.getQuantity() - remainder.getQuantity();
            itemComponent.setItemStack(remainder);

            ItemPickupTracker.track(tracked);

            if (pickedUp > 0) {
                sendPickupNotification(playerRef, tracked, pickedUp);
            }
        } else {
            // Inventory full — re-track, item stays in world.
            ItemPickupTracker.track(tracked);
        }
    }

    /**
     * Locked-inventory pickup: places the weapon in the first empty slot (0-2),
     * or swaps the held weapon if all 3 slots are full (dropping the old one).
     */
    @SuppressWarnings("removal")
    private static void collectItemLocked(@Nonnull ItemPickupTracker.TrackedItem tracked,
            @Nonnull Player player,
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> playerEntityRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> itemRef,
            @Nonnull ItemComponent itemComponent,
            @Nonnull ItemStack newWeapon) {

        Inventory inventory = player.getInventory();
        if (inventory == null) {
            ItemPickupTracker.track(tracked);
            return;
        }

        ItemContainer hotbar = inventory.getHotbar();
        if (hotbar == null) {
            ItemPickupTracker.track(tracked);
            return;
        }

        short emptySlot = InventoryLockService.findEmptyWeaponSlot(hotbar);

        if (emptySlot >= 0) {
            // Free slot available — place directly.
            hotbar.setItemStackForSlot(emptySlot, newWeapon);
        } else {
            // All 3 weapon slots full — swap with held slot.
            byte activeSlot = inventory.getActiveHotbarSlot();
            if (activeSlot < 0 || activeSlot >= InventoryLockService.MAX_WEAPON_SLOTS) {
                activeSlot = 0;
            }

            ItemStack oldWeapon = hotbar.getItemStack(activeSlot);
            hotbar.setItemStackForSlot(activeSlot, newWeapon);

            // Drop the old weapon back into the world.
            if (!ItemStack.isEmpty(oldWeapon)) {
                ItemUtils.dropItem(playerEntityRef, oldWeapon, store);
            }
        }

        // Sync inventory to client.
        playerRef.getPacketHandler().writeNoCache(new UpdatePlayerInventory(
                inventory.getStorage() != null ? inventory.getStorage().toPacket() : null,
                inventory.getArmor() != null ? inventory.getArmor().toPacket() : null,
                inventory.getHotbar() != null ? inventory.getHotbar().toPacket() : null,
                inventory.getUtility() != null ? inventory.getUtility().toPacket() : null,
                inventory.getTools() != null ? inventory.getTools().toPacket() : null,
                inventory.getBackpack() != null ? inventory.getBackpack().toPacket() : null
        ));

        // Remove the picked-up entity from the world.
        itemComponent.setRemovedByPlayerPickup(true);
        store.removeEntity(itemRef, RemoveReason.REMOVE);

        sendPickupNotification(playerRef, tracked, newWeapon.getQuantity());
    }

    private static void sendPickupNotification(@Nonnull PlayerRef playerRef,
            @Nonnull ItemPickupTracker.TrackedItem tracked,
            int quantity) {
        try {
            String displayName = tracked.getDisplayName() != null
                    ? tracked.getDisplayName()
                    : tracked.getItemId();

            String text;
            if (quantity > 1) {
                text = "Picked up " + displayName + " x" + quantity;
            } else {
                text = "Picked up " + displayName;
            }

            NotificationUtil.sendNotification(
                    playerRef.getPacketHandler(),
                    Message.raw(text),
                    null,
                    "crate_item_pickup");
        } catch (Exception ignored) {
        }
    }
}
