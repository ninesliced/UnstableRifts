package dev.ninesliced.unstablerifts.armor;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.dungeon.DoorService;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.dungeon.Level;
import dev.ninesliced.unstablerifts.hud.PortalPromptHudService;
import dev.ninesliced.unstablerifts.hud.RevivePromptHudService;
import dev.ninesliced.unstablerifts.pickup.ItemPickupConfig;
import dev.ninesliced.unstablerifts.pickup.ItemPickupHudService;
import dev.ninesliced.unstablerifts.pickup.ItemPickupTracker;
import dev.ninesliced.unstablerifts.shop.ShopPromptHudService;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Prevents the armor ability from stealing the interaction key when another
 * F-key action is already visible or currently available nearby.
 */
public final class ArmorAbilityActivationGuard {

    private ArmorAbilityActivationGuard() {
    }

    public static boolean hasVisiblePrompt(@Nonnull UUID playerUuid) {
        return ItemPickupHudService.isActive(playerUuid)
                || PortalPromptHudService.isActive(playerUuid)
                || ShopPromptHudService.isActive(playerUuid)
                || RevivePromptHudService.isActive(playerUuid);
    }

    public static boolean hasBlockingUseInteraction(@Nonnull PlayerRef playerRef,
                                                    @Nonnull Ref<EntityStore> ref,
                                                    @Nonnull ComponentAccessor<EntityStore> accessor) {
        if (hasVisiblePrompt(playerRef.getUuid()) || !ref.isValid()) {
            return true;
        }

        TransformComponent transform = accessor.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return false;
        }

        Vector3d playerPos = transform.getPosition();
        double pickupRadiusSq = ItemPickupConfig.ITEM_PICKUP_RADIUS * ItemPickupConfig.ITEM_PICKUP_RADIUS;
        if (ItemPickupTracker.findClosestFKeyPickup(ref.getStore(), playerPos, pickupRadiusSq) != null) {
            return true;
        }

        UnstableRifts plugin = UnstableRifts.getInstance();
        if (plugin == null) {
            return false;
        }

        Game game = plugin.getGameManager().findGameForPlayer(playerRef.getUuid());
        if (game == null) {
            return false;
        }

        if (plugin.getPortalInteractionService().resolvePrompt(game, playerRef.getUuid(), playerPos) != null) {
            return true;
        }

        Level level = game.getCurrentLevel();
        if (level != null
                && plugin.getDoorService().findNearbyKeyDoor(level, playerPos, DoorService.KEY_INTERACTION_RADIUS) != null) {
            return true;
        }

        return plugin.getShopService().findNearbyShop(game, playerPos) != null;
    }
}