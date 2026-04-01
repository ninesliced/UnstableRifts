package dev.ninesliced.unstablerifts.interactions;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.hud.AmmoHudService;

import javax.annotation.Nonnull;

/**
 * Updates the custom ammo HUD overlay with the current weapon state.
 */
public final class UpdateAmmoHudInteraction extends SimpleInstantInteraction {
    @Nonnull
    public static final BuilderCodec<UpdateAmmoHudInteraction> CODEC =
            BuilderCodec.builder(UpdateAmmoHudInteraction.class, UpdateAmmoHudInteraction::new, SimpleInstantInteraction.CODEC).build();

    public UpdateAmmoHudInteraction() {
    }

    public UpdateAmmoHudInteraction(@Nonnull String id) {
        super(id);
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (context.getCommandBuffer() == null) {
            return;
        }

        Player player = context.getCommandBuffer().getComponent(context.getEntity(), Player.getComponentType());
        PlayerRef playerRef = context.getCommandBuffer().getComponent(context.getEntity(), PlayerRef.getComponentType());
        if (player == null || playerRef == null) {
            return;
        }

        boolean crouching = false;
        MovementStatesComponent movementStates = context.getCommandBuffer().getComponent(
                context.getEntity(), MovementStatesComponent.getComponentType());
        if (movementStates != null) {
            crouching = movementStates.getMovementStates().crouching;
        }

        ItemStack heldItem = context.getHeldItem();
        AmmoHudService.updateForHeldItem(player, playerRef, heldItem, crouching, context.getEntity());
    }
}
