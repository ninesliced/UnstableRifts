package dev.ninesliced.unstablerifts.interactions;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.meta.DynamicMetaStore;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.collision.CollisionMath;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.IInteractionSimulationHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ChargingInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.selector.Selector;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import dev.ninesliced.unstablerifts.guns.*;
import dev.ninesliced.unstablerifts.hud.AmmoHudService;
import dev.ninesliced.unstablerifts.systems.DamageEffectRuntime;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.joml.Vector4d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Sustained beam interaction that retraces every tick so the beam follows the
 * weapon while damage only lands on the configured cadence.
 */
public final class ContinuousBeamInteraction extends ChargingInteraction {
    @Nonnull
    public static final BuilderCodec<ContinuousBeamInteraction> CODEC;
    private static final double EPSILON = 0.000001d;
    private static final float HELD_CHARGE_VALUE = -1.0f;
    private static final String CLIENT_CHARGE_CAP_INTERACTION_ID = "UnstableRifts_Update_Hud";
    private static final MetaKey<double[]> NEXT_DAMAGE_TIME_KEY =
            Interaction.CONTEXT_META_REGISTRY.registerMetaObject(data -> null);
    private static final MetaKey<float[]> COMPLETION_COOLDOWN_BONUS_KEY =
            Interaction.CONTEXT_META_REGISTRY.registerMetaObject(data -> null);
    private static final MetaKey<boolean[]> COMPLETION_COOLDOWN_APPLIED_KEY =
            Interaction.CONTEXT_META_REGISTRY.registerMetaObject(data -> null);

    static {
        BuilderCodec.Builder<ContinuousBeamInteraction> builder = BuilderCodec.builder(
                ContinuousBeamInteraction.class,
                ContinuousBeamInteraction::new,
                ChargingInteraction.ABSTRACT_CODEC);

        builder.documentation("Configurable channelled beam weapon that retraces every tick.");
        builder.appendInherited(
                new KeyedCodec<String>("DamageRoot", Codec.STRING),
                (o, v) -> o.damageRoot = v,
                o -> o.damageRoot,
                (o, p) -> o.damageRoot = p.damageRoot).addValidator(Validators.nonNull()).add();

        builder.appendInherited(
                new KeyedCodec<String>("MissRoot", Codec.STRING),
                (o, v) -> o.missRoot = v,
                o -> o.missRoot,
                (o, p) -> o.missRoot = p.missRoot).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("Range", Codec.INTEGER),
                (o, v) -> o.range = v,
                o -> o.range,
                (o, p) -> o.range = p.range).addValidator(Validators.greaterThan(1)).add();

        builder.appendInherited(
                        new KeyedCodec<Double>("DamageInterval", Codec.DOUBLE),
                        (o, v) -> o.damageInterval = v,
                        o -> o.damageInterval,
                        (o, p) -> o.damageInterval = p.damageInterval)
                .addValidator(Validators.greaterThan(0.01d)).add();

        builder.appendInherited(
                        new KeyedCodec<Double>("CompletionCooldownBonus", Codec.DOUBLE),
                        (o, v) -> o.completionCooldownBonus = v,
                        o -> o.completionCooldownBonus,
                        (o, p) -> o.completionCooldownBonus = p.completionCooldownBonus)
                .addValidator(Validators.greaterThanOrEqual(0.0d)).add();

        builder.appendInherited(
                new KeyedCodec<String>("BeamParticleId", Codec.STRING),
                (o, v) -> o.beamParticleId = v,
                o -> o.beamParticleId,
                (o, p) -> o.beamParticleId = p.beamParticleId).add();

        builder.appendInherited(
                        new KeyedCodec<Double>("BeamStepDistance", Codec.DOUBLE),
                        (o, v) -> o.beamStepDistance = v,
                        o -> o.beamStepDistance,
                        (o, p) -> o.beamStepDistance = p.beamStepDistance)
                .addValidator(Validators.greaterThan(0.05d)).add();

