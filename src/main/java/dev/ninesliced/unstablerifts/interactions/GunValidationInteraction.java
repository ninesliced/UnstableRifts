package dev.ninesliced.unstablerifts.interactions;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.guns.GunItemMetadata;

import javax.annotation.Nonnull;

public final class GunValidationInteraction extends SimpleInstantInteraction {
    @Nonnull
    public static final BuilderCodec<GunValidationInteraction> CODEC;

    static {
        BuilderCodec.Builder<GunValidationInteraction> builder = BuilderCodec.builder(
                GunValidationInteraction.class,
                GunValidationInteraction::new,
                SimpleInstantInteraction.CODEC
        );

        builder.appendInherited(
                new KeyedCodec<Boolean>("RequireAmmo", Codec.BOOLEAN),
                (o, v) -> o.requireAmmo = v,
                o -> o.requireAmmo,
                (o, p) -> o.requireAmmo = p.requireAmmo
        ).add();

        CODEC = builder.build();
    }

    private boolean requireAmmo = true;

    public GunValidationInteraction() {
    }


    public GunValidationInteraction(@Nonnull String id, boolean requireAmmo,
                                    @Nonnull String nextId, @Nonnull String failedId) {
        super(id);
        this.requireAmmo = requireAmmo;
        this.next = nextId;
        this.failed = failedId;
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        ItemStack heldItem = context.getHeldItem();
        if (heldItem == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        if (!GunItemMetadata.hasInt(heldItem, GunItemMetadata.AMMO_KEY)) {
            return;
        }

        if (this.requireAmmo && GunItemMetadata.getInt(heldItem, GunItemMetadata.AMMO_KEY, 0) <= 0) {
            // Play dry fire click so the player knows the weapon is empty
            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            if (commandBuffer != null) {
                Ref<EntityStore> ref = context.getEntity();
                PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef != null) {
                    int soundIndex = SoundEvent.getAssetMap().getIndex("SFX_Blunderbuss_Miss");
                    SoundUtil.playSoundEvent2dToPlayer(playerRef, soundIndex, SoundCategory.SFX);
                }
            }
            context.getState().state = InteractionState.Failed;
        }
    }
}

