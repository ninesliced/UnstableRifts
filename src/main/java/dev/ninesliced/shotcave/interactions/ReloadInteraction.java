package dev.ninesliced.shotcave.interactions;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.InteractionEffects;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import dev.ninesliced.shotcave.guns.GunItemMetadata;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
        ).add();

        CODEC = builder.build();
    }

    private int reloadAmount = 0;

    public ReloadInteraction() {}

    public ReloadInteraction(@Nonnull String id, int reloadAmount, float reloadRunTime,
                              @Nonnull String nextId,
                              @Nullable String worldSfx, @Nullable String localSfx,
                              @Nullable String itemAnimationId) {
        super(id);
        this.reloadAmount = reloadAmount;
        this.runTime = reloadRunTime;
        this.next = nextId;
        this.effects = new ReloadEffects(worldSfx, localSfx, itemAnimationId);
    }

    private static final class ReloadEffects extends InteractionEffects {
        ReloadEffects(@Nullable String worldSfx, @Nullable String localSfx,
                      @Nullable String itemAnimationId) {
            this.worldSoundEventId = worldSfx;
            this.localSoundEventId = localSfx;
            this.itemAnimationId = itemAnimationId;
            this.processConfig();
        }
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        ItemStack heldItem = context.getHeldItem();
        if (heldItem == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        int maxAmmo = GunItemMetadata.getInt(heldItem, GunItemMetadata.MAX_AMMO_KEY, -1);
        if (maxAmmo <= 0) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        int ammo = GunItemMetadata.getInt(heldItem, GunItemMetadata.AMMO_KEY, maxAmmo);

        if (ammo >= maxAmmo) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        int effectiveReloadAmount = (this.reloadAmount <= 0) ? maxAmmo : this.reloadAmount;
        int newAmmo = Math.min(maxAmmo, ammo + effectiveReloadAmount);
        ItemStack updated = GunItemMetadata.setInt(heldItem, GunItemMetadata.AMMO_KEY, newAmmo);
        GunItemMetadata.applyHeldItem(context, updated);
    }
}

