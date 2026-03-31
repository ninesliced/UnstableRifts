package dev.ninesliced.unstablerifts.party;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;

import javax.annotation.Nonnull;

public final class UnstableRiftsPartyPageSupplier implements OpenCustomUIInteraction.CustomPageSupplier {

    @Nonnull
    public static final BuilderCodec<UnstableRiftsPartyPageSupplier> CODEC =
            BuilderCodec.builder(UnstableRiftsPartyPageSupplier.class, UnstableRiftsPartyPageSupplier::new).build();

    @Override
    public CustomUIPage tryCreate(@Nonnull Ref<EntityStore> ref,
                                  @Nonnull ComponentAccessor<EntityStore> store,
                                  @Nonnull PlayerRef playerRef,
                                  @Nonnull InteractionContext context) {
        return new PartyUiPage(UnstableRifts.getInstance(), playerRef);
    }
}