package dev.ninesliced.shotcave.interactions;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.InteractionChainData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;

import javax.annotation.Nonnull;

/**
 * Spawns an NPC at the projectile's impact location (or the entity's current position as fallback).
 * Designed to be used inside ProjectileHit / ProjectileMiss interaction chains.
 */
public final class SpawnNPCAtImpactInteraction extends SimpleInstantInteraction {
    @Nonnull
    public static final BuilderCodec<SpawnNPCAtImpactInteraction> CODEC;

    static {
        BuilderCodec.Builder<SpawnNPCAtImpactInteraction> builder = BuilderCodec.builder(
                SpawnNPCAtImpactInteraction.class,
                SpawnNPCAtImpactInteraction::new,
                SimpleInstantInteraction.CODEC
        );

        builder.appendInherited(
                new KeyedCodec<>("EntityId", Codec.STRING),
                (o, v) -> o.entityId = v,
                o -> o.entityId,
                (o, p) -> o.entityId = p.entityId
        ).add();

        builder.appendInherited(
                new KeyedCodec<>("SpawnOffset", Vector3d.CODEC),
                (o, v) -> o.spawnOffset.assign(v),
                o -> o.spawnOffset,
                (o, p) -> o.spawnOffset.assign(p.spawnOffset)
        ).add();

        CODEC = builder.build();
    }

    @Nonnull
    private final Vector3d spawnOffset = new Vector3d();
    private String entityId;

    @Override
    public boolean needsRemoteSync() {
        return false;
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (entityId == null || entityId.isBlank()) {
            return;
        }

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            return;
        }

        Vector3d position = resolveImpactPosition(context, commandBuffer);
        if (position == null) {
            return;
        }

        position.add(spawnOffset);
        commandBuffer.run(store ->
                NPCPlugin.get().spawnNPC(store, entityId, null, position, Vector3f.ZERO)
        );
    }

    private Vector3d resolveImpactPosition(@Nonnull InteractionContext context, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        InteractionChain chain = context.getChain();
        if (chain != null) {
            InteractionChainData chainData = chain.getChainData();
            if (chainData != null && chainData.hitLocation != null) {
                return new Vector3d(chainData.hitLocation.x, chainData.hitLocation.y, chainData.hitLocation.z);
            }
        }

        Ref<EntityStore> ref = context.getEntity();
        if (ref != null && ref.isValid()) {
            TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
            if (transform != null) {
                return transform.getPosition().clone();
            }
        }

        return null;
    }
}

