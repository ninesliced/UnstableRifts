package dev.ninesliced.shotcave.pickup;

import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.shotcave.hud.MultiHudCompat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Show/hide logic for the item pickup HUD with per-player state deduplication.
 */
public final class ItemPickupHudService {

    private static final String HUD_IDENTIFIER = "Shotcave_ItemPickup";
    private static final long STATE_HIDDEN = 0L;

    private static final ConcurrentHashMap<UUID, Long> LAST_STATE = new ConcurrentHashMap<>();

    private ItemPickupHudService() {
    }

    public static void show(@Nonnull Player player,
            @Nonnull PlayerRef playerRef,
            @Nonnull String itemName,
            @Nullable String itemIconPath,
            int itemQuantity) {

        long state = computeState(itemName, itemIconPath, itemQuantity);
        UUID uuid = playerRef.getUuid();

        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == state) {
            return;
        }
        LAST_STATE.put(uuid, state);

        ItemPickupHud hud = new ItemPickupHud(playerRef, itemName, itemIconPath, itemQuantity);

        if (!MultiHudCompat.setHud(player, playerRef, HUD_IDENTIFIER, hud)) {
            player.getHudManager().setCustomHud(playerRef, hud);
        }
    }

    public static void hide(@Nonnull Player player,
            @Nonnull PlayerRef playerRef) {

        UUID uuid = playerRef.getUuid();

        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == STATE_HIDDEN) {
            return;
        }
        LAST_STATE.put(uuid, STATE_HIDDEN);

        if (!MultiHudCompat.hideHud(player, playerRef, HUD_IDENTIFIER)) {
            player.getHudManager().setCustomHud(playerRef, null);
        }
    }

    public static boolean isActive(@Nonnull UUID playerUuid) {
        Long state = LAST_STATE.get(playerUuid);
        return state != null && state != STATE_HIDDEN;
    }

    public static void clear(@Nonnull PlayerRef playerRef) {
        LAST_STATE.remove(playerRef.getUuid());
    }

    public static void clearAll() {
        LAST_STATE.clear();
    }

    private static long computeState(@Nullable String itemName,
            @Nullable String itemIconPath,
            int itemQuantity) {
        long h = 7919L;
        h = 31L * h + (itemName == null ? 0 : itemName.hashCode());
        h = 31L * h + (itemIconPath == null ? 0 : itemIconPath.hashCode());
        h = 31L * h + itemQuantity;
        return h;
    }
}
