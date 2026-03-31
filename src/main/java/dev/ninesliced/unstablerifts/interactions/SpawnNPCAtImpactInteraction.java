package dev.ninesliced.unstablerifts.interactions;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
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
import dev.ninesliced.unstablerifts.guns.DamageEffect;
import dev.ninesliced.unstablerifts.guns.GunItemMetadata;
import dev.ninesliced.unstablerifts.systems.SummonedEffectComponent;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
                new KeyedCodec<Double>("SpawnOffsetX", Codec.DOUBLE),
                (o, v) -> o.spawnOffsetX = v != null ? v : 0.0,
                o -> o.spawnOffsetX,
                (o, p) -> o.spawnOffsetX = p.spawnOffsetX
        ).add();

        builder.appendInherited(
                new KeyedCodec<Double>("SpawnOffsetY", Codec.DOUBLE),
                (o, v) -> o.spawnOffsetY = v != null ? v : 0.0,
                o -> o.spawnOffsetY,
                (o, p) -> o.spawnOffsetY = p.spawnOffsetY
        ).add();

        builder.appendInherited(
                new KeyedCodec<Double>("SpawnOffsetZ", Codec.DOUBLE),
                (o, v) -> o.spawnOffsetZ = v != null ? v : 0.0,
                o -> o.spawnOffsetZ,
                (o, p) -> o.spawnOffsetZ = p.spawnOffsetZ
        ).add();

        CODEC = builder.build();
    }

    private String entityId;
    private double spawnOffsetX;
    private double spawnOffsetY;
    private double spawnOffsetZ;

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

        SpawnPosition position = resolveImpactPosition(context, commandBuffer);
        if (position == null) {
            return;
        }

        // Read damage effect and rarity from the weapon that fired this projectile
        ItemStack heldItem = context.getHeldItem();
        DamageEffect weaponEffect = heldItem != null ? GunItemMetadata.getEffect(heldItem) : DamageEffect.NONE;
        int effectOrdinal = weaponEffect.ordinal();
        int rarityOrdinal = heldItem != null ? GunItemMetadata.getRarity(heldItem).ordinal() : 0;

        position.add(spawnOffsetX, spawnOffsetY, spawnOffsetZ);
        commandBuffer.run(store -> {
            var spawnPosition = new Vector3d(position.x, position.y, position.z);
            var result = NPCPlugin.get().spawnNPC(store, entityId, null, spawnPosition, Rotation3f.ZERO);
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

    @Nullable
    private SpawnPosition resolveImpactPosition(@Nonnull InteractionContext context, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        InteractionChain chain = context.getChain();
        if (chain != null) {
            InteractionChainData chainData = chain.getChainData();
            if (chainData != null && chainData.hitLocation != null) {
                return new SpawnPosition(chainData.hitLocation.x, chainData.hitLocation.y, chainData.hitLocation.z);
            }
        }

        Ref<EntityStore> ref = context.getEntity();
        if (ref != null && ref.isValid()) {
            TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
            if (transform != null) {
                var position = transform.getPosition();
                return new SpawnPosition(position.x, position.y, position.z);
            }
        }

        return null;
    }

    private static final class SpawnPosition {
        private double x;
        private double y;
        private double z;

        private SpawnPosition(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private void add(double ox, double oy, double oz) {
            this.x += ox;
            this.y += oy;
            this.z += oz;
        }
    }
}
