package dev.ninesliced.shotcave.hud;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.entity.LivingEntityInventoryChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;

import javax.annotation.Nonnull;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps ammo HUD synchronized outside of interactions.
 */
public final class AmmoHudRuntime {
    private ScheduledFuture<?> hudPollTask;

    public void start(@Nonnull Shotcave plugin) {
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
        plugin.getEventRegistry().registerGlobal(LivingEntityInventoryChangeEvent.class, this::onInventoryChanged);
        startHudPoller();
    }

    public void stop() {
        if (this.hudPollTask != null) {
            this.hudPollTask.cancel(false);
            this.hudPollTask = null;
        }
    }

    public void onPlayerConnect(@Nonnull PlayerRef playerRef) {
        AmmoHudService.clear(playerRef);
        refreshHeldItemHud(playerRef);
    }

    private void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        AmmoHudService.clear(event.getPlayerRef());
    }

    private void onInventoryChanged(@Nonnull LivingEntityInventoryChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        refreshHeldItemHud((Player) event.getEntity());
    }

    private void startHudPoller() {
        if (this.hudPollTask != null) {
            this.hudPollTask.cancel(false);
        }
        this.hudPollTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::pollPlayersHud, 200L, 200L, TimeUnit.MILLISECONDS);
    }

    private void pollPlayersHud() {
        for (PlayerRef playerRef : Universe.get().getPlayers()) {
            refreshHeldItemHud(playerRef);
        }
    }

    private void refreshHeldItemHud(@Nonnull PlayerRef playerRef) {
        if (!playerRef.isValid()) {
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        ref.getStore().getExternalData().getWorld().execute(() -> {
            Player player = ref.getStore().getComponent(ref, Player.getComponentType());
            if (player == null || player.wasRemoved()) {
                return;
            }

            ItemStack heldItem = null;
            if (player.getInventory() != null) {
                heldItem = player.getInventory().getItemInHand();
            }
            AmmoHudService.updateForHeldItem(player, playerRef, heldItem);
        });
    }

    private void refreshHeldItemHud(@Nonnull Player player) {
        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        ItemStack heldItem = null;
        if (player.getInventory() != null) {
            heldItem = player.getInventory().getItemInHand();
        }

        AmmoHudService.updateForHeldItem(player, playerRef, heldItem);
    }
}

