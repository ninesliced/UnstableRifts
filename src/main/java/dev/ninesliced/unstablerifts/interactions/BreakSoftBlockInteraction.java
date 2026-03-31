package dev.ninesliced.unstablerifts.interactions;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
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
import dev.ninesliced.unstablerifts.crate.DestructibleBlockConfig;
import org.joml.Vector3i;

import javax.annotation.Nonnull;

/**
 * Breaks destructible blocks (crates and barrels) on projectile hit.
 * Only blocks registered in {@code destructible_blocks.json} are affected.
 * Drop spawning and barrel gas effects are handled by {@code CrateBreakDropSystem}
 * via the resulting {@code BreakBlockEvent}.
 */
public final class BreakSoftBlockInteraction extends SimpleInstantInteraction {

    @Nonnull
    public static final BuilderCodec<BreakSoftBlockInteraction> CODEC = BuilderCodec.builder(
                    BreakSoftBlockInteraction.class,
                    BreakSoftBlockInteraction::new,
                    SimpleInstantInteraction.CODEC)
            .documentation("Breaks the target block if it is a destructible block (crate or barrel).")
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

        String blockTypeId = blockType.getId();
        if (blockTypeId == null) {
            return;
        }

        if (!DestructibleBlockConfig.isDestructible(blockTypeId)) {
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

        BlockHarvestUtils.performBlockBreak(entityRef, null, position, chunkReference,
                commandBuffer, chunkStore.getStore());
    }
}
