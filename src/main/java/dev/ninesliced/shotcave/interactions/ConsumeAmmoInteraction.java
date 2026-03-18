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

/**
 * Consumes a configurable amount of ammo from the held weapon.
 */
public final class ConsumeAmmoInteraction extends SimpleInstantInteraction {
    @Nonnull
    public static final BuilderCodec<ConsumeAmmoInteraction> CODEC;

    static {
        BuilderCodec.Builder<ConsumeAmmoInteraction> builder = BuilderCodec.builder(
                ConsumeAmmoInteraction.class,
                ConsumeAmmoInteraction::new,
                SimpleInstantInteraction.CODEC
        );

        builder.appendInherited(
                new KeyedCodec<Integer>("AmmoPerShot", Codec.INTEGER),
                (o, v) -> o.ammoPerShot = v,
                o -> o.ammoPerShot,
                (o, p) -> o.ammoPerShot = p.ammoPerShot
        ).addValidator(Validators.greaterThan(0)).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("MaxAmmo", Codec.INTEGER),
                (o, v) -> o.maxAmmo = v,
                o -> o.maxAmmo,
                (o, p) -> o.maxAmmo = p.maxAmmo
        ).addValidator(Validators.greaterThan(0)).add();

        CODEC = builder.build();
    }

    private int ammoPerShot = 1;
    private int maxAmmo = 5;

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        ItemStack heldItem = context.getHeldItem();
        if (heldItem == null) {
            return;
        }

        int effectiveMaxAmmo = GunItemMetadata.getEffectiveMaxAmmo(heldItem, this.maxAmmo);

        ItemStack updated = GunItemMetadata.ensureAmmo(heldItem, this.maxAmmo, effectiveMaxAmmo);
        int ammo = GunItemMetadata.getInt(updated, GunItemMetadata.AMMO_KEY, effectiveMaxAmmo);
        int newAmmo = Math.max(0, ammo - this.ammoPerShot);
        updated = GunItemMetadata.setInt(updated, GunItemMetadata.AMMO_KEY, newAmmo);
        GunItemMetadata.applyHeldItem(context, updated);
    }
}

