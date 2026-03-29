package dev.ninesliced.shotcave.dungeon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class PortalConfigPageSupplier implements OpenCustomUIInteraction.CustomPageSupplier {

    @Nonnull
    public static final BuilderCodec<PortalConfigPageSupplier> CODEC =
            BuilderCodec.builder(PortalConfigPageSupplier.class, PortalConfigPageSupplier::new).build();

    @Override
    public CustomUIPage tryCreate(@Nonnull Ref<EntityStore> ref,
                                  @Nonnull ComponentAccessor<EntityStore> store,
                                  @Nonnull PlayerRef playerRef,
                                  @Nonnull InteractionContext context) {
        BlockPosition targetBlock = context.getTargetBlock();
        return new PortalConfigPage(playerRef, targetBlock);
    }
}
