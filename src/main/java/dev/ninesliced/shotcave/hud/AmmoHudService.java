package dev.ninesliced.shotcave.hud;

import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.shotcave.guns.GunItemMetadata;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized ammo HUD update/hide behavior.
 */
public final class AmmoHudService {

    private static final long STATE_HIDDEN = 0L;
    private static final ConcurrentHashMap<UUID, Long> LAST_STATE = new ConcurrentHashMap<>();

    private AmmoHudService() {
    }

    public static void updateForHeldItem(@Nonnull Player player, @Nonnull PlayerRef playerRef, @Nullable ItemStack heldItem) {
        if (heldItem == null) {
            hide(player, playerRef);
            return;
        }

        String itemId = heldItem.getItemId();
        int defaultMaxAmmo = getDefaultMaxAmmo(itemId);
        if (defaultMaxAmmo <= 0) {
            hide(player, playerRef);
            return;
        }

        int maxAmmo = GunItemMetadata.getInt(heldItem, GunItemMetadata.MAX_AMMO_KEY, defaultMaxAmmo);
        if (maxAmmo <= 0) {
            maxAmmo = defaultMaxAmmo;
        }

        int ammo = GunItemMetadata.getInt(heldItem, GunItemMetadata.AMMO_KEY, maxAmmo);
        ammo = Math.max(0, Math.min(ammo, maxAmmo));

        long state = computeState(itemId, ammo, maxAmmo);
        UUID uuid = playerRef.getUuid();
        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == state) {
            return;
        }
        LAST_STATE.put(uuid, state);

        String weaponName = extractWeaponName(heldItem);
        ShotcaveHud hud = new ShotcaveHud(playerRef, ammo, maxAmmo, weaponName);

        player.getHudManager().showHudComponents(playerRef, HudComponent.AmmoIndicator);
        if (!MultiHudCompat.setHud(player, playerRef, hud)) {
            player.getHudManager().setCustomHud(playerRef, hud);
        }
    }

    public static void hide(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == STATE_HIDDEN) {
            return;
        }
        LAST_STATE.put(uuid, STATE_HIDDEN);

        player.getHudManager().hideHudComponents(playerRef, HudComponent.AmmoIndicator);
        if (!MultiHudCompat.hideHud(player, playerRef)) {
            player.getHudManager().setCustomHud(playerRef, null);
        }
    }

    public static void clear(@Nonnull PlayerRef playerRef) {
        LAST_STATE.remove(playerRef.getUuid());
    }

    private static long computeState(@Nullable String itemId, int ammo, int maxAmmo) {
        long h = 1125899906842597L;
        h = 31L * h + (itemId == null ? 0 : itemId.hashCode());
        h = 31L * h + ammo;
        h = 31L * h + maxAmmo;
        return h;
    }

    private static int getDefaultMaxAmmo(@Nullable String itemId) {
        if (itemId == null) {
            return -1;
        }

        return switch (itemId) {
            case "Weapon_Pistol_Shotcave" -> 12;
            case "Weapon_Sniper_Shotcave" -> 5;
            case "Weapon_Taser_Shotcave" -> 8;
            default -> -1;
        };
    }

    @Nullable
    private static String extractWeaponName(@Nonnull ItemStack item) {
        String id = item.getItemId();
        if (id == null || id.isBlank()) {
            return null;
        }

        String name = id;
        if (name.startsWith("Weapon_")) {
            name = name.substring(7);
        }
        if (name.endsWith("_Shotcave")) {
            name = name.substring(0, name.length() - 9);
        }
        return name.replace('_', ' ').toUpperCase();
    }
}
