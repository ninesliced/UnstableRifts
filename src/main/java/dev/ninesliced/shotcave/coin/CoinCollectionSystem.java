package dev.ninesliced.shotcave.coin;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ninesliced.shotcave.ShotcaveLog;
import dev.ninesliced.shotcave.pickup.ItemPickupConfig;
import dev.ninesliced.shotcave.pickup.ItemPickupTracker;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Tick system that auto-collects nearby coin entities for players.
 * Coins bypass inventory and increment the player's score via
 * {@link CoinScoreService}.
 */
public final class CoinCollectionSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = ShotcaveLog.forModule("Coin");

    public CoinCollectionSystem() {
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void tick(float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        if (ItemPickupTracker.size() == 0) {
            return;
        }

        Ref<EntityStore> playerEntityRef = archetypeChunk.getReferenceTo(index);
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null || player.wasRemoved()) {
            return;
        }

        TransformComponent playerTransform = store.getComponent(playerEntityRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            return;
        }

        PlayerRef playerRef = store.getComponent(playerEntityRef, PlayerRef.getComponentType());
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Vector3d playerPos = playerTransform.getPosition();

        double collectRadius = ItemPickupConfig.getCoinCollectRadius(playerRef.getUuid());
        double collectRadiusSq = collectRadius * collectRadius;

        // Gather coins in range first to avoid ConcurrentModificationException.
        List<ItemPickupTracker.TrackedItem> toCollect = null;

        for (ItemPickupTracker.TrackedItem tracked : ItemPickupTracker.getAll()) {
            if (!tracked.isScoreCollect()) {
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

            if (distSq <= collectRadiusSq) {
                if (toCollect == null) {
                    toCollect = new ArrayList<>(4);
                }
                toCollect.add(tracked);
            }
        }

        if (toCollect == null) {
            return;
        }

        for (ItemPickupTracker.TrackedItem tracked : toCollect) {
            collectCoin(tracked, player, playerRef, playerEntityRef, store, commandBuffer);
        }
    }

    /**
     * Collects a single coin: removes entity, increments score, sends notification.
     * Does NOT use generatePickedUpItem() to avoid infinite re-tracking loops.
     */
    private static void collectCoin(@Nonnull ItemPickupTracker.TrackedItem tracked,
            @Nonnull Player player,
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<EntityStore> playerEntityRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        Ref<EntityStore> coinRef = tracked.getRef();

        // Atomic untrack — prevents double-collection on the same frame.
        ItemPickupTracker.TrackedItem removed = ItemPickupTracker.entries().remove(coinRef);
        if (removed == null) {
            return;
        }

        if (!coinRef.isValid()) {
            return;
        }

        int quantity = 1;

        ItemComponent itemComponent = store.getComponent(coinRef, ItemComponent.getComponentType());
        if (itemComponent != null) {
            ItemStack stack = itemComponent.getItemStack();
            if (stack != null && !ItemStack.isEmpty(stack)) {
                quantity = stack.getQuantity();
            }
            itemComponent.setRemovedByPlayerPickup(true);
        }

        commandBuffer.removeEntity(coinRef, RemoveReason.REMOVE);

        int newTotal = CoinScoreService.addCoins(playerRef.getUuid(), quantity);

        try {
            NotificationUtil.sendNotification(
                    playerRef.getPacketHandler(),
                    Message.raw("+" + quantity + " Coin" + (quantity != 1 ? "s" : "")
                            + "  (Total: " + newTotal + ")"),
                    null,
                    "coin_collected");
        } catch (Exception e) {
            LOGGER.at(java.util.logging.Level.FINE).withCause(e).log("Failed to send coin notification");
        }
    }
}
