package dev.ninesliced.shotcave.interactions;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.math.vector.Vector2d;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.math.vector.Vector4d;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.InteractionChainData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.meta.DynamicMetaStore;
import com.hypixel.hytale.server.core.modules.collision.CollisionMath;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.selector.Selector;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import dev.ninesliced.shotcave.guns.AimAssistHelper;
import dev.ninesliced.shotcave.guns.DamageEffect;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.guns.WeaponRarity;
import dev.ninesliced.shotcave.guns.WeaponModifierType;
import dev.ninesliced.shotcave.systems.DamageEffectRuntime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ChainLightningInteraction extends SimpleInstantInteraction {
    @Nonnull
    public static final BuilderCodec<ChainLightningInteraction> CODEC;

    static {
        BuilderCodec.Builder<ChainLightningInteraction> builder = BuilderCodec.builder(
                ChainLightningInteraction.class,
                ChainLightningInteraction::new,
                SimpleInstantInteraction.CODEC);

        builder.documentation("Chains projectile damage across nearby living entities.");
        builder.appendInherited(
                new KeyedCodec<String>("DamageRoot", Codec.STRING),
                (o, v) -> o.damageRoot = v,
                o -> o.damageRoot,
                (o, p) -> o.damageRoot = p.damageRoot).addValidator(Validators.nonNull()).add();

        builder.appendInherited(
                new KeyedCodec<Double>("JumpRange", Codec.DOUBLE),
                (o, v) -> o.jumpRange = v,
                o -> o.jumpRange,
                (o, p) -> o.jumpRange = p.jumpRange).addValidator(Validators.greaterThan(0.0)).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("MaxTargets", Codec.INTEGER),
                (o, v) -> o.maxTargets = v,
                o -> o.maxTargets,
                (o, p) -> o.maxTargets = p.maxTargets).addValidator(Validators.greaterThan(0)).add();

        builder.appendInherited(
                new KeyedCodec<Boolean>("IncludeInitialTarget", Codec.BOOLEAN),
                (o, v) -> o.includeInitialTarget = v,
                o -> o.includeInitialTarget,
                (o, p) -> o.includeInitialTarget = p.includeInitialTarget).add();

        builder.appendInherited(
                new KeyedCodec<String>("BeamParticleId", Codec.STRING),
                (o, v) -> o.beamParticleId = v,
                o -> o.beamParticleId,
                (o, p) -> o.beamParticleId = p.beamParticleId).addValidator(Validators.nonNull()).add();

        builder.appendInherited(
                new KeyedCodec<Double>("BeamStepDistance", Codec.DOUBLE),
                (o, v) -> o.beamStepDistance = v,
                o -> o.beamStepDistance,
                (o, p) -> o.beamStepDistance = p.beamStepDistance).addValidator(Validators.greaterThan(0.05)).add();

        builder.appendInherited(
                new KeyedCodec<Double>("BeamHeightOffset", Codec.DOUBLE),
                (o, v) -> o.beamHeightOffset = v,
                o -> o.beamHeightOffset,
                (o, p) -> o.beamHeightOffset = p.beamHeightOffset).add();

        builder.appendInherited(
                new KeyedCodec<Double>("BeamForwardOffset", Codec.DOUBLE),
                (o, v) -> o.beamForwardOffset = v,
                o -> o.beamForwardOffset,
                (o, p) -> o.beamForwardOffset = p.beamForwardOffset).add();

        builder.appendInherited(
                new KeyedCodec<Double>("BeamRightOffset", Codec.DOUBLE),
                (o, v) -> o.beamRightOffset = v,
                o -> o.beamRightOffset,
                (o, p) -> o.beamRightOffset = p.beamRightOffset).add();

        builder.appendInherited(
                new KeyedCodec<Double>("BeamParticleScale", Codec.DOUBLE),
                (o, v) -> o.beamParticleScale = v,
                o -> o.beamParticleScale,
                (o, p) -> o.beamParticleScale = p.beamParticleScale).addValidator(Validators.greaterThan(0.05)).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("MaxDistance", Codec.INTEGER),
                (o, v) -> o.maxDistance = v,
                o -> o.maxDistance,
                (o, p) -> o.maxDistance = p.maxDistance).addValidator(Validators.greaterThan(1)).add();

        builder.appendInherited(
                new KeyedCodec<Boolean>("AimAssist", Codec.BOOLEAN),
                (o, v) -> o.aimAssist = v,
                o -> o.aimAssist,
                (o, p) -> o.aimAssist = p.aimAssist).add();

        builder.appendInherited(
                new KeyedCodec<String>("MissRoot", Codec.STRING),
                (o, v) -> o.missRoot = v,
                o -> o.missRoot,
                (o, p) -> o.missRoot = p.missRoot).add();

        builder.appendInherited(
                new KeyedCodec<Boolean>("UseAmmo", Codec.BOOLEAN),
                (o, v) -> o.useAmmo = v,
                o -> o.useAmmo,
                (o, p) -> o.useAmmo = p.useAmmo).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("MaxAmmo", Codec.INTEGER),
                (o, v) -> o.maxAmmo = v,
                o -> o.maxAmmo,
                (o, p) -> o.maxAmmo = p.maxAmmo).addValidator(Validators.greaterThan(0)).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("AmmoPerShot", Codec.INTEGER),
                (o, v) -> o.ammoPerShot = v,
                o -> o.ammoPerShot,
                (o, p) -> o.ammoPerShot = p.ammoPerShot).addValidator(Validators.greaterThan(0)).add();

        CODEC = builder.build();
    }

    private final Color beamColor = new Color((byte) -1, (byte) -11, (byte) -86);
    private String damageRoot = "Root_Shotcave_Taser_Chain_Damage";
    private double jumpRange = 5.0;
    private int maxTargets = 5;
    private boolean includeInitialTarget = true;
    private String beamParticleId = "NatureBeam";
    private double beamStepDistance = 0.7;
    private double beamHeightOffset = 1.35;
    private double beamForwardOffset = 0.0;
    private double beamRightOffset = 0.0;
    private double beamParticleScale = 0.35;
    private int maxDistance = 30;
    @Nullable
    private String missRoot = null;
    private boolean aimAssist = false;
    private boolean useAmmo = false;
    private int maxAmmo = 8;
    private int ammoPerShot = 1;

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            return;
        }

        // Read modifier bonuses from held item BSON
        ItemStack heldItem = context.getHeldItem();
        int effectiveMaxDistance = this.maxDistance;
        int effectiveMaxTargets = this.maxTargets;
        int effectiveMaxAmmo = this.maxAmmo;

        if (heldItem != null) {
            double rangeBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.MAX_RANGE);
            effectiveMaxDistance = (int) Math.round(this.maxDistance * (1.0 + rangeBonus));

            double targetBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.ADDITIONAL_BULLETS);
            effectiveMaxTargets = this.maxTargets + (int) targetBonus;
            effectiveMaxAmmo = GunItemMetadata.getEffectiveMaxAmmo(heldItem, this.maxAmmo);
        }

        if (this.useAmmo) {
            if (heldItem == null) {
                return;
            }

            ItemStack updated = GunItemMetadata.ensureAmmo(heldItem, this.maxAmmo, effectiveMaxAmmo);
            int ammo = GunItemMetadata.getInt(updated, GunItemMetadata.AMMO_KEY, effectiveMaxAmmo);
            if (ammo < this.ammoPerShot) {
                return;
            }

            updated = GunItemMetadata.setInt(updated, GunItemMetadata.AMMO_KEY, ammo - this.ammoPerShot);
            GunItemMetadata.applyHeldItem(context, updated);
        }

        Vector3d from = getEmitterPosition(commandBuffer, context.getEntity());
        if (from == null) {
            return;
        }

        // Compute look direction with optional aim-assist
        Vector3d lookDir = getLookDirection(commandBuffer, context.getEntity());
        if (this.aimAssist) {
            Vector3d assisted = AimAssistHelper.computeAssistedDirectionNearest(
                    commandBuffer, context, from, lookDir, (double) effectiveMaxDistance);
            if (assisted != null) {
                lookDir = assisted;
            }
        }

        RaycastHit hit = traceFirstHit(commandBuffer, context, from, lookDir, effectiveMaxDistance);
        spawnBeamSegment(from, hit.position, commandBuffer);

        if (hit.target == null) {
            // No entity hit — fork miss interaction so BreakSoftBlockInteraction
            // can destroy crates/barrels at the block hit position.
            if (hit.block != null && this.missRoot != null && !this.missRoot.isEmpty()) {
                RootInteraction miss = RootInteraction.getRootInteractionOrUnknown(this.missRoot);
                if (miss != null) {
                    forkMissInteraction(context, miss, hit.block, hit.position);
                }
            }
            return;
        }

        RootInteraction damageRootInteraction = RootInteraction.getRootInteractionOrUnknown(this.damageRoot);
        if (damageRootInteraction == null) {
            return;
        }

        WeaponRarity chainRarity = heldItem != null ? GunItemMetadata.getRarity(heldItem) : WeaponRarity.BASIC;

        List<Ref<EntityStore>> chainTargets = buildChain(commandBuffer, context, hit.target, effectiveMaxTargets);
        renderBeamChain(commandBuffer, chainTargets, hit.position);

        for (Ref<EntityStore> targetRef : chainTargets) {
            Vector3d hitPosition = getPosition(commandBuffer, targetRef);
            if (hitPosition == null) {
                continue;
            }
            hitPosition = hitPosition.clone().add(0.0, this.beamHeightOffset, 0.0);
            forkDamageInteraction(context, damageRootInteraction, targetRef, hitPosition);
            DamageEffectRuntime.apply(commandBuffer, targetRef, DamageEffect.ELECTRICITY, chainRarity);
        }
    }

    private List<Ref<EntityStore>> buildChain(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionContext context,
            @Nonnull Ref<EntityStore> initialTarget,
            int effectiveMaxTargets) {
        List<Ref<EntityStore>> out = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();

        Ref<EntityStore> current = initialTarget;
        if (this.includeInitialTarget) {
            out.add(initialTarget);
            visited.add(initialTarget.getIndex());
        }

        while (out.size() < effectiveMaxTargets) {
            Vector3d currentPos = getPosition(commandBuffer, current);
            if (currentPos == null) {
                break;
            }

            Ref<EntityStore> next = findNearestValidTarget(commandBuffer, context, currentPos, visited);
            if (next == null) {
                break;
            }

            out.add(next);
            visited.add(next.getIndex());
            current = next;
        }

        return out;
    }

    private Ref<EntityStore> findNearestValidTarget(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionContext context,
            @Nonnull Vector3d from,
            @Nonnull Set<Integer> visited) {
        Ref<EntityStore>[] best = new Ref[] { null };
        double[] bestDistanceSq = new double[] { Double.MAX_VALUE };

        Selector.selectNearbyEntities(commandBuffer, from, this.jumpRange, candidate -> {
            if (!candidate.isValid() || visited.contains(candidate.getIndex())) {
                return;
            }
            if (candidate.equals(context.getEntity()) || candidate.equals(context.getOwningEntity())) {
                return;
            }
            Entity e = EntityUtils.getEntity(candidate, commandBuffer);
            if (!(e instanceof LivingEntity)) {
                return;
            }

            Vector3d pos = getPosition(commandBuffer, candidate);
            if (pos == null) {
                return;
            }

            double d = from.distanceSquaredTo(pos);
            if (d < bestDistanceSq[0]) {
                bestDistanceSq[0] = d;
                best[0] = candidate;
            }
        }, ref -> true);

        return best[0];
    }

    private void forkDamageInteraction(@Nonnull InteractionContext context,
            @Nonnull RootInteraction root,
            @Nonnull Ref<EntityStore> target,
            @Nonnull Vector3d hitPosition) {
        InteractionContext forkContext = context.duplicate();
        DynamicMetaStore<InteractionContext> metaStore = forkContext.getMetaStore();
        metaStore.putMetaObject(Interaction.TARGET_ENTITY, target);
        metaStore.putMetaObject(Interaction.HIT_LOCATION,
                new Vector4d(hitPosition.x, hitPosition.y, hitPosition.z, 1.0));
        metaStore.removeMetaObject(Interaction.TARGET_BLOCK);
        metaStore.removeMetaObject(Interaction.TARGET_BLOCK_RAW);

        context.fork(new InteractionChainData(), context.getChain().getType(), forkContext, root, false);
    }

    private void forkMissInteraction(@Nonnull InteractionContext context,
            @Nonnull RootInteraction root,
            @Nonnull BlockPosition rawBlock,
            @Nonnull Vector3d hitPosition) {
        InteractionContext forkContext = context.duplicate();
        DynamicMetaStore<InteractionContext> metaStore = forkContext.getMetaStore();
        metaStore.putMetaObject(Interaction.HIT_LOCATION,
                new Vector4d(hitPosition.x, hitPosition.y, hitPosition.z, 1.0));
        metaStore.putMetaObject(Interaction.TARGET_BLOCK_RAW, rawBlock);
        metaStore.putMetaObject(Interaction.TARGET_BLOCK, rawBlock);
        metaStore.removeMetaObject(Interaction.TARGET_ENTITY);

        context.fork(new InteractionChainData(), context.getChain().getType(), forkContext, root, false);
    }

    @Nullable
    private Vector3d getPosition(@Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> ref) {
        TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        return transform.getPosition();
    }

    @Nullable
    private Vector3d getEmitterPosition(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> ref) {
        Vector3d position = getPosition(commandBuffer, ref);
        if (position == null) {
            return null;
        }
        Vector3d pos = position.clone().add(0.0, this.beamHeightOffset, 0.0);

        boolean hasForward = this.beamForwardOffset > 0.001 || this.beamForwardOffset < -0.001;
        boolean hasRight = this.beamRightOffset > 0.001 || this.beamRightOffset < -0.001;

        if (hasForward || hasRight) {
            HeadRotation headRotation = commandBuffer.getComponent(ref, HeadRotation.getComponentType());
            if (headRotation != null) {
                float yaw = headRotation.getRotation().getYaw();
                double sinYaw = Math.sin(yaw);
                double cosYaw = Math.cos(yaw);
                double offsetX = sinYaw * this.beamForwardOffset + cosYaw * this.beamRightOffset;
                double offsetZ = cosYaw * this.beamForwardOffset - sinYaw * this.beamRightOffset;
                pos.add(offsetX, 0.0, offsetZ);
            }
        }

        return pos;
    }

    @Nonnull
    private RaycastHit traceFirstHit(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionContext context,
            @Nonnull Vector3d from,
            @Nonnull Vector3d direction,
            int effectiveMaxDistance) {
        Vector3d missPosition = from.clone().addScaled(direction, (double) effectiveMaxDistance);

        final Vector3d[] entityHitPos = new Vector3d[] { null };
        final Ref<EntityStore>[] entityHitRef = new Ref[] { null };
        final double[] entityHitDistanceSq = new double[] { Double.MAX_VALUE };

        Vector2d minMax = new Vector2d();
        Vector3d searchCenter = from.clone().addScaled(direction, (double) effectiveMaxDistance * 0.5);
        Selector.selectNearbyEntities(commandBuffer, searchCenter, (double) effectiveMaxDistance * 0.6, candidate -> {
            if (!candidate.isValid()) {
                return;
            }
            if (candidate.equals(context.getEntity()) || candidate.equals(context.getOwningEntity())) {
                return;
            }

            Entity entity = EntityUtils.getEntity(candidate, commandBuffer);
            if (!(entity instanceof LivingEntity)) {
                return;
            }

            BoundingBox boundingBox = commandBuffer.getComponent(candidate, BoundingBox.getComponentType());
            TransformComponent transform = commandBuffer.getComponent(candidate, TransformComponent.getComponentType());
            if (boundingBox == null || transform == null) {
                return;
            }

            Vector3d ePos = transform.getPosition();
            if (!CollisionMath.intersectRayAABB(from, direction, ePos.getX(), ePos.getY(), ePos.getZ(),
                    boundingBox.getBoundingBox(), minMax)) {
                return;
            }

            double t = minMax.x;
            if (t < 0.0 || t > (double) effectiveMaxDistance) {
                return;
            }

            double hitX = from.x + direction.x * t;
            double hitY = from.y + direction.y * t;
            double hitZ = from.z + direction.z * t;
            double hitDistanceSq = from.distanceSquaredTo(hitX, hitY, hitZ);
            if (hitDistanceSq >= entityHitDistanceSq[0]) {
                return;
            }

            entityHitDistanceSq[0] = hitDistanceSq;
            entityHitPos[0] = new Vector3d(hitX, hitY, hitZ);
            entityHitRef[0] = candidate;
        }, ref -> true);

        World world = commandBuffer.getExternalData().getWorld();
        Vector3i block = TargetUtil.getTargetBlock(world, (id, fluidId) -> id != 0,
                from.x, from.y, from.z, direction.x, direction.y, direction.z, effectiveMaxDistance);

        if (block != null) {
            Vector3d blockHitPos = new Vector3d((double) block.x + 0.5, (double) block.y + 0.5, (double) block.z + 0.5);
            double blockDistanceSq = from.distanceSquaredTo(blockHitPos);
            if (entityHitRef[0] == null || blockDistanceSq < entityHitDistanceSq[0]) {
                BlockPosition bp = new BlockPosition(block.x, block.y, block.z);
                return new RaycastHit(blockHitPos, null, bp);
            }
        }

        if (entityHitRef[0] != null && entityHitPos[0] != null) {
            return new RaycastHit(entityHitPos[0], entityHitRef[0], null);
        }

        return new RaycastHit(missPosition, null, null);
    }

    @Nonnull
    private Vector3d getLookDirection(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> ref) {
        HeadRotation headRotation = commandBuffer.getComponent(ref, HeadRotation.getComponentType());
        if (headRotation == null) {
            return new Vector3d(0.0, 0.0, 1.0);
        }

        Vector3d direction = new Vector3d(headRotation.getRotation().getYaw(), headRotation.getRotation().getPitch());
        if (direction.squaredLength() <= 0.000001) {
            return new Vector3d(0.0, 0.0, 1.0);
        }
        return direction;
    }

    private void renderBeamChain(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull List<Ref<EntityStore>> chainTargets,
            @Nonnull Vector3d chainStart) {
        if (chainTargets.isEmpty()) {
            return;
        }

        Vector3d from = chainStart.clone();
        int startIndex = this.includeInitialTarget ? 1 : 0;
        for (int i = startIndex; i < chainTargets.size(); i++) {
            Vector3d to = getPosition(commandBuffer, chainTargets.get(i));
            if (to == null) {
                continue;
            }

            to = to.clone().add(0.0, this.beamHeightOffset, 0.0);
            spawnBeamSegment(from, to, commandBuffer);
            from = to;
        }
    }

    private void spawnBeamSegment(@Nonnull Vector3d from,
            @Nonnull Vector3d to,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Vector3d delta = to.clone().subtract(from);
        double distance = from.distanceTo(to);
        if (distance <= 0.001) {
            spawnLineParticle(from.clone(), 0.0f, 0.0f, 0.0f, commandBuffer);
            return;
        }

        float yaw = (float) Math.atan2(delta.x, delta.z);
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float pitch = (float) -Math.atan2(delta.y, horizontalDistance);

        int steps = Math.max(1, (int) Math.ceil(distance / this.beamStepDistance));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            Vector3d point = from.clone().addScaled(delta, t);
            spawnLineParticle(point, yaw, pitch, 0.0f, commandBuffer);
        }
    }

    private void spawnLineParticle(@Nonnull Vector3d point,
            float yaw,
            float pitch,
            float roll,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource = commandBuffer
                .getResource(EntityModule.get().getPlayerSpatialResourceType());
        List<Ref<EntityStore>> playerRefs = SpatialResource.getThreadLocalReferenceList();
        playerSpatialResource.getSpatialStructure().collect(point, ParticleUtil.DEFAULT_PARTICLE_DISTANCE, playerRefs);

        ParticleUtil.spawnParticleEffect(
                this.beamParticleId,
                point.x,
                point.y,
                point.z,
                yaw,
                pitch,
                roll,
                (float) this.beamParticleScale,
                this.beamColor,
                null,
                playerRefs,
                commandBuffer);
    }

    private static final class RaycastHit {
        @Nonnull
        private final Vector3d position;
        @Nullable
        private final Ref<EntityStore> target;
        @Nullable
        private final BlockPosition block;

        private RaycastHit(@Nonnull Vector3d position, @Nullable Ref<EntityStore> target, @Nullable BlockPosition block) {
            this.position = position;
            this.target = target;
            this.block = block;
        }
    }
}
