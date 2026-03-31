package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Restores server-side movement settings after death-state overrides.
 */
public final class DeathMovementController {

    private DeathMovementController() {
    }

    public static void restore(@Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nullable PlayerRef playerRef) {
        if (!ref.isValid()) {
            return;
        }

        if (playerRef != null && playerRef.isValid()) {
            UnstableRifts unstablerifts = UnstableRifts.getInstance();
            if (unstablerifts != null) {
                unstablerifts.getCameraService().refreshMovementProfile(playerRef);
            } else {
                MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
                if (movementManager != null) {
                    movementManager.resetDefaultsAndUpdate(ref, store);
                }
            }
        }
    }
}