        builder.appendInherited(
                        new KeyedCodec<Double>("BeamParticleScale", Codec.DOUBLE),
                        (o, v) -> o.beamParticleScale = v,
                        o -> o.beamParticleScale,
                        (o, p) -> o.beamParticleScale = p.beamParticleScale)
                .addValidator(Validators.greaterThan(0.05d)).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("BeamColorR", Codec.INTEGER),
                (o, v) -> o.beamColorR = v,
                o -> o.beamColorR,
                (o, p) -> o.beamColorR = p.beamColorR).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("BeamColorG", Codec.INTEGER),
                (o, v) -> o.beamColorG = v,
                o -> o.beamColorG,
                (o, p) -> o.beamColorG = p.beamColorG).add();

        builder.appendInherited(
                new KeyedCodec<Integer>("BeamColorB", Codec.INTEGER),
                (o, v) -> o.beamColorB = v,
                o -> o.beamColorB,
                (o, p) -> o.beamColorB = p.beamColorB).add();

        builder.appendInherited(
                new KeyedCodec<String>("ImpactParticleId", Codec.STRING),
                (o, v) -> o.impactParticleId = v,
                o -> o.impactParticleId,
                (o, p) -> o.impactParticleId = p.impactParticleId).add();

        builder.appendInherited(
                        new KeyedCodec<Double>("ImpactParticleScale", Codec.DOUBLE),
                        (o, v) -> o.impactParticleScale = v,
                        o -> o.impactParticleScale,
                        (o, p) -> o.impactParticleScale = p.impactParticleScale)
                .addValidator(Validators.greaterThan(0.0d)).add();

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

        CODEC = builder.build();
    }

    @Nonnull
    private String damageRoot = "Root_UnstableRifts_Pistol_Projectile_Hit";
    @Nullable
    private String missRoot;
    private int range = 35;
    private double damageInterval = 0.2d;
    private double completionCooldownBonus = 0.0d;
    @Nullable
    private String beamParticleId = "UnstableRifts_ElectricBeam";
    private double beamStepDistance = 0.45d;
    private double beamParticleScale = 0.55d;
    private int beamColorR = 255;
    private int beamColorG = 255;
    private int beamColorB = 255;
    @Nullable
    private String impactParticleId;
    private double impactParticleScale = 0.55d;
    private double muzzleHeightOffset = 1.35d;
    private double muzzleForwardOffset = 0.0d;
    private double muzzleRightOffset = 0.0d;
    private boolean useAmmo = false;
    private int maxAmmo = 5;
    private int ammoPerShot = 1;
    private boolean aimAssist = false;

    @Override
    protected void configurePacket(com.hypixel.hytale.protocol.Interaction packet) {
        super.configurePacket(packet);

        com.hypixel.hytale.protocol.ChargingInteraction chargingPacket =
                (com.hypixel.hytale.protocol.ChargingInteraction) packet;
        chargingPacket.displayProgress = false;
        if (this.getRunTime() > 0.0f) {
            chargingPacket.chargedNext = Map.of(
                    this.getRunTime(),
                    Interaction.getInteractionIdOrUnknown(CLIENT_CHARGE_CAP_INTERACTION_ID));
        }
    }

    @Override
    protected void tick0(boolean firstRun, float time, @Nonnull InteractionType type,
                         @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            context.getState().state = InteractionState.Finished;
            return;
        }

        InteractionSyncData clientState = context.getClientState();
        if (clientState == null) {
            context.getState().state = InteractionState.NotFinished;
            return;
        }

        if (firstRun) {
            ItemStack heldItem = context.getHeldItem();
            float cooldownScale = 1.0f;
            if (heldItem != null) {
                double speedBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.ATTACK_SPEED);
                if (speedBonus > 0.001d) {
                    CooldownHandler.Cooldown shootCooldown = cooldownHandler.getCooldown("Shoot");
                    if (shootCooldown != null) {
                        float baseCooldown = shootCooldown.getCooldown();
                        float reducedCooldown = (float) (baseCooldown * (1.0d - speedBonus));
                        if (reducedCooldown < 0.05f) {
                            reducedCooldown = 0.05f;
                        }
                        if (baseCooldown > 0.0f) {
                            cooldownScale = reducedCooldown / baseCooldown;
                        }
                        shootCooldown.setCooldownMax(reducedCooldown);
                    }
                }
            }

            if (this.useAmmo) {
                if (!consumeAmmo(context)) {
                    context.getState().state = InteractionState.Failed;
                    return;
                }
                updateAmmoHud(context);
            }

            context.getMetaStore().putMetaObject(NEXT_DAMAGE_TIME_KEY, new double[]{0.0d});
            context.getMetaStore().putMetaObject(
                    COMPLETION_COOLDOWN_BONUS_KEY,
                    new float[]{(float) (this.completionCooldownBonus * cooldownScale)});
            context.getMetaStore().putMetaObject(COMPLETION_COOLDOWN_APPLIED_KEY, new boolean[]{false});
        }

        double[] nextDamageTime = context.getMetaStore().getIfPresentMetaObject(NEXT_DAMAGE_TIME_KEY);
        if (nextDamageTime == null) {
            context.getState().state = InteractionState.Finished;
            return;
        }

        if (!isBeamActive(clientState, time)) {
            context.getState().state = clientState.state == InteractionState.Failed
                    ? InteractionState.Failed
                    : InteractionState.Finished;
            applyCompletionCooldownIfNeeded(context, cooldownHandler, resolveChargeTime(clientState, time));
            return;
        }

        context.getState().state = InteractionState.NotFinished;

        ItemStack heldItem = context.getHeldItem();
        DamageEffect damageEffect = heldItem != null ? GunItemMetadata.getEffect(heldItem) : DamageEffect.NONE;
        WeaponRarity rarity = heldItem != null ? GunItemMetadata.getRarity(heldItem) : WeaponRarity.BASIC;

        int effectiveRange = this.range;
        int beamR = this.beamColorR;
        int beamG = this.beamColorG;
        int beamB = this.beamColorB;
        double knockbackForce = 0.0d;

        if (heldItem != null) {
            double rangeBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.MAX_RANGE);
            effectiveRange = (int) Math.round(this.range * (1.0d + rangeBonus));

            if (damageEffect != DamageEffect.NONE) {
                beamR = damageEffect.getTrailR();
                beamG = damageEffect.getTrailG();
                beamB = damageEffect.getTrailB();
            }

            WeaponDefinition definition = WeaponDefinitions.getById(heldItem.getItemId());
            if (definition != null) {
                double knockbackBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.KNOCKBACK);
                knockbackForce = definition.baseKnockback() * (1.0d + knockbackBonus);
            }
        }

        Vector3d start = getMuzzlePosition(commandBuffer, context.getEntity());
        if (start == null) {
            super.tick0(firstRun, time, type, context, cooldownHandler);
            return;
        }

        Vector3d direction = getLookDirection(commandBuffer, context.getEntity());
        if (this.aimAssist) {
            Vector3d assisted = AimAssistHelper.computeAssistedDirectionNearest(
                    commandBuffer, context, start, direction, effectiveRange);
            if (assisted != null) {
                direction = assisted;
            }
        }

        ShotHit hit = traceShot(commandBuffer, context, start, direction, effectiveRange);
        spawnBeamSegment(start, hit.position, commandBuffer, beamR, beamG, beamB);

        if (time + EPSILON >= nextDamageTime[0]) {
            advanceDamageTimer(nextDamageTime, time);
            applyBeamTick(context, commandBuffer, hit, damageEffect, rarity, knockbackForce);
        }
    }

    @Override
    protected void simulateTick0(boolean firstRun, float time, @Nonnull InteractionType type,
                                 @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        Ref<EntityStore> ref = context.getEntity();
        IInteractionSimulationHandler simulationHandler = context.getInteractionManager().getInteractionSimulationHandler();
        boolean stillCharging = simulationHandler.isCharging(firstRun, time, type, context, ref, cooldownHandler);

        if (stillCharging && time + EPSILON < this.getRunTime()) {
            context.getState().state = InteractionState.NotFinished;
            context.getState().chargeValue = HELD_CHARGE_VALUE;
            return;
        }

        context.getState().state = InteractionState.Finished;
        if (stillCharging) {
            context.getState().chargeValue = this.getRunTime();
            return;
        }

        context.getState().chargeValue = simulationHandler.getChargeValue(firstRun, time, type, context, ref, cooldownHandler);
    }

    private boolean isBeamActive(@Nonnull InteractionSyncData clientState, float time) {
        return clientState.state == InteractionState.NotFinished
                && clientState.chargeValue == HELD_CHARGE_VALUE
                && time + EPSILON < this.getRunTime();
    }

    private float resolveChargeTime(@Nonnull InteractionSyncData clientState, float serverTime) {
        if (clientState.chargeValue == HELD_CHARGE_VALUE) {
            return Math.min(serverTime, this.getRunTime());
        }
        return clientState.chargeValue;
    }

    private void applyCompletionCooldownIfNeeded(@Nonnull InteractionContext context,
                                                 @Nonnull CooldownHandler cooldownHandler,
                                                 float chargeTime) {
        if (this.completionCooldownBonus <= EPSILON || chargeTime + EPSILON < this.getRunTime()) {
            return;
        }

        boolean[] appliedState = context.getMetaStore().getIfPresentMetaObject(COMPLETION_COOLDOWN_APPLIED_KEY);
        if (appliedState == null || appliedState[0]) {
            return;
        }

        float[] storedBonus = context.getMetaStore().getIfPresentMetaObject(COMPLETION_COOLDOWN_BONUS_KEY);
        float bonus = storedBonus != null ? storedBonus[0] : (float) this.completionCooldownBonus;
        if (bonus > 0.0f) {
            CooldownHandler.Cooldown shootCooldown = cooldownHandler.getCooldown("Shoot");
            if (shootCooldown != null) {
                shootCooldown.increaseTime(bonus);
            }
        }

        appliedState[0] = true;
    }

    private boolean consumeAmmo(@Nonnull InteractionContext context) {
        ItemStack heldItem = context.getHeldItem();
        if (heldItem == null) {
            return false;
        }

        int effectiveMaxAmmo = GunItemMetadata.getEffectiveMaxAmmo(context, heldItem, this.maxAmmo);
        ItemStack updated = GunItemMetadata.ensureAmmo(heldItem, this.maxAmmo, effectiveMaxAmmo);
        int ammo = GunItemMetadata.getInt(updated, GunItemMetadata.AMMO_KEY, effectiveMaxAmmo);
        if (ammo < this.ammoPerShot) {
            return false;
        }

        updated = GunItemMetadata.setInt(updated, GunItemMetadata.AMMO_KEY, ammo - this.ammoPerShot);
        GunItemMetadata.applyHeldItem(context, updated);
        return true;
    }

    private void advanceDamageTimer(@Nonnull double[] nextDamageTime, float currentTime) {
        nextDamageTime[0] += this.damageInterval;
        while (nextDamageTime[0] <= currentTime) {
            nextDamageTime[0] += this.damageInterval;
        }
    }

    private void applyBeamTick(@Nonnull InteractionContext context,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer,
                               @Nonnull ShotHit hit,
                               @Nonnull DamageEffect damageEffect,
                               @Nonnull WeaponRarity rarity,
                               double knockbackForce) {
        RootInteraction damage = RootInteraction.getRootInteractionOrUnknown(this.damageRoot);
        RootInteraction miss = hasText(this.missRoot)
                ? RootInteraction.getRootInteractionOrUnknown(this.missRoot)
                : null;

        if (hit.target != null) {
            if (damage != null) {
                forkHitInteraction(context, damage, hit.target, hit.position);
            }
            if (knockbackForce > 0.001d) {
                applyKnockback(commandBuffer, context.getEntity(), hit.target, knockbackForce);
            }
            if (damageEffect.hasDoT()) {
                DamageEffectRuntime.apply(commandBuffer, hit.target, damageEffect, rarity);
            }
            spawnImpactParticle(hit.position, commandBuffer);
            return;
        }

        if (miss != null && hit.block != null) {
            spawnImpactParticle(hit.position, commandBuffer);
            forkMissInteraction(context, miss, hit.block, hit.position);
        }
    }

    private void updateAmmoHud(@Nonnull InteractionContext context) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            return;
        }

        Player player = commandBuffer.getComponent(context.getEntity(), Player.getComponentType());
        PlayerRef playerRef = commandBuffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        if (player == null || playerRef == null) {
            return;
        }

        boolean crouching = false;
        MovementStatesComponent movementStates = commandBuffer.getComponent(
                context.getEntity(), MovementStatesComponent.getComponentType());
        if (movementStates != null) {
            crouching = movementStates.getMovementStates().crouching;
        }

        AmmoHudService.updateForHeldItem(player, playerRef, context.getHeldItem(), crouching, context.getEntity());
    }

    @Nullable
    private Vector3d getMuzzlePosition(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                       @Nonnull Ref<EntityStore> ref) {
        TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }

        Vector3d position = new Vector3d(transform.getPosition()).add(0.0d, this.muzzleHeightOffset, 0.0d);
        boolean hasForward = Math.abs(this.muzzleForwardOffset) > EPSILON;
        boolean hasRight = Math.abs(this.muzzleRightOffset) > EPSILON;
        if (!hasForward && !hasRight) {
            return position;
        }

        HeadRotation headRotation = commandBuffer.getComponent(ref, HeadRotation.getComponentType());
        if (headRotation == null) {
            return position;
        }

        float yaw = headRotation.getRotation().yaw();
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        double offsetX = sinYaw * this.muzzleForwardOffset + cosYaw * this.muzzleRightOffset;
        double offsetZ = cosYaw * this.muzzleForwardOffset - sinYaw * this.muzzleRightOffset;
        position.add(offsetX, 0.0d, offsetZ);
        return position;
    }

    @Nonnull
    private Vector3d getLookDirection(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                      @Nonnull Ref<EntityStore> ref) {
        HeadRotation headRotation = commandBuffer.getComponent(ref, HeadRotation.getComponentType());
        if (headRotation == null) {
            return new Vector3d(0.0d, 0.0d, 1.0d);
        }

        float yaw = headRotation.getRotation().yaw();
        float pitch = headRotation.getRotation().pitch();
        double horizontal = Math.cos(pitch);
        Vector3d direction = new Vector3d(
                horizontal * -Math.sin(yaw),
                Math.sin(pitch),
                horizontal * -Math.cos(yaw));
        if (direction.lengthSquared() <= EPSILON) {
            return new Vector3d(0.0d, 0.0d, 1.0d);
        }
        return direction;
    }

    @Nonnull
    private ShotHit traceShot(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull InteractionContext context,
                              @Nonnull Vector3d from,
                              @Nonnull Vector3d direction,
                              int effectiveRange) {
        final Vector3d[] entityHitPos = new Vector3d[]{null};
        final Ref<EntityStore>[] entityHitRef = new Ref[]{null};
        final double[] entityHitDistanceSq = new double[]{Double.MAX_VALUE};

        Vector2d minMax = new Vector2d();
        Vector3d searchCenter = new Vector3d(from).fma((double) effectiveRange * 0.5d, direction);
        Selector.selectNearbyEntities(commandBuffer, searchCenter, (double) effectiveRange * 0.6d, candidate -> {
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

            Vector3d entityPosition = transform.getPosition();
            if (!CollisionMath.intersectRayAABB(from, direction,
                    entityPosition.x, entityPosition.y, entityPosition.z,
                    boundingBox.getBoundingBox(), minMax)) {
                return;
            }

            double hitDistance = minMax.x;
            if (hitDistance < 0.0d || hitDistance > (double) effectiveRange) {
                return;
            }

            double hitX = from.x + direction.x * hitDistance;
            double hitY = from.y + direction.y * hitDistance;
            double hitZ = from.z + direction.z * hitDistance;
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
            Vector3d blockHitPos = new Vector3d((double) block.x + 0.5d, (double) block.y + 0.5d, (double) block.z + 0.5d);
            double blockDistanceSq = from.distanceSquared(blockHitPos);
            if (entityHitRef[0] == null || blockDistanceSq < entityHitDistanceSq[0]) {
                return new ShotHit(blockHitPos, null, new BlockPosition(block.x, block.y, block.z));
            }
        }

        if (entityHitRef[0] != null && entityHitPos[0] != null) {
            return new ShotHit(entityHitPos[0], entityHitRef[0], null);
        }

        return new ShotHit(new Vector3d(from).fma(effectiveRange, direction), null, null);
    }

    private void forkHitInteraction(@Nonnull InteractionContext context,
                                    @Nonnull RootInteraction root,
                                    @Nonnull Ref<EntityStore> target,
                                    @Nonnull Vector3d hitPosition) {
        InteractionContext forkContext = context.duplicate();
        DynamicMetaStore<InteractionContext> metaStore = forkContext.getMetaStore();
        metaStore.putMetaObject(Interaction.TARGET_ENTITY, target);
        metaStore.putMetaObject(Interaction.HIT_LOCATION,
                new Vector4d(hitPosition.x, hitPosition.y, hitPosition.z, 1.0d));
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
                new Vector4d(hitPosition.x, hitPosition.y, hitPosition.z, 1.0d));
        metaStore.putMetaObject(Interaction.TARGET_BLOCK_RAW, rawBlock);
        metaStore.putMetaObject(Interaction.TARGET_BLOCK, rawBlock);
        metaStore.removeMetaObject(Interaction.TARGET_ENTITY);

        context.fork(new InteractionChainData(), context.getChain().getType(), forkContext, root, false);
    }

    private void spawnBeamSegment(@Nonnull Vector3d from,
                                  @Nonnull Vector3d to,
                                  @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                  int beamR,
                                  int beamG,
                                  int beamB) {
        if (!hasText(this.beamParticleId)) {
            return;
        }

        Vector3d delta = new Vector3d(to).sub(from);
        double distance = from.distance(to);
        if (distance <= EPSILON) {
            spawnBeamParticle(new Vector3d(from), 0.0f, 0.0f, commandBuffer, beamR, beamG, beamB);
            return;
        }

        float yaw = (float) Math.atan2(delta.x, delta.z);
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float pitch = (float) -Math.atan2(delta.y, horizontalDistance);
        int steps = Math.max(1, (int) Math.ceil(distance / this.beamStepDistance));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            Vector3d point = new Vector3d(from).fma(t, delta);
            spawnBeamParticle(point, yaw, pitch, commandBuffer, beamR, beamG, beamB);
        }
    }

    private void spawnBeamParticle(@Nonnull Vector3d point,
                                   float yaw,
                                   float pitch,
                                   @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                   int beamR,
                                   int beamG,
                                   int beamB) {
        SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource =
                commandBuffer.getResource(EntityModule.get().getPlayerSpatialResourceType());
        List<Ref<EntityStore>> playerRefs = SpatialResource.getThreadLocalReferenceList();
        playerSpatialResource.getSpatialStructure().collect(point, ParticleUtil.DEFAULT_PARTICLE_DISTANCE, playerRefs);

        ParticleUtil.spawnParticleEffect(
                this.beamParticleId,
                point.x,
                point.y,
                point.z,
                yaw,
                pitch,
                0.0f,
                (float) this.beamParticleScale,
                new Color((byte) beamR, (byte) beamG, (byte) beamB),
                null,
                playerRefs,
                commandBuffer);
    }

    private void spawnImpactParticle(@Nonnull Vector3d hitPosition,
                                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (!hasText(this.impactParticleId)) {
            return;
        }

        SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource =
                commandBuffer.getResource(EntityModule.get().getPlayerSpatialResourceType());
        List<Ref<EntityStore>> playerRefs = SpatialResource.getThreadLocalReferenceList();
        playerSpatialResource.getSpatialStructure().collect(hitPosition, ParticleUtil.DEFAULT_PARTICLE_DISTANCE, playerRefs);

        ParticleUtil.spawnParticleEffect(
                this.impactParticleId,
                hitPosition.x,
                hitPosition.y,
                hitPosition.z,
                0.0f,
                0.0f,
                0.0f,
                (float) this.impactParticleScale,
                new Color((byte) -1, (byte) -1, (byte) -1),
                null,
                playerRefs,
                commandBuffer);
    }

    private void applyKnockback(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull Ref<EntityStore> attacker,
                                @Nonnull Ref<EntityStore> target,
                                double force) {
        if (force <= EPSILON || attacker.equals(target)) {
            return;
        }

        TransformComponent attackerTransform = commandBuffer.getComponent(attacker, TransformComponent.getComponentType());
        TransformComponent targetTransform = commandBuffer.getComponent(target, TransformComponent.getComponentType());
        if (attackerTransform == null || targetTransform == null) {
            return;
        }

        Vector3d direction = new Vector3d(targetTransform.getPosition()).sub(attackerTransform.getPosition());
        direction.y = 0.0d;
        if (direction.lengthSquared() <= EPSILON) {
            HeadRotation attackerHeadRotation = commandBuffer.getComponent(attacker, HeadRotation.getComponentType());
            if (attackerHeadRotation == null) {
                return;
            }

            direction = new Vector3d(0.0d, 0.0d, -1.0d);
            direction.rotateY(attackerHeadRotation.getRotation().yaw());
        } else {
            direction.normalize();
        }

        direction.mul(force);
        direction.y = Math.min(0.45d, 0.12d * force + 0.05d);

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

    private boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private record ShotHit(@Nonnull Vector3d position, @Nullable Ref<EntityStore> target,
                           @Nullable BlockPosition block) {
    }
}
