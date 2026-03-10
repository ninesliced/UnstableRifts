package dev.ninesliced.shotcave.interactions;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.crate.CrateDropGenerator;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Breaks soft-gather blocks (e.g. crates) on projectile hit and spawns
 * programmatic drops.
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

        String blockTypeId = blockType.getId();

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

        // Engine's ItemDropList doesn't resolve plugin-provided drop list JSONs,
        // so crate drops are spawned manually after the block break.
        if (blockTypeId != null && CrateDropGenerator.isCrate(blockTypeId)) {
            spawnCrateDrops(blockTypeId, blockPosition, world);
        }
    }

    private static void spawnCrateDrops(@Nonnull String blockTypeId,
            @Nonnull BlockPosition blockPosition,
            @Nonnull World world) {

        List<ItemStack> drops = CrateDropGenerator.generateDrops(blockTypeId);
        if (drops.isEmpty()) {
            return;
        }

        Vector3d dropPosition = new Vector3d(
                blockPosition.x + 0.5,
                blockPosition.y,
                blockPosition.z + 0.5);

        // Schedule on world thread so the store is not in a processing state.
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore().getStore();
            Holder<EntityStore>[] itemEntityHolders = ItemComponent.generateItemDrops(
                    store, drops, dropPosition, Vector3f.ZERO);
            if (itemEntityHolders.length > 0) {
                store.addEntities(itemEntityHolders, AddReason.SPAWN);
            }
        });
    }
}
