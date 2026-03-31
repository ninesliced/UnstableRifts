package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Common supplier for block-targeted config pages.
 */
public abstract class AbstractTargetBlockPageSupplier implements OpenCustomUIInteraction.CustomPageSupplier {

    @Override
    public final CustomUIPage tryCreate(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull ComponentAccessor<EntityStore> store,
                                        @Nonnull PlayerRef playerRef,
                                        @Nonnull InteractionContext context) {
        return createPage(playerRef, context.getTargetBlock());
    }

    @Nonnull
    protected abstract CustomUIPage createPage(@Nonnull PlayerRef playerRef, @Nullable BlockPosition targetBlock);
}
