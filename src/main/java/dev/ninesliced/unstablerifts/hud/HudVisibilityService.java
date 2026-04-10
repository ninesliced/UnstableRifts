package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.pickup.ItemPickupHudService;
import dev.ninesliced.unstablerifts.shop.ShopPromptHudService;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player HUD visibility toggle. When hidden, all HUD services
 * skip rendering, and background pollers will not re-show HUDs.
 */
public final class HudVisibilityService {

    private static final Set<UUID> HIDDEN_PLAYERS = ConcurrentHashMap.newKeySet();

    private HudVisibilityService() {
    }

    /**
     * Returns true if the player's HUD is currently suppressed.
     */
    public static boolean isHidden(@Nonnull UUID uuid) {
        return HIDDEN_PLAYERS.contains(uuid);
    }

    /**
     * Toggles HUD visibility for the player.
     *
     * @return true if HUD is now visible, false if now hidden
     */
    public static boolean toggle(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        if (HIDDEN_PLAYERS.remove(uuid)) {
            // Was hidden → now visible: clear cached states so pollers re-show
            AmmoHudService.clear(playerRef);
            RevivePromptHudService.clear(playerRef);
            ItemPickupHudService.clear(playerRef);
            PortalPromptHudService.clear(playerRef);
            ShopPromptHudService.clear(playerRef);
            return true;
        } else {
            // Was visible → now hidden: hide everything
            HIDDEN_PLAYERS.add(uuid);
            hideAll(player, playerRef);
            return false;
        }
    }

    /**
     * Hides all known HUD elements for a player.
     */
    private static void hideAll(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        // Weapon + armor HUD
        AmmoHudService.hide(player, playerRef);

        // Hide armor HUD separately
        AmmoHudService.hideArmorHud(player, playerRef);

        // Pickup
        ItemPickupHudService.hide(player, playerRef);

        // Revive prompt
        RevivePromptHudService.hide(player, playerRef);

        // Portal prompt
        PortalPromptHudService.hide(player, playerRef);

        // Shared interaction prompt (shop / key door)
        ShopPromptHudService.hide(player, playerRef);

        // MultiHud-based overlays
        player.getHudManager().removeCustomHud(playerRef, ChallengeHud.HUD_ID);
        player.getHudManager().removeCustomHud(playerRef, PartyStatusHud.HUD_ID);
        player.getHudManager().removeCustomHud(playerRef, DungeonInfoHud.HUD_ID);
        player.getHudManager().removeCustomHud(playerRef, BossFightHud.HUD_ID);
        player.getHudManager().removeCustomHud(playerRef, DeathCountdownHud.HUD_ID);
        player.getHudManager().removeCustomHud(playerRef, ReviveProgressHud.HUD_ID);

        // Native HUD component
        player.getHudManager().hideHudComponents(playerRef, HudComponent.AmmoIndicator);
    }

    /**
     * Clean up when a player disconnects.
     */
    public static void clear(@Nonnull UUID uuid) {
        HIDDEN_PLAYERS.remove(uuid);
    }
}
