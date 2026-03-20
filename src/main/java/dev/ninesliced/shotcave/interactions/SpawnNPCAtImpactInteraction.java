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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import dev.ninesliced.shotcave.guns.DamageEffect;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.systems.SummonedEffectComponent;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;

/**
 * Spawns an NPC at the projectile's impact location (or the entity's current position as fallback).
 * Designed to be used inside ProjectileHit / ProjectileMiss interaction chains.
 * If the weapon has a DamageEffect, the spawned NPC will carry a {@link SummonedEffectComponent}
 * so its attacks apply DoT to targets.
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

        // Read damage effect and rarity from the weapon that fired this projectile
        ItemStack heldItem = context.getHeldItem();
        DamageEffect weaponEffect = heldItem != null ? GunItemMetadata.getEffect(heldItem) : DamageEffect.NONE;
        int effectOrdinal = weaponEffect.ordinal();
        int rarityOrdinal = heldItem != null ? GunItemMetadata.getRarity(heldItem).ordinal() : 0;

        position.add(spawnOffset);
        commandBuffer.run(store -> {
            var result = NPCPlugin.get().spawnNPC(store, entityId, null, position, Vector3f.ZERO);
            if (result != null && effectOrdinal > 0) {
                Ref<EntityStore> npcRef = result.left();
                if (npcRef != null && npcRef.isValid()) {
                    SummonedEffectComponent comp = new SummonedEffectComponent();
                    comp.setEffectOrdinal(effectOrdinal);
                    comp.setRarityOrdinal(rarityOrdinal);
                    commandBuffer.putComponent(npcRef, SummonedEffectComponent.getComponentType(), comp);
                }
            }
        });
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

