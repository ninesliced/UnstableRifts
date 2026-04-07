package dev.ninesliced.unstablerifts.shop;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.dungeon.DoorService;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.dungeon.Level;
import dev.ninesliced.unstablerifts.pickup.ItemPickupConfig;
import dev.ninesliced.unstablerifts.pickup.ItemPickupTracker;
import dev.ninesliced.unstablerifts.player.OnlinePlayers;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls nearby key doors and shopkeepers per player and shows/hides the shared interaction prompt.
 */
public final class ShopPromptHudRuntime {

    private static final long POLL_INTERVAL_MS = 200L;

    private ScheduledFuture<?> pollTask;

    public void start(@Nonnull UnstableRifts plugin) {
        plugin.getEventRegistry().registerGlobal(
                PlayerDisconnectEvent.class, this::onPlayerDisconnect);
        startPoller();
    }

    public void stop() {
        if (this.pollTask != null) {
            this.pollTask.cancel(false);
            this.pollTask = null;
        }
        ShopPromptHudService.clearAll();
    }

    public void onPlayerConnect(@Nonnull PlayerRef playerRef) {
        ShopPromptHudService.clear(playerRef);
    }

    private void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        ShopPromptHudService.clear(event.getPlayerRef());
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
            for (PlayerRef playerRef : OnlinePlayers.snapshot()) {
                pollSinglePlayer(playerRef);
            }
        } catch (Exception ignored) {
        }
    }

    private void pollSinglePlayer(@Nonnull PlayerRef playerRef) {
        if (!playerRef.isValid()) return;

        UnstableRifts plugin = UnstableRifts.getInstance();
        if (plugin == null) return;

        Game game = plugin.getGameManager().findGameForPlayer(playerRef.getUuid());
        if (game == null) return;

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) return;

        ref.getStore().getExternalData().getWorld().execute(() -> {
            try {
                Player player = ref.getStore().getComponent(ref, Player.getComponentType());
                if (player == null || player.wasRemoved()) return;

                TransformComponent transform = ref.getStore().getComponent(
                        ref, TransformComponent.getComponentType());
                if (transform == null) return;

                Vector3d playerPos = transform.getPosition();
                Level level = game.getCurrentLevel();
                if (level != null) {
                    DoorService.NearbyKeyDoor nearbyDoor = plugin.getDoorService().findNearbyKeyDoor(
                            level, playerPos, DoorService.KEY_INTERACTION_RADIUS);
                    if (nearbyDoor != null) {
                        int teamKeys = game.getTeamKeys();
                        ShopPromptHudService.show(player, playerRef,
                                "Locked Door",
                                teamKeys > 0
                                        ? "Press F to unlock with a team key (" + teamKeys + " ready)"
                                        : "Press F when your team has a key");
                        return;
                    }
                }

                double pickupRadiusSq = ItemPickupConfig.ITEM_PICKUP_RADIUS * ItemPickupConfig.ITEM_PICKUP_RADIUS;
                if (ItemPickupTracker.findClosestFKeyPickup(ref.getStore(), playerPos, pickupRadiusSq) != null) {
                    ShopPromptHudService.hide(player, playerRef);
                    return;
                }

                ShopService shopService = plugin.getShopService();
                ShopService.ShopRoomInventory nearby = shopService.findNearbyShop(game, playerPos);

                if (nearby != null) {
                    ShopPromptHudService.show(player, playerRef,
                            "Shop",
                            "Press F to open the shop");
                } else {
                    ShopPromptHudService.hide(player, playerRef);
                }
            } catch (Exception ignored) {
            }
        });
    }
}
