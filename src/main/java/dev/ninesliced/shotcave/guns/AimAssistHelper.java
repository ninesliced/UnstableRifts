package dev.ninesliced.shotcave.guns;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.selector.Selector;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared aim-assist logic for hitscan weapons.
 */
public final class AimAssistHelper {
    private static final double XZ_CONE_RAD = Math.toRadians(30.0);
    private static final double XZ_CORRECTION_FACTOR = 0.35;

    private AimAssistHelper() {
    }

    /**
     * Computes an assisted direction if a suitable target is visible.
     */
    @Nullable
    public static Vector3d computeAssistedDirection(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                    @Nonnull InteractionContext context,
                                                    @Nonnull Vector3d muzzle,
                                                    @Nonnull Vector3d rawDirection,
                                                    double range) {
        double rawHorizX = rawDirection.x;
        double rawHorizZ = rawDirection.z;
        double rawHorizLenSq = rawHorizX * rawHorizX + rawHorizZ * rawHorizZ;
        if (rawHorizLenSq < 1.0E-8) {
            return rawDirection;
        }

        double lookXN = rawHorizX / Math.sqrt(rawHorizLenSq);
        double lookZN = rawHorizZ / Math.sqrt(rawHorizLenSq);
        double minHorizontalDot = Math.cos(XZ_CONE_RAD);
        double rangeSq = range * range;

        List<AimCandidate> candidates = new ArrayList<>();

        Selector.selectNearbyEntities(commandBuffer, muzzle, range, candidateRef -> {
            if (!candidateRef.isValid()) {
                return;
            }
            if (candidateRef.equals(context.getEntity()) || candidateRef.equals(context.getOwningEntity())) {
                return;
            }

            Entity entity = EntityUtils.getEntity(candidateRef, commandBuffer);
            if (!(entity instanceof LivingEntity)) {
                return;
            }

            TransformComponent transform = commandBuffer.getComponent(candidateRef, TransformComponent.getComponentType());
            BoundingBox box = commandBuffer.getComponent(candidateRef, BoundingBox.getComponentType());
            if (transform == null || box == null) {
                return;
            }

            Vector3d entityPos = transform.getPosition();
            double toTargetX = entityPos.x - muzzle.x;
            double toTargetZ = entityPos.z - muzzle.z;
            double horizDist = Math.sqrt(toTargetX * toTargetX + toTargetZ * toTargetZ);
            if (horizDist < 0.5 || horizDist > range) {
                return;
            }

            double toTargetHorizLenSq = toTargetX * toTargetX + toTargetZ * toTargetZ;
            if (toTargetHorizLenSq < 1.0E-8) {
                return;
            }

            double toTargetHorizLen = Math.sqrt(toTargetHorizLenSq);
            double toTargetHorizNormX = toTargetX / toTargetHorizLen;
            double toTargetHorizNormZ = toTargetZ / toTargetHorizLen;

            double horizontalDot = lookXN * toTargetHorizNormX + lookZN * toTargetHorizNormZ;
            if (horizontalDot < minHorizontalDot) {
                return;
            }

            double boxCenterY = (box.getBoundingBox().min.y + box.getBoundingBox().max.y) * 0.5;
            double targetY = entityPos.y + boxCenterY;
            double toTargetY = targetY - muzzle.y;
            double distSq = toTargetX * toTargetX + toTargetY * toTargetY + toTargetZ * toTargetZ;
            if (distSq > rangeSq) {
                return;
            }

            double score = (1.0 - horizontalDot) + (horizDist / range) * 0.15;
            candidates.add(new AimCandidate(candidateRef, toTargetX, toTargetY, toTargetZ, Math.sqrt(distSq), score));
        }, ref -> true);

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparingDouble(a -> a.score));
        World world = commandBuffer.getExternalData().getWorld();

        for (AimCandidate candidate : candidates) {
            double dist = candidate.distance;
            if (dist < 1.0E-6) {
                continue;
            }

            double dirX = candidate.toTargetX / dist;
            double dirY = candidate.toTargetY / dist;
            double dirZ = candidate.toTargetZ / dist;

            Vector3i blockingBlock = TargetUtil.getTargetBlock(
                    world,
                    (id, fluidId) -> id != 0,
                    muzzle.x,
                    muzzle.y,
                    muzzle.z,
                    dirX,
                    dirY,
                    dirZ,
                    (int) Math.ceil(dist)
            );

            if (blockingBlock != null) {
                Vector3d blockCenter = new Vector3d(blockingBlock.x + 0.5, blockingBlock.y + 0.5, blockingBlock.z + 0.5);
                double blockDistSq = muzzle.distanceSquaredTo(blockCenter);
                double targetDistSq = dist * dist;
                if (blockDistSq + 1.0E-6 < targetDistSq) {
                    continue;
                }
            }

            double targetHorizLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (targetHorizLen < 1.0E-8) {
                continue;
            }

            double targetHorizNormX = dirX / targetHorizLen;
            double targetHorizNormZ = dirZ / targetHorizLen;

            double blendedHorizX = lookXN * (1.0 - XZ_CORRECTION_FACTOR) + targetHorizNormX * XZ_CORRECTION_FACTOR;
            double blendedHorizZ = lookZN * (1.0 - XZ_CORRECTION_FACTOR) + targetHorizNormZ * XZ_CORRECTION_FACTOR;

            double blendedHorizLen = Math.sqrt(blendedHorizX * blendedHorizX + blendedHorizZ * blendedHorizZ);
            if (blendedHorizLen < 1.0E-8) {
                continue;
            }
            blendedHorizX /= blendedHorizLen;
            blendedHorizZ /= blendedHorizLen;

            Vector3d out = new Vector3d(blendedHorizX * targetHorizLen, dirY, blendedHorizZ * targetHorizLen);
            if (out.squaredLength() > 1.0E-8) {
                out.normalize();
                return out;
            }
        }

        return rawDirection;
    }

    private static final class AimCandidate {
        @Nonnull
        final Ref<EntityStore> candidate;
        final double toTargetX;
        final double toTargetY;
        final double toTargetZ;
        final double distance;
        final double score;

        AimCandidate(@Nonnull Ref<EntityStore> candidate,
                     double toTargetX,
                     double toTargetY,
                     double toTargetZ,
                     double distance,
                     double score) {
            this.candidate = candidate;
            this.toTargetX = toTargetX;
            this.toTargetY = toTargetY;
            this.toTargetZ = toTargetZ;
            this.distance = distance;
            this.score = score;
        }
    }
}
