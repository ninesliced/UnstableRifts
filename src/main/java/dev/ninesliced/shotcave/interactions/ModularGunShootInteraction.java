package dev.ninesliced.shotcave.interactions;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validator;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.spatial.SpatialResource;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.joml.Vector4d;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.InteractionChainData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.meta.DynamicMetaStore;
import com.hypixel.hytale.server.core.modules.collision.CollisionMath;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.selector.Selector;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import dev.ninesliced.shotcave.guns.AimAssistHelper;
import dev.ninesliced.shotcave.guns.DamageEffect;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.guns.WeaponCategory;
import dev.ninesliced.shotcave.guns.WeaponDefinition;
import dev.ninesliced.shotcave.guns.WeaponModifierType;
import dev.ninesliced.shotcave.guns.WeaponRarity;
import dev.ninesliced.shotcave.guns.WeaponDefinitions;
import dev.ninesliced.shotcave.ShotcaveLog;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ninesliced.shotcave.systems.DamageEffectRuntime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A data-driven hitscan gun interaction intended as a clean baseline for
 * modular firearms.
 * This keeps weapon behavior in JSON and avoids hardcoding per-weapon logic in
 * Java.
 */
public final class ModularGunShootInteraction extends SimpleInteraction {
    private static final HytaleLogger LOG = ShotcaveLog.forModule("GunShoot");

    @Nonnull
    public static final BuilderCodec<ModularGunShootInteraction> CODEC;

