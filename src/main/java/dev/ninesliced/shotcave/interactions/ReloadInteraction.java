package dev.ninesliced.shotcave.interactions;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import dev.ninesliced.shotcave.guns.GunItemMetadata;

import javax.annotation.Nonnull;

public final class ReloadInteraction extends SimpleInstantInteraction {
    @Nonnull
    public static final BuilderCodec<ReloadInteraction> CODEC;

    static {
        BuilderCodec.Builder<ReloadInteraction> builder = BuilderCodec.builder(
            ReloadInteraction.class,
            ReloadInteraction::new,
            SimpleInstantInteraction.CODEC
        );

        builder.appendInherited(
            new KeyedCodec<Integer>("ReloadAmount", Codec.INTEGER),
            (o, v) -> o.reloadAmount = v,
            o -> o.reloadAmount,
            (o, p) -> o.reloadAmount = p.reloadAmount
        ).addValidator(Validators.greaterThan(0)).add();

        builder.appendInherited(
            new KeyedCodec<Integer>("MaxAmmo", Codec.INTEGER),
            (o, v) -> o.maxAmmo = v,
            o -> o.maxAmmo,
            (o, p) -> o.maxAmmo = p.maxAmmo
        ).addValidator(Validators.greaterThan(0)).add();

        CODEC = builder.build();
    }

    private int reloadAmount = 30;
    private int maxAmmo = 30;

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        ItemStack heldItem = context.getHeldItem();
        if (heldItem == null) {
            return;
        }

        ItemStack updated = GunItemMetadata.ensureAmmo(heldItem, this.maxAmmo);
        int ammo = GunItemMetadata.getInt(updated, GunItemMetadata.AMMO_KEY, 0);
        int maxAmmo = GunItemMetadata.getInt(updated, GunItemMetadata.MAX_AMMO_KEY, this.maxAmmo);

        int newAmmo = Math.min(maxAmmo, ammo + this.reloadAmount);
        updated = GunItemMetadata.setInt(updated, GunItemMetadata.AMMO_KEY, newAmmo);
        GunItemMetadata.applyHeldItem(context, updated);
    }
}

