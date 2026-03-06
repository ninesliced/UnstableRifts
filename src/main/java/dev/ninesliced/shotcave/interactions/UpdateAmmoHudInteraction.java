package dev.ninesliced.shotcave.interactions;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.hud.ShotcaveHud;

import javax.annotation.Nonnull;

/**
 * Placeholder for ammo HUD update.
 * The class is intentionally isolated so a UI implementation can be plugged in without touching gun logic.
 */
public final class UpdateAmmoHudInteraction extends SimpleInstantInteraction {
    @Nonnull
    public static final BuilderCodec<UpdateAmmoHudInteraction> CODEC =
        BuilderCodec.builder(UpdateAmmoHudInteraction.class, UpdateAmmoHudInteraction::new, SimpleInstantInteraction.CODEC).build();

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

        ItemStack heldItem = context.getHeldItem();
        if (heldItem == null) {
            return;
        }

        int ammo = GunItemMetadata.getInt(heldItem, GunItemMetadata.AMMO_KEY, -1);
        int maxAmmo = GunItemMetadata.getInt(heldItem, GunItemMetadata.MAX_AMMO_KEY, -1);
        if (ammo < 0 || maxAmmo <= 0) {
            return;
        }

        player.getHudManager().showHudComponents(playerRef, HudComponent.AmmoIndicator);
        player.getHudManager().setCustomHud(playerRef, new ShotcaveHud(playerRef, ammo, maxAmmo));
    }
}
