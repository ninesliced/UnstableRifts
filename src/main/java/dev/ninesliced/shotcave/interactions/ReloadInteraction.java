package dev.ninesliced.shotcave.interactions;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
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

        builder.appendInherited(
                new KeyedCodec<Integer>("MaxAmmo", Codec.INTEGER),
                (o, v) -> o.maxAmmo = v,
                o -> o.maxAmmo,
                (o, p) -> o.maxAmmo = p.maxAmmo
        ).add();

        CODEC = builder.build();
    }

    private int reloadAmount = 0;
    private int maxAmmo = 0;

    public ReloadInteraction() {}

    public ReloadInteraction(@Nonnull String id, int reloadAmount, float reloadRunTime,
                              @Nonnull String nextId,
                              @Nullable String worldSfx, @Nullable String localSfx,
                              @Nullable String itemAnimationId, int maxAmmo) {
        super(id);
        this.reloadAmount = reloadAmount;
        this.maxAmmo = maxAmmo;
        this.runTime = 0;
        this.next = nextId;
        // No effects — auto-reload is disabled, so no animation/sound should play
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        // Auto-reload disabled — ammo can only be refilled via Ammo item pickup.
        context.getState().state = InteractionState.Failed;
    }
}
