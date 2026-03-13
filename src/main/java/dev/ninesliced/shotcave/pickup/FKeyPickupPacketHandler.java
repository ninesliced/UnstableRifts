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
import java.util.logging.Level;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ninesliced.shotcave.ShotcaveLog;

/**
 * Observes {@link SyncInteractionChains} packets for F-key presses and
 * picks up the closest tracked item on the world thread.
 */
public final class FKeyPickupPacketHandler implements PlayerPacketWatcher {

    private static final HytaleLogger LOGGER = ShotcaveLog.forModule("Pickup");

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

        LOGGER.at(Level.INFO).log("[FKeyPickup] handleSyncInteractionChains called, updates=%d", updates.length);

        for (int i = 0; i < updates.length; i++) {
            SyncInteractionChain chain = updates[i];
            LOGGER.at(Level.INFO).log("[FKeyPickup]   chain[%d] type=%s initial=%s state=%s itemInHand=%s",
                    i, chain.interactionType, chain.initial, chain.state, chain.itemInHandId);
        }

        boolean hasUsePress = false;
        for (SyncInteractionChain chain : updates) {
            if (chain.interactionType == InteractionType.Use) {
                hasUsePress = true;
                break;
            }
        }

        if (!hasUsePress) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] No Use chain found, skipping");
            return;
        }

        LOGGER.at(Level.INFO).log("[FKeyPickup] Use key press detected! TrackerSize=%d", ItemPickupTracker.size());

        if (ItemPickupTracker.size() == 0) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] No tracked items, skipping");
            return;
        }

        if (!playerRef.isValid()) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] PlayerRef invalid, skipping");
            return;
        }

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] PlayerEntityRef not found or invalid");
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        World world;
        try {
            world = store.getExternalData().getWorld();
        } catch (Exception e) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] Could not resolve world: %s", e);
            return;
        }

        if (world == null) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] World is null, skipping");
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
        LOGGER.at(Level.INFO).log("[FKeyPickup] attemptPickup running on world thread");

        if (!playerEntityRef.isValid()) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] playerEntityRef invalid, aborting");
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();

        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null || player.wasRemoved()) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] player null or removed, aborting");
            return;
        }

        TransformComponent playerTransform = store.getComponent(
                playerEntityRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] playerTransform null, aborting");
            return;
        }

        Vector3d playerPos = playerTransform.getPosition();
        LOGGER.at(Level.INFO).log("[FKeyPickup] playerPos=%.2f,%.2f,%.2f", playerPos.x, playerPos.y, playerPos.z);

        if (ItemPickupTracker.size() == 0) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] tracker empty on world thread, aborting");
            return;
        }

        double pickupRadiusSq = ItemPickupConfig.ITEM_PICKUP_RADIUS
                * ItemPickupConfig.ITEM_PICKUP_RADIUS;

        LOGGER.at(Level.INFO).log("[FKeyPickup] scanning %d tracked items, radius=%.2f",
                ItemPickupTracker.size(), ItemPickupConfig.ITEM_PICKUP_RADIUS);

        ItemPickupTracker.TrackedItem closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (ItemPickupTracker.TrackedItem tracked : ItemPickupTracker.getAll()) {
            LOGGER.at(Level.INFO).log("[FKeyPickup]   item=%s fkey=%s valid=%s",
                    tracked.getItemId(), tracked.isFKeyPickup(), tracked.getRef().isValid());
            if (!tracked.isFKeyPickup()) {
                continue;
            }
            if (!tracked.getRef().isValid()) {
                continue;
            }

            Vector3d itemPos = tracked.getPosition(store);
            if (itemPos == null) {
                LOGGER.at(Level.INFO).log("[FKeyPickup]   itemPos=null, skipping");
                continue;
            }

            double dx = playerPos.x - itemPos.x;
            double dy = playerPos.y - itemPos.y;
            double dz = playerPos.z - itemPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            LOGGER.at(Level.INFO).log("[FKeyPickup]   itemPos=%.2f,%.2f,%.2f dist=%.2f maxDist=%.2f",
                    itemPos.x, itemPos.y, itemPos.z, Math.sqrt(distSq), ItemPickupConfig.ITEM_PICKUP_RADIUS);

            if (distSq <= pickupRadiusSq && distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = tracked;
            }
        }

        if (closest == null) {
            LOGGER.at(Level.INFO).log("[FKeyPickup] no F-key item in range, skipping");
            return;
        }

        LOGGER.at(Level.INFO).log("[FKeyPickup] picking up %s at dist=%.2f", closest.getItemId(),
                Math.sqrt(closestDistSq));

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
