package dev.ninesliced.shotcave.pickup;

import com.hypixel.hytale.component.Ref;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.OnlinePlayers;
import dev.ninesliced.shotcave.Shotcave;

import javax.annotation.Nonnull;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls nearby F-key items per player and shows/hides {@link ItemPickupHud}.
 */
public final class ItemPickupHudRuntime {

    private static final long POLL_INTERVAL_MS = 150L;

    private ScheduledFuture<?> pollTask;

    public void start(@Nonnull Shotcave plugin) {
        plugin.getEventRegistry().registerGlobal(
                PlayerDisconnectEvent.class, this::onPlayerDisconnect);

        startPoller();
    }

    public void stop() {
        if (this.pollTask != null) {
            this.pollTask.cancel(false);
            this.pollTask = null;
        }
        ItemPickupHudService.clearAll();
        ItemPickupTracker.clear();
    }

    public void onPlayerConnect(@Nonnull PlayerRef playerRef) {
        ItemPickupHudService.clear(playerRef);
    }

    private void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        ItemPickupHudService.clear(event.getPlayerRef());
    }

    private void startPoller() {
        if (this.pollTask != null) {
            this.pollTask.cancel(false);
        }
        this.pollTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                this::pollAllPlayers,
                POLL_INTERVAL_MS,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void pollAllPlayers() {
        try {
            ItemPickupTracker.pruneInvalid();

            for (PlayerRef playerRef : OnlinePlayers.snapshot()) {
                pollSinglePlayer(playerRef);
            }
        } catch (Exception e) {
            // Keep the scheduled task alive.
        }
    }

    /**
     * Finds the closest F-key item within HUD proximity radius and shows/hides the
     * HUD. Detects crouching to expand weapon details panel.
     */
    private void pollSinglePlayer(@Nonnull PlayerRef playerRef) {
        if (!playerRef.isValid()) {
            return;
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        ref.getStore().getExternalData().getWorld().execute(() -> {
            try {
                Player player = ref.getStore().getComponent(ref, Player.getComponentType());
                if (player == null || player.wasRemoved()) {
                    return;
                }

                TransformComponent playerTransform = ref.getStore().getComponent(
                        ref, TransformComponent.getComponentType());
                if (playerTransform == null) {
                    return;
                }

                // Detect crouching
                boolean crouching = false;
                MovementStatesComponent movementStates = ref.getStore().getComponent(
                        ref, MovementStatesComponent.getComponentType());
                if (movementStates != null) {
                    crouching = movementStates.getMovementStates().crouching;
                }

                Vector3d playerPos = playerTransform.getPosition();
                double radiusSq = ItemPickupConfig.HUD_PROXIMITY_RADIUS
                        * ItemPickupConfig.HUD_PROXIMITY_RADIUS;

                ItemPickupTracker.TrackedItem closest = null;
                double closestDistSq = Double.MAX_VALUE;
                int closestQuantity = 1;
                ItemStack closestStack = null;

                for (ItemPickupTracker.TrackedItem tracked : ItemPickupTracker.getAll()) {
                    if (!tracked.isFKeyPickup()) {
                        continue;
                    }
                    if (!tracked.getRef().isValid()) {
                        continue;
                    }
                    if (tracked.getRef().getStore() != ref.getStore()) {
                        continue;
                    }

                    Vector3d itemPos = tracked.getPosition(ref.getStore());
                    if (itemPos == null) {
                        continue;
                    }

                    double dx = playerPos.x - itemPos.x;
                    double dy = playerPos.y - itemPos.y;
                    double dz = playerPos.z - itemPos.z;
                    double distSq = dx * dx + dy * dy + dz * dz;

                    if (distSq <= radiusSq && distSq < closestDistSq) {
                        closestDistSq = distSq;
                        closest = tracked;

                        ItemStack stack = tracked.getItemStack(ref.getStore());
                        closestStack = stack;
                        closestQuantity = (stack != null) ? stack.getQuantity() : 1;
                    }
                }

                if (closest != null) {
                    String name = closest.getDisplayName() != null
                            ? closest.getDisplayName()
                            : closest.getItemId();
                    String icon = closest.getIconPath();

                    ItemPickupHudService.show(player, playerRef, name, icon,
                            closestQuantity, crouching, closestStack);
                } else {
                    ItemPickupHudService.hide(player, playerRef);
                }
            } catch (Exception e) {
                // Silently ignore per-player errors.
            }
        });
    }
}
