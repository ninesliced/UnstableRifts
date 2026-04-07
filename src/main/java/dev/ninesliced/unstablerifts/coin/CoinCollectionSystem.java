package dev.ninesliced.unstablerifts.coin;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.logging.UnstableRiftsLog;
import dev.ninesliced.unstablerifts.pickup.ItemPickupConfig;
import dev.ninesliced.unstablerifts.pickup.ItemPickupTracker;
import dev.ninesliced.unstablerifts.systems.DeathComponent;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Tick system that auto-collects nearby coin entities for players.
 * Coins bypass inventory and increase the active dungeon team's money pool.
 * Falls back to the legacy personal score tracker outside active dungeon runs.
 */
public final class CoinCollectionSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = UnstableRiftsLog.forModule("Coin");
    private static final String KEY_ITEM_ID = "UnstableRifts_Key_Item";

    public CoinCollectionSystem() {
    }

    /**
     * Collects a single coin: removes entity, increments shared dungeon money,
     * and sends a notification.
     * Does NOT use generatePickedUpItem() to avoid infinite re-tracking loops.
     */
    private static void collectCoin(@Nonnull ItemPickupTracker.TrackedItem tracked,
                                    @Nonnull PlayerRef playerRef,
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

        String totalLabel = "Total";
        long newTotal;
        UnstableRifts unstablerifts = UnstableRifts.getInstance();
        Game game = unstablerifts != null ? unstablerifts.getGameManager().findGameForPlayer(playerRef.getUuid()) : null;
        if (game != null) {
            newTotal = game.addMoney(quantity);
            totalLabel = "Team Total";
        } else {
            newTotal = CoinScoreService.addCoins(playerRef.getUuid(), quantity);
        }

        try {
            NotificationUtil.sendNotification(
                    playerRef.getPacketHandler(),
                    Message.raw("+" + quantity + " Coin" + (quantity != 1 ? "s" : "")
                            + "  (" + totalLabel + ": " + newTotal + ")"),
                    null,
                    "coin_collected");
        } catch (Exception e) {
            LOGGER.at(java.util.logging.Level.FINE).withCause(e).log("Failed to send coin notification");
        }
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

        // Dead/ghost players cannot collect coins.
        DeathComponent death = store.getComponent(playerEntityRef, DeathComponent.getComponentType());
        if (death != null && death.isDead()) {
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
            if (KEY_ITEM_ID.equals(tracked.getItemId())) {
                continue;
            }
            if (!tracked.getRef().isValid()) {
                continue;
            }
            // Guard against cross-world refs: the tracked item may belong to a
            // different world's store (e.g. a dungeon instance that has been removed).
            if (tracked.getRef().getStore() != store) {
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
            collectCoin(tracked, playerRef, store, commandBuffer);
        }
    }
}
