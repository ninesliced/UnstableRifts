package dev.ninesliced.shotcave.interactions;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Breaks the target block if it is a "soft" gatherable block (e.g. crates,
 * barrels).
 * <p>
 * Intended for use in projectile miss interaction chains so that shots can
 * destroy soft furniture blocks on impact without affecting normal terrain.
 */
public final class BreakSoftBlockInteraction extends SimpleInstantInteraction {

    @Nonnull
    public static final BuilderCodec<BreakSoftBlockInteraction> CODEC = BuilderCodec.builder(
            BreakSoftBlockInteraction.class,
            BreakSoftBlockInteraction::new,
            SimpleInstantInteraction.CODEC)
            .documentation("Breaks the target block if it is a soft-gather block (e.g. crates).")
            .build();

    public BreakSoftBlockInteraction() {
    }

    @Override
    public boolean needsRemoteSync() {
        return false;
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler) {
        BlockPosition blockPosition = context.getTargetBlock();
        if (blockPosition == null) {
            return;
        }

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            return;
        }

        World world = commandBuffer.getExternalData().getWorld();

        BlockType blockType = world.getBlockType(blockPosition.x, blockPosition.y, blockPosition.z);
        if (blockType == null) {
            return;
        }

        BlockGathering gathering = blockType.getGathering();
        if (gathering == null || !gathering.isSoft()) {
            return;
        }

        ChunkStore chunkStore = world.getChunkStore();
        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockPosition.x, blockPosition.z);
        Ref<ChunkStore> chunkReference = chunkStore.getChunkReference(chunkIndex);
        if (chunkReference == null) {
            return;
        }

        Ref<EntityStore> entityRef = context.getEntity();
        Vector3i position = new Vector3i(blockPosition.x, blockPosition.y, blockPosition.z);
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();

        BlockHarvestUtils.performBlockBreak(entityRef, null, position, chunkReference, commandBuffer, chunkStoreStore);
    }
}
