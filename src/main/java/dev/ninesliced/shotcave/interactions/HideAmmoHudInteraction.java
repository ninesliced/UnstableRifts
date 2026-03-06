package dev.ninesliced.shotcave.interactions;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

/**
 * Placeholder for ammo HUD hide behavior.
 */
public final class HideAmmoHudInteraction extends SimpleInstantInteraction {
    @Nonnull
    public static final BuilderCodec<HideAmmoHudInteraction> CODEC =
        BuilderCodec.builder(HideAmmoHudInteraction.class, HideAmmoHudInteraction::new, SimpleInstantInteraction.CODEC).build();

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

        player.getHudManager().hideHudComponents(playerRef, HudComponent.AmmoIndicator);
        player.getHudManager().setCustomHud(playerRef, null);
    }
}
