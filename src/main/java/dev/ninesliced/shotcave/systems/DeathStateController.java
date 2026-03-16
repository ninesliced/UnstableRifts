package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Applies and removes engine-level restrictions for custom dungeon death states.
 */
public final class DeathStateController {

    private DeathStateController() {
    }

    public static void apply(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull Store<EntityStore> store,
                             @Nonnull Ref<EntityStore> ref,
                             boolean freezeMovement) {
        if (!ref.isValid()) {
            return;
        }

        if (freezeMovement) {
            commandBuffer.ensureComponent(ref, Frozen.getComponentType());
        } else {
            commandBuffer.tryRemoveComponent(ref, Frozen.getComponentType());
        }

        commandBuffer.ensureComponent(ref, Invulnerable.getComponentType());
        commandBuffer.ensureComponent(ref, Intangible.getComponentType());

        InteractionManager interactionManager = store.getComponent(
                ref, InteractionModule.get().getInteractionManagerComponent());
        if (interactionManager != null) {
            interactionManager.clear();
        }
    }

    public static void clear(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return;
        }

        commandBuffer.tryRemoveComponent(ref, Frozen.getComponentType());
        commandBuffer.tryRemoveComponent(ref, Invulnerable.getComponentType());
        commandBuffer.tryRemoveComponent(ref, Intangible.getComponentType());
    }

    public static void apply(@Nonnull Store<EntityStore> store,
                             @Nonnull Ref<EntityStore> ref,
                             boolean freezeMovement) {
        if (!ref.isValid()) {
            return;
        }

        if (freezeMovement) {
            store.ensureComponent(ref, Frozen.getComponentType());
        } else {
            store.tryRemoveComponent(ref, Frozen.getComponentType());
        }

        store.ensureComponent(ref, Invulnerable.getComponentType());
        store.ensureComponent(ref, Intangible.getComponentType());

        InteractionManager interactionManager = store.getComponent(
                ref, InteractionModule.get().getInteractionManagerComponent());
        if (interactionManager != null) {
            interactionManager.clear();
        }
    }

    public static void clear(@Nonnull Store<EntityStore> store,
                             @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return;
        }

        store.tryRemoveComponent(ref, Frozen.getComponentType());
        store.tryRemoveComponent(ref, Invulnerable.getComponentType());
        store.tryRemoveComponent(ref, Intangible.getComponentType());
    }
}