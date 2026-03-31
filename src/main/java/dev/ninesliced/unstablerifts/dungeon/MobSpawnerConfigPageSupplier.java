package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class MobSpawnerConfigPageSupplier extends AbstractTargetBlockPageSupplier {

    @Nonnull
    public static final BuilderCodec<MobSpawnerConfigPageSupplier> CODEC =
            BuilderCodec.builder(MobSpawnerConfigPageSupplier.class, MobSpawnerConfigPageSupplier::new).build();

    @Override
    protected CustomUIPage createPage(@Nonnull PlayerRef playerRef, @Nullable BlockPosition targetBlock) {
        return new MobSpawnerConfigPage(playerRef, targetBlock);
    }
}
