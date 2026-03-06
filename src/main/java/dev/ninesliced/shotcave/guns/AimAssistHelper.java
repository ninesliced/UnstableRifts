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
 * <p>
 * When aim-assist is active the system finds the best {@link LivingEntity} that is
 * in the player's X-Z look direction (within a configurable cone) and returns a
 * corrected shot direction that fully locks the Y (pitch) axis to aim at the
 * target's center mass. The X-Z direction receives a small nudge so near-misses
 * still connect, without feeling like an aimbot.
 * <p>
 * Candidates that are occluded by solid blocks (walls) are skipped;
 * the helper falls through to the next-best visible target.
 */
public final class AimAssistHelper {

    /**
     * Maximum horizontal angular deviation (in radians) for a target to be
     * considered "in front" of the player.  ~30 degrees gives comfortable
     * coverage when the player only steers on the X-Z plane.
     */
    private static final double XZ_CONE_RAD = Math.toRadians(30.0);

    /**
     * Fraction of the horizontal angular error that is corrected toward the target.
     * 1.0 = full snap, 0.0 = no correction.  0.35 gives a subtle nudge that
     * rewards good aim while still being forgiving enough for fun gameplay.
     */
    private static final double XZ_CORRECTION_FACTOR = 0.35;

    private AimAssistHelper() {
    }

    /**
     * Try to find an aim-assist-corrected direction.
     *
     * @param commandBuffer the current command buffer
     * @param context       the interaction context (used to exclude the shooter)
     * @param muzzle        the position the shot originates from
     * @param rawDirection  the player's raw look direction (normalized)
     * @param range         the maximum range of the weapon
     * @return a corrected, normalized direction, or {@code null} if no suitable target was found
     */
    @Nullable
    public static Vector3d computeAssistedDirection(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                     @Nonnull InteractionContext context,
                                                     @Nonnull Vector3d muzzle,
                                                     @Nonnull Vector3d rawDirection,
                                                     double range) {
        // Horizontal (X-Z) components of the raw look direction
        double lookHorizLen = Math.sqrt(rawDirection.x * rawDirection.x + rawDirection.z * rawDirection.z);
        if (lookHorizLen < 0.000001) {
            // Player is looking straight up or down – no meaningful X-Z direction
            return null;
        }
        double lookXN = rawDirection.x / lookHorizLen;
        double lookZN = rawDirection.z / lookHorizLen;

        // Collect all valid candidates with their scores
        List<AimCandidate> candidates = new ArrayList<>();

        Vector3d searchCenter = muzzle.clone().addScaled(rawDirection, range * 0.5);

        Selector.selectNearbyEntities(commandBuffer, searchCenter, range * 0.6, candidate -> {
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

            TransformComponent transform = commandBuffer.getComponent(candidate, TransformComponent.getComponentType());
            BoundingBox boundingBox = commandBuffer.getComponent(candidate, BoundingBox.getComponentType());
            if (transform == null) {
                return;
            }

            Vector3d ePos = transform.getPosition();
            double dx = ePos.getX() - muzzle.x;
            double dz = ePos.getZ() - muzzle.z;
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            if (horizDist < 0.5 || horizDist > range) {
                return;
            }

            // Horizontal angle between player's look and direction to target
            double toTargetXN = dx / horizDist;
            double toTargetZN = dz / horizDist;
            double dot = lookXN * toTargetXN + lookZN * toTargetZN;
            double angleXZ = Math.acos(Math.min(1.0, Math.max(-1.0, dot)));

            if (angleXZ > XZ_CONE_RAD) {
                return;
            }

            // Use the center-mass of the bounding box as the aim point
            double targetCenterY;
            if (boundingBox != null) {
                targetCenterY = ePos.getY() + boundingBox.getBoundingBox().height() * 0.5;
            } else {
                targetCenterY = ePos.getY() + 1.0;
            }

            // Score: prefer targets that are closer to the crosshair direction
            // (weighted combination of angular deviation and distance)
            double distanceFactor = horizDist / range; // 0..1
            double score = angleXZ + distanceFactor * 0.3;

            candidates.add(new AimCandidate(
                new Vector3d(ePos.getX(), targetCenterY, ePos.getZ()),
                horizDist,
                score
            ));
        }, ref -> true);

        if (candidates.isEmpty()) {
            return null;
        }

        // Sort by score (best first)
        candidates.sort(Comparator.comparingDouble(c -> c.score));

        // Try each candidate in order; skip those behind walls
        World world = commandBuffer.getExternalData().getWorld();

        for (AimCandidate aim : candidates) {
            // Build the direction from muzzle to this candidate's center
            double dx = aim.center.x - muzzle.x;
            double dy = aim.center.y - muzzle.y;
            double dz = aim.center.z - muzzle.z;
            double fullDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (fullDist < 0.001) {
                continue;
            }

            double dirX = dx / fullDist;
            double dirY = dy / fullDist;
            double dirZ = dz / fullDist;

            // Check for solid blocks between the muzzle and the target
            Vector3i wallBlock = TargetUtil.getTargetBlock(
                world,
                (id, fluidId) -> id != 0,
                muzzle.x, muzzle.y, muzzle.z,
                dirX, dirY, dirZ,
                fullDist
            );

            if (wallBlock != null) {
                // A solid block is in the way – compute distance to that block
                double wallDist = muzzle.distanceTo(
                    (double) wallBlock.x + 0.5,
                    (double) wallBlock.y + 0.5,
                    (double) wallBlock.z + 0.5
                );
                if (wallDist < aim.horizDist) {
                    // Wall is closer than the target – skip this candidate
                    continue;
                }
            }

            // This candidate has clear line of sight – build corrected direction
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            if (horizDist < 0.000001) {
                continue;
            }

            // Target horizontal direction
            double targetXN = dx / horizDist;
            double targetZN = dz / horizDist;

            // Blend the X-Z direction: subtly correct toward target
            double blendedX = lookXN + (targetXN - lookXN) * XZ_CORRECTION_FACTOR;
            double blendedZ = lookZN + (targetZN - lookZN) * XZ_CORRECTION_FACTOR;
            double blendedLen = Math.sqrt(blendedX * blendedX + blendedZ * blendedZ);
            if (blendedLen < 0.000001) {
                continue;
            }
            blendedX /= blendedLen;
            blendedZ /= blendedLen;

            // Reconstruct direction with full Y correction (auto-pitch) and blended X-Z
            double correctedPitch = Math.atan2(dy, horizDist);
            double cosP = Math.cos(correctedPitch);

            Vector3d corrected = new Vector3d(blendedX * cosP, Math.sin(correctedPitch), blendedZ * cosP);
            if (corrected.squaredLength() < 0.000001) {
                continue;
            }
            corrected.normalize();
            return corrected;
        }

        // No candidate had clear line of sight
        return null;
    }

    /**
     * Internal record for a scored aim-assist candidate.
     */
    private static final class AimCandidate {
        @Nonnull
        final Vector3d center;
        final double horizDist;
        final double score;

        AimCandidate(@Nonnull Vector3d center, double horizDist, double score) {
            this.center = center;
            this.horizDist = horizDist;
            this.score = score;
        }
    }
}