    static {
        BuilderCodec.Builder<ModularGunShootInteraction> builder = BuilderCodec.builder(
                ModularGunShootInteraction.class,
                ModularGunShootInteraction::new,
                SimpleInteraction.CODEC);

        builder.documentation("Configurable hitscan gun behavior with optional trail particles.");
        builder.appendInherited(
                new KeyedCodec<String>("DamageRoot", Codec.STRING),
                (o, v) -> o.damageRoot = v,
                o -> o.damageRoot,
                (o, p) -> o.damageRoot = p.damageRoot).addValidator(Validators.nonNull()).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("Range", Codec.INTEGER),
                (o, v) -> o.range = v,
                o -> o.range,
                (o, p) -> o.range = p.range).addValidator(Validators.greaterThan(1)).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("Pellets", Codec.INTEGER),
                (o, v) -> o.pellets = v,
                o -> o.pellets,
                (o, p) -> o.pellets = p.pellets).addValidator(Validators.greaterThan(0)).add();

        builder.appendInherited(
                new KeyedCodec<Double>("SpreadDegrees", Codec.DOUBLE),
                (o, v) -> o.spreadDegrees = v,
                o -> o.spreadDegrees,
                (o, p) -> o.spreadDegrees = p.spreadDegrees)
                .addValidator((Validator<Double>) Validators.greaterThanOrEqual(0.0)).add();

        builder.appendInherited(
                new KeyedCodec<String>("MissRoot", Codec.STRING),
                (o, v) -> o.missRoot = v,
                o -> o.missRoot,
                (o, p) -> o.missRoot = p.missRoot).add();

        builder.appendInherited(
                new KeyedCodec<String>("TrailParticleId", Codec.STRING),
                (o, v) -> o.trailParticleId = v,
                o -> o.trailParticleId,
                (o, p) -> o.trailParticleId = p.trailParticleId).add();

        builder.appendInherited(
                new KeyedCodec<Double>("TrailStepDistance", Codec.DOUBLE),
                (o, v) -> o.trailStepDistance = v,
                o -> o.trailStepDistance,
                (o, p) -> o.trailStepDistance = p.trailStepDistance).addValidator(Validators.greaterThan(0.05)).add();

        builder.appendInherited(
                new KeyedCodec<Double>("TrailScale", Codec.DOUBLE),
                (o, v) -> o.trailScale = v,
                o -> o.trailScale,
                (o, p) -> o.trailScale = p.trailScale).addValidator(Validators.greaterThan(0.05)).add();

        builder.appendInherited(
                new KeyedCodec<Double>("MuzzleHeightOffset", Codec.DOUBLE),
                (o, v) -> o.muzzleHeightOffset = v,
                o -> o.muzzleHeightOffset,
                (o, p) -> o.muzzleHeightOffset = p.muzzleHeightOffset).add();

        builder.appendInherited(
                new KeyedCodec<Double>("MuzzleForwardOffset", Codec.DOUBLE),
                (o, v) -> o.muzzleForwardOffset = v,
                o -> o.muzzleForwardOffset,
                (o, p) -> o.muzzleForwardOffset = p.muzzleForwardOffset).add();

        builder.appendInherited(
                new KeyedCodec<Double>("MuzzleRightOffset", Codec.DOUBLE),
                (o, v) -> o.muzzleRightOffset = v,
                o -> o.muzzleRightOffset,
                (o, p) -> o.muzzleRightOffset = p.muzzleRightOffset).add();

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

        builder.appendInherited(
                new KeyedCodec<Boolean>("AimAssist", Codec.BOOLEAN),
                (o, v) -> o.aimAssist = v,
                o -> o.aimAssist,
                (o, p) -> o.aimAssist = p.aimAssist).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("TrailColorR", Codec.INTEGER),
                (o, v) -> o.trailColorR = v,
                o -> o.trailColorR,
                (o, p) -> o.trailColorR = p.trailColorR).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("TrailColorG", Codec.INTEGER),
                (o, v) -> o.trailColorG = v,
                o -> o.trailColorG,
                (o, p) -> o.trailColorG = p.trailColorG).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("TrailColorB", Codec.INTEGER),
                (o, v) -> o.trailColorB = v,
                o -> o.trailColorB,
                (o, p) -> o.trailColorB = p.trailColorB).add();

        builder.appendInherited(
                new KeyedCodec<String>("ImpactParticleId", Codec.STRING),
                (o, v) -> o.impactParticleId = v,
                o -> o.impactParticleId,
                (o, p) -> o.impactParticleId = p.impactParticleId).add();

        builder.appendInherited(
                new KeyedCodec<Double>("ImpactParticleScale", Codec.DOUBLE),
                (o, v) -> o.impactParticleScale = v,
                o -> o.impactParticleScale,
                (o, p) -> o.impactParticleScale = p.impactParticleScale).addValidator(Validators.greaterThan(0.0))
                .add();

        builder.appendInherited(
                new KeyedCodec<Integer>("BurstCount", Codec.INTEGER),
                (o, v) -> o.burstCount = v,
                o -> o.burstCount,
                (o, p) -> o.burstCount = p.burstCount).add();

        builder.appendInherited(
                new KeyedCodec<Double>("BurstInterval", Codec.DOUBLE),
                (o, v) -> o.burstInterval = v,
                o -> o.burstInterval,
                (o, p) -> o.burstInterval = p.burstInterval).add();

        CODEC = builder.build();
    }

    private String damageRoot = "Root_Shotcave_Pistol_Projectile_Hit";
    private int range = 45;
    private int pellets = 1;
    private double spreadDegrees = 0.0;
    @Nullable
    private String missRoot;
    @Nullable
    private String trailParticleId;
    private double trailStepDistance = 0.8;
    private double trailScale = 0.2;
    private double muzzleHeightOffset = 1.35;
    private double muzzleForwardOffset = 0.0;
    private double muzzleRightOffset = 0.0;
    private boolean useAmmo = false;
    private int maxAmmo = 30;
    private int ammoPerShot = 1;
    private boolean aimAssist = false;
    private int trailColorR = 255;
    private int trailColorG = 255;
    private int trailColorB = 255;
    @Nullable
    private String impactParticleId;
    private double impactParticleScale = 0.5;
    private int burstCount = 1;
    private double burstInterval = 0.0;

    private static final MetaKey<int[]> BURST_SHOTS_KEY = Interaction.CONTEXT_META_REGISTRY.registerMetaObject(data -> null);

    @Override
    protected void tick0(boolean firstRun, float time, @Nonnull InteractionType type,
            @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            super.tick0(firstRun, time, type, context, cooldownHandler);
            return;
        }

        if (firstRun) {
            ItemStack heldItem = context.getHeldItem();

            // Apply attack speed bonus once per trigger pull
            if (heldItem != null) {
                double speedBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.ATTACK_SPEED);
                if (speedBonus > 0.001) {
                    CooldownHandler.Cooldown shootCooldown = cooldownHandler.getCooldown("Shoot");
                    if (shootCooldown != null) {
                        float baseCd = shootCooldown.getCooldown();
                        float reducedCd = (float) (baseCd * (1.0 - speedBonus));
                        if (reducedCd < 0.05f) reducedCd = 0.05f;
                        shootCooldown.setCooldownMax(reducedCd);
                    }
                }
            }

            // Consume ammo once for the entire burst
            if (this.useAmmo) {
                if (heldItem == null) {
                    super.tick0(firstRun, time, type, context, cooldownHandler);
                    return;
                }
                int effectiveMaxAmmo = GunItemMetadata.getEffectiveMaxAmmo(context, heldItem, this.maxAmmo);
                ItemStack updated = GunItemMetadata.ensureAmmo(heldItem, this.maxAmmo, effectiveMaxAmmo);
                int ammo = GunItemMetadata.getInt(updated, GunItemMetadata.AMMO_KEY, effectiveMaxAmmo);
                if (ammo < this.ammoPerShot) {
                    super.tick0(firstRun, time, type, context, cooldownHandler);
                    return;
                }
                updated = GunItemMetadata.setInt(updated, GunItemMetadata.AMMO_KEY, ammo - this.ammoPerShot);
                GunItemMetadata.applyHeldItem(context, updated);
            }

            context.getMetaStore().putMetaObject(BURST_SHOTS_KEY, new int[]{0});
        }

        int[] counter = context.getMetaStore().getIfPresentMetaObject(BURST_SHOTS_KEY);
        if (counter == null) {
            super.tick0(firstRun, time, type, context, cooldownHandler);
            return;
        }

        int expectedShots;
        if (this.burstCount <= 1 || this.burstInterval <= 0.0) {
            expectedShots = this.burstCount;
        } else {
            expectedShots = Math.min(this.burstCount, (int) (time / this.burstInterval) + 1);
        }

        while (counter[0] < expectedShots) {
            fireRound(commandBuffer, context);
            counter[0]++;
        }

        super.tick0(firstRun, time, type, context, cooldownHandler);
    }

    private void fireRound(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionContext context) {
        ItemStack heldItem = context.getHeldItem();

        int effectiveRange = this.range;
        int effectivePellets = this.pellets;
        double effectiveSpread = this.spreadDegrees;
        int effectiveTrailR = this.trailColorR;
        int effectiveTrailG = this.trailColorG;
        int effectiveTrailB = this.trailColorB;
        double effectiveKnockback = 0.0;
        WeaponDefinition weaponDefinition = null;

        if (heldItem != null) {
            weaponDefinition = WeaponDefinitions.getById(heldItem.getItemId());
            double rangeBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.MAX_RANGE);
            effectiveRange = (int) Math.round(this.range * (1.0 + rangeBonus));

            double precisionBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.PRECISION);
            effectiveSpread = this.spreadDegrees * Math.max(0.0, 1.0 - precisionBonus);

            double pelletBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.ADDITIONAL_BULLETS);
            effectivePellets = this.pellets + (int) pelletBonus;

            DamageEffect effect = GunItemMetadata.getEffect(heldItem);
            if (effect != DamageEffect.NONE) {
                effectiveTrailR = effect.getTrailR();
                effectiveTrailG = effect.getTrailG();
                effectiveTrailB = effect.getTrailB();
            } else if (weaponDefinition != null && weaponDefinition.getCategory() == WeaponCategory.LASER) {
                effectiveTrailR = 255;
                effectiveTrailG = 255;
                effectiveTrailB = 255;
            }

            if (weaponDefinition != null) {
                double knockbackBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.KNOCKBACK);
                effectiveKnockback = weaponDefinition.getBaseKnockback() * (1.0 + knockbackBonus);
            }
        }

        DamageEffect dotEffect = heldItem != null ? GunItemMetadata.getEffect(heldItem) : DamageEffect.NONE;
        WeaponRarity weaponRarity = heldItem != null ? GunItemMetadata.getRarity(heldItem) : WeaponRarity.BASIC;

        Vector3d start = getMuzzlePosition(commandBuffer, context.getEntity());
        if (start == null) {
            return;
        }

        RootInteraction damage = RootInteraction.getRootInteractionOrUnknown(this.damageRoot);
        if (damage == null) {
            return;
        }

        RootInteraction miss = null;
        if (hasText(this.missRoot)) {
            miss = RootInteraction.getRootInteractionOrUnknown(this.missRoot);
        }

        Vector3d assistedBaseDir = null;
        if (this.aimAssist) {
            Vector3d rawLook = getShotDirection(commandBuffer, context.getEntity());
            assistedBaseDir = AimAssistHelper.computeAssistedDirectionNearest(
                    commandBuffer, context, start, rawLook, (double) effectiveRange);
        }

        final int shotRange = effectiveRange;
        final double shotSpread = effectiveSpread;
        final int shotTrailR = effectiveTrailR;
        final int shotTrailG = effectiveTrailG;
        final int shotTrailB = effectiveTrailB;
        Set<Integer> knockedTargets = effectiveKnockback > 0.001 ? new HashSet<>() : null;
        Set<Integer> effectedTargets = dotEffect.hasDoT() ? new HashSet<>() : null;

        for (int i = 0; i < effectivePellets; i++) {
            Vector3d direction;
            if (assistedBaseDir != null) {
                direction = applySpread(assistedBaseDir, shotSpread);
            } else {
                direction = getShotDirection(commandBuffer, context.getEntity(), shotSpread);
            }
            ShotHit hit = traceShot(commandBuffer, context, start, direction, shotRange);

            if (hasText(this.trailParticleId)) {
                spawnTrail(start, hit.position, commandBuffer, shotTrailR, shotTrailG, shotTrailB);
            }

            if (hit.target != null) {
                forkHitInteraction(context, damage, hit.target, hit.position);
                if (knockedTargets != null && knockedTargets.add(hit.target.getIndex())) {
                    applyKnockback(commandBuffer, context.getEntity(), hit.target, effectiveKnockback);
                }
                if (effectedTargets != null && effectedTargets.add(hit.target.getIndex())) {
                    DamageEffectRuntime.apply(commandBuffer, hit.target, dotEffect, weaponRarity);
                }
                continue;
            }

            if (miss != null && hit.block != null) {
                spawnImpactParticle(hit.position, commandBuffer);
                forkMissInteraction(context, miss, hit.block, hit.position);
            }
        }
    }

    @Nullable
    private Vector3d getMuzzlePosition(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> ref) {
        TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        Vector3d pos = new Vector3d(transform.getPosition()).add(0.0, this.muzzleHeightOffset, 0.0);

        boolean hasForward = this.muzzleForwardOffset > 0.001 || this.muzzleForwardOffset < -0.001;
        boolean hasRight = this.muzzleRightOffset > 0.001 || this.muzzleRightOffset < -0.001;

        if (hasForward || hasRight) {
            HeadRotation headRotation = commandBuffer.getComponent(ref, HeadRotation.getComponentType());
            if (headRotation != null) {
                float yaw = headRotation.getRotation().yaw();
                double sinYaw = Math.sin(yaw);
                double cosYaw = Math.cos(yaw);
                // Forward: (sinYaw, cosYaw), Right: (cosYaw, -sinYaw)
                double offsetX = sinYaw * this.muzzleForwardOffset + cosYaw * this.muzzleRightOffset;
                double offsetZ = cosYaw * this.muzzleForwardOffset - sinYaw * this.muzzleRightOffset;
                pos.add(offsetX, 0.0, offsetZ);
            }
        }

        return pos;
    }

    @Nonnull
    private Vector3d getShotDirection(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> ref) {
        return getShotDirection(commandBuffer, ref, this.spreadDegrees);
    }

    @Nonnull
    private Vector3d getShotDirection(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> ref, double effectiveSpread) {
        HeadRotation headRotation = commandBuffer.getComponent(ref, HeadRotation.getComponentType());
        if (headRotation == null) {
            return new Vector3d(0.0, 0.0, 1.0);
        }

        float yaw = headRotation.getRotation().yaw();
        float pitch = headRotation.getRotation().pitch();
        double horizontal = Math.cos(pitch);
        Vector3d direction = new Vector3d(
                horizontal * -Math.sin(yaw),
                Math.sin(pitch),
                horizontal * -Math.cos(yaw));
        return applySpread(direction, effectiveSpread);
    }

    @Nonnull
    private Vector3d applySpread(@Nonnull Vector3d direction, double effectiveSpread) {
        double spreadRadians = Math.toRadians(effectiveSpread);
        if (spreadRadians > 0.000001) {
            direction = new Vector3d(direction);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            direction.x += random.nextDouble(-spreadRadians, spreadRadians);
            direction.y += random.nextDouble(-spreadRadians, spreadRadians);
            direction.z += random.nextDouble(-spreadRadians, spreadRadians);
            if (direction.lengthSquared() > 0.000001) {
                direction.normalize();
            }
        }
        return direction;
    }

    @Nonnull
    private ShotHit traceShot(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionContext context,
            @Nonnull Vector3d from,
            @Nonnull Vector3d direction,
            int effectiveRange) {
        final Vector3d[] entityHitPos = new Vector3d[] { null };
        final Ref<EntityStore>[] entityHitRef = new Ref[] { null };
        final double[] entityHitDistanceSq = new double[] { Double.MAX_VALUE };

        Vector2d minMax = new Vector2d();
        Vector3d searchCenter = new Vector3d(from).fma((double) effectiveRange * 0.5, direction);
        Selector.selectNearbyEntities(commandBuffer, searchCenter, (double) effectiveRange * 0.6, candidate -> {
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
            if (!CollisionMath.intersectRayAABB(from, direction, ePos.x, ePos.y, ePos.z,
                    boundingBox.getBoundingBox(), minMax)) {
                return;
            }

            double t = minMax.x;
            if (t < 0.0 || t > (double) effectiveRange) {
                return;
            }

            double hitX = from.x + direction.x * t;
            double hitY = from.y + direction.y * t;
            double hitZ = from.z + direction.z * t;
            double hitDistanceSq = from.distanceSquared(hitX, hitY, hitZ);
            if (hitDistanceSq >= entityHitDistanceSq[0]) {
                return;
            }

            entityHitDistanceSq[0] = hitDistanceSq;
            entityHitPos[0] = new Vector3d(hitX, hitY, hitZ);
            entityHitRef[0] = candidate;
        }, ref -> true);

        World world = commandBuffer.getExternalData().getWorld();
        Vector3i block = TargetUtil.getTargetBlock(
                world,
                (id, fluidId) -> id != 0,
                from.x,
                from.y,
                from.z,
                direction.x,
                direction.y,
                direction.z,
                effectiveRange);

        if (block != null) {
            Vector3d blockHitPos = new Vector3d((double) block.x + 0.5, (double) block.y + 0.5, (double) block.z + 0.5);
            double blockDistanceSq = from.distanceSquared(blockHitPos);
            if (entityHitRef[0] == null || blockDistanceSq < entityHitDistanceSq[0]) {
                BlockPosition raw = new BlockPosition(block.x, block.y, block.z);
                return new ShotHit(blockHitPos, null, raw);
            }
        }

        if (entityHitRef[0] != null && entityHitPos[0] != null) {
            return new ShotHit(entityHitPos[0], entityHitRef[0], null);
        }

        Vector3d miss = new Vector3d(from).fma((double) effectiveRange, direction);
        return new ShotHit(miss, null, null);
    }

    private void forkHitInteraction(@Nonnull InteractionContext context,
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

    private void spawnTrail(@Nonnull Vector3d from,
            @Nonnull Vector3d to,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            int trailR, int trailG, int trailB) {
        Vector3d delta = new Vector3d(to).sub(from);
        double distance = from.distance(to);
        if (distance <= 0.001) {
            spawnTrailParticle(new Vector3d(from), 0.0f, 0.0f, commandBuffer, trailR, trailG, trailB);
            return;
        }

        float yaw = (float) Math.atan2(delta.x, delta.z);
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float pitch = (float) -Math.atan2(delta.y, horizontalDistance);

        int steps = Math.max(1, (int) Math.ceil(distance / this.trailStepDistance));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            Vector3d point = new Vector3d(from).fma(t, delta);
            spawnTrailParticle(point, yaw, pitch, commandBuffer, trailR, trailG, trailB);
        }
    }

    private void spawnTrailParticle(@Nonnull Vector3d point,
            float yaw,
            float pitch,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            int trailR, int trailG, int trailB) {
        SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource = commandBuffer
                .getResource(EntityModule.get().getPlayerSpatialResourceType());
        List<Ref<EntityStore>> playerRefs = SpatialResource.getThreadLocalReferenceList();
        playerSpatialResource.getSpatialStructure().collect(point, ParticleUtil.DEFAULT_PARTICLE_DISTANCE, playerRefs);

        Color color = new Color((byte) trailR, (byte) trailG, (byte) trailB);
        ParticleUtil.spawnParticleEffect(
                this.trailParticleId,
                point.x,
                point.y,
                point.z,
                yaw,
                pitch,
                0.0f,
                (float) this.trailScale,
                color,
                null,
                playerRefs,
                commandBuffer);
    }

    private void spawnImpactParticle(@Nonnull Vector3d hitPos,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (!hasText(this.impactParticleId)) {
            return;
        }
        SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource = commandBuffer
                .getResource(EntityModule.get().getPlayerSpatialResourceType());
        List<Ref<EntityStore>> playerRefs = SpatialResource.getThreadLocalReferenceList();
        playerSpatialResource.getSpatialStructure().collect(hitPos, ParticleUtil.DEFAULT_PARTICLE_DISTANCE, playerRefs);

        ParticleUtil.spawnParticleEffect(
                this.impactParticleId,
                hitPos.x,
                hitPos.y,
                hitPos.z,
                0.0f,
                0.0f,
                0.0f,
                (float) this.impactParticleScale,
                new Color((byte) -1, (byte) -1, (byte) -1),
                null,
                playerRefs,
                commandBuffer);
    }

    private boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private void applyKnockback(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> attacker,
            @Nonnull Ref<EntityStore> target,
            double force) {
        if (force <= 0.001 || attacker.equals(target)) {
            return;
        }

        TransformComponent attackerTransform = commandBuffer.getComponent(attacker, TransformComponent.getComponentType());
        TransformComponent targetTransform = commandBuffer.getComponent(target, TransformComponent.getComponentType());
        if (attackerTransform == null || targetTransform == null) {
            return;
        }

        Vector3d direction = new Vector3d(targetTransform.getPosition()).sub(attackerTransform.getPosition());
        direction.y = 0.0;
        if (direction.lengthSquared() <= 0.000001) {
            HeadRotation attackerHeadRotation = commandBuffer.getComponent(attacker, HeadRotation.getComponentType());
            if (attackerHeadRotation == null) {
                return;
            }

            direction = new Vector3d(0.0, 0.0, -1.0);
            direction.rotateY(attackerHeadRotation.getRotation().yaw());
        } else {
            direction.normalize();
        }

        direction.mul(force);
        direction.y = Math.min(0.45, 0.12 * force + 0.05);

        KnockbackComponent knockback = commandBuffer.getComponent(target, KnockbackComponent.getComponentType());
        if (knockback == null) {
            knockback = new KnockbackComponent();
            commandBuffer.putComponent(target, KnockbackComponent.getComponentType(), knockback);
        }

        knockback.setVelocity(direction);
        knockback.setVelocityType(ChangeVelocityType.Add);
        knockback.setDuration(0.0f);
        knockback.setTimer(0.0f);
    }

    private static final class ShotHit {
        @Nonnull
        private final Vector3d position;
        @Nullable
        private final Ref<EntityStore> target;
        @Nullable
        private final BlockPosition block;

        private ShotHit(@Nonnull Vector3d position, @Nullable Ref<EntityStore> target, @Nullable BlockPosition block) {
            this.position = position;
            this.target = target;
            this.block = block;
        }
    }
}
