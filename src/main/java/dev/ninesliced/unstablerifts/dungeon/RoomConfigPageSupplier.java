package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class RoomConfigPageSupplier extends AbstractTargetBlockPageSupplier {

    @Nonnull
    public static final BuilderCodec<RoomConfigPageSupplier> CODEC =
            BuilderCodec.builder(RoomConfigPageSupplier.class, RoomConfigPageSupplier::new).build();

    @Override
    protected CustomUIPage createPage(@Nonnull PlayerRef playerRef, @Nullable BlockPosition targetBlock) {
        return new RoomConfigPage(playerRef, targetBlock);
    }
}
