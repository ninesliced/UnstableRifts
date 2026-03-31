package dev.ninesliced.unstablerifts.interactions;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.hud.AmmoHudService;

import javax.annotation.Nonnull;

/**
 * Hides the custom ammo HUD.
 */
public final class HideAmmoHudInteraction extends SimpleInstantInteraction {
    @Nonnull
    public static final BuilderCodec<HideAmmoHudInteraction> CODEC =
            BuilderCodec.builder(HideAmmoHudInteraction.class, HideAmmoHudInteraction::new, SimpleInstantInteraction.CODEC).build();

    public HideAmmoHudInteraction() {
    }

    public HideAmmoHudInteraction(@Nonnull String id) {
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

        AmmoHudService.hide(player, playerRef);
    }
}
