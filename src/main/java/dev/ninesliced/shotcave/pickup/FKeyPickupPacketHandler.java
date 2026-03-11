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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import javax.annotation.Nonnull;
import java.util.logging.Logger;

/**
 * Observes {@link SyncInteractionChains} packets for F-key presses and
 * picks up the closest tracked item on the world thread.
 */
public final class FKeyPickupPacketHandler implements PlayerPacketWatcher {

    private static final Logger LOGGER = Logger.getLogger(FKeyPickupPacketHandler.class.getName());

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

        LOGGER.info("[FKeyPickup] handleSyncInteractionChains called, updates=" + updates.length);

        for (int i = 0; i < updates.length; i++) {
            SyncInteractionChain chain = updates[i];
            LOGGER.info("[FKeyPickup]   chain[" + i + "] type=" + chain.interactionType
                    + " initial=" + chain.initial
                    + " state=" + chain.state
                    + " itemInHand=" + chain.itemInHandId);
        }

        boolean hasUsePress = false;
        for (SyncInteractionChain chain : updates) {
            if (chain.interactionType == InteractionType.Use) {
                hasUsePress = true;
                break;
            }
        }

        if (!hasUsePress) {
            LOGGER.info("[FKeyPickup] No Use chain found, skipping");
            return;
        }

        LOGGER.info("[FKeyPickup] Use key press detected! TrackerSize=" + ItemPickupTracker.size());

        if (ItemPickupTracker.size() == 0) {
            LOGGER.info("[FKeyPickup] No tracked items, skipping");
            return;
        }

        if (!playerRef.isValid()) {
            LOGGER.info("[FKeyPickup] PlayerRef invalid, skipping");
            return;
        }

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            LOGGER.info("[FKeyPickup] PlayerEntityRef not found or invalid");
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        World world;
        try {
            world = store.getExternalData().getWorld();
        } catch (Exception e) {
            LOGGER.info("[FKeyPickup] Could not resolve world: " + e);
            return;
        }

        if (world == null) {
            LOGGER.info("[FKeyPickup] World is null, skipping");
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
        LOGGER.info("[FKeyPickup] attemptPickup running on world thread");

        if (!playerEntityRef.isValid()) {
            LOGGER.info("[FKeyPickup] playerEntityRef invalid, aborting");
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();

        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null || player.wasRemoved()) {
            LOGGER.info("[FKeyPickup] player null or removed, aborting");
            return;
        }

        TransformComponent playerTransform = store.getComponent(
                playerEntityRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            LOGGER.info("[FKeyPickup] playerTransform null, aborting");
            return;
        }

        Vector3d playerPos = playerTransform.getPosition();
        LOGGER.info("[FKeyPickup] playerPos=" + playerPos.x + "," + playerPos.y + "," + playerPos.z);

        if (ItemPickupTracker.size() == 0) {
            LOGGER.info("[FKeyPickup] tracker empty on world thread, aborting");
            return;
        }

        double pickupRadiusSq = ItemPickupConfig.ITEM_PICKUP_RADIUS
                * ItemPickupConfig.ITEM_PICKUP_RADIUS;

        LOGGER.info("[FKeyPickup] scanning " + ItemPickupTracker.size() + " tracked items, radius="
                + ItemPickupConfig.ITEM_PICKUP_RADIUS);

        ItemPickupTracker.TrackedItem closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (ItemPickupTracker.TrackedItem tracked : ItemPickupTracker.getAll()) {
            LOGGER.info("[FKeyPickup]   item=" + tracked.getItemId()
                    + " fkey=" + tracked.isFKeyPickup()
                    + " valid=" + tracked.getRef().isValid());
            if (!tracked.isFKeyPickup()) {
                continue;
            }
            if (!tracked.getRef().isValid()) {
                continue;
            }

            Vector3d itemPos = tracked.getPosition(store);
            if (itemPos == null) {
                LOGGER.info("[FKeyPickup]   itemPos=null, skipping");
                continue;
            }

            double dx = playerPos.x - itemPos.x;
            double dy = playerPos.y - itemPos.y;
            double dz = playerPos.z - itemPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            LOGGER.info("[FKeyPickup]   itemPos=" + itemPos.x + "," + itemPos.y + "," + itemPos.z
                    + " dist=" + Math.sqrt(distSq) + " maxDist=" + ItemPickupConfig.ITEM_PICKUP_RADIUS);

            if (distSq <= pickupRadiusSq && distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = tracked;
            }
        }

        if (closest == null) {
            LOGGER.info("[FKeyPickup] no F-key item in range, skipping");
            return;
        }

        LOGGER.info("[FKeyPickup] picking up " + closest.getItemId() + " at dist=" + Math.sqrt(closestDistSq));

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

        TransformComponent itemTransform = store.getComponent(
                itemRef, TransformComponent.getComponentType());
        Vector3d itemPos = (itemTransform != null) ? itemTransform.getPosition() : null;

        ItemStackTransaction transaction = player.giveItem(
                itemStack, playerEntityRef, store);
        ItemStack remainder = transaction.getRemainder();

        if (ItemStack.isEmpty(remainder)) {
            // Full pickup.
            itemComponent.setRemovedByPlayerPickup(true);
            store.removeEntity(itemRef, RemoveReason.REMOVE);

            if (itemPos != null) {
                player.notifyPickupItem(playerEntityRef, itemStack, itemPos, store);
            }

            sendPickupNotification(playerRef, tracked, itemStack.getQuantity());

        } else if (!remainder.equals(itemStack)) {
            // Partial pickup — update remaining stack and re-track.
            int pickedUp = itemStack.getQuantity() - remainder.getQuantity();
            itemComponent.setItemStack(remainder);

            ItemPickupTracker.track(tracked);

            if (pickedUp > 0 && itemPos != null) {
                player.notifyPickupItem(
                        playerEntityRef,
                        itemStack.withQuantity(pickedUp),
                        itemPos,
                        store);
            }

            if (pickedUp > 0) {
                sendPickupNotification(playerRef, tracked, pickedUp);
            }
        } else {
            // Inventory full — re-track, item stays in world.
            ItemPickupTracker.track(tracked);
        }
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
