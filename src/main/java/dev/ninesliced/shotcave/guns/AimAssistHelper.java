package dev.ninesliced.shotcave.guns;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import org.joml.Vector3d;
import org.joml.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
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
import dev.ninesliced.shotcave.crate.DestructibleBlockConfig;

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
                double blockDistSq = dev.ninesliced.shotcave.JomlCompat.distanceSquared(muzzle, blockCenter);
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
            if (dev.ninesliced.shotcave.JomlCompat.lengthSquared(out) > 1.0E-8) {
                out.normalize();
                return out;
            }
        }

        return rawDirection;
    }

    /**
     * Computes an assisted direction toward the best destructible block
     * (crate / barrel) within a 30° horizontal cone, with top-to-bottom
     * priority for stacked blocks.
     */
    @Nullable
    public static Vector3d computeAssistedDirectionForBlocks(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                              @Nonnull Vector3d muzzle,
                                                              @Nonnull Vector3d rawDirection,
                                                              double range) {
        double rawHorizX = rawDirection.x;
        double rawHorizZ = rawDirection.z;
        double rawHorizLenSq = rawHorizX * rawHorizX + rawHorizZ * rawHorizZ;
        if (rawHorizLenSq < 1.0E-8) {
            return null;
        }

        double lookXN = rawHorizX / Math.sqrt(rawHorizLenSq);
        double lookZN = rawHorizZ / Math.sqrt(rawHorizLenSq);
        double minHorizontalDot = Math.cos(XZ_CONE_RAD);

        World world = commandBuffer.getExternalData().getWorld();
        if (world == null) {
            return null;
        }

        int scanRange = (int) Math.ceil(range);
        int muzzleX = (int) Math.floor(muzzle.x);
        int muzzleY = (int) Math.floor(muzzle.y);
        int muzzleZ = (int) Math.floor(muzzle.z);

        List<BlockCandidate> candidates = new ArrayList<>();

        for (int dx = -scanRange; dx <= scanRange; dx++) {
            for (int dz = -scanRange; dz <= scanRange; dz++) {
                for (int dy = -scanRange; dy <= scanRange; dy++) {
                    int bx = muzzleX + dx;
                    int by = muzzleY + dy;
                    int bz = muzzleZ + dz;

                    if (by < 0 || by > 320) continue;

                    double centerX = bx + 0.5;
                    double centerY = by + 0.5;
                    double centerZ = bz + 0.5;

                    double toBlockX = centerX - muzzle.x;
                    double toBlockZ = centerZ - muzzle.z;
                    double horizDist = Math.sqrt(toBlockX * toBlockX + toBlockZ * toBlockZ);
                    if (horizDist < 0.5 || horizDist > range) continue;

                    double toBlockHorizLen = horizDist;
                    double toBlockHorizNormX = toBlockX / toBlockHorizLen;
                    double toBlockHorizNormZ = toBlockZ / toBlockHorizLen;

                    double horizontalDot = lookXN * toBlockHorizNormX + lookZN * toBlockHorizNormZ;
                    if (horizontalDot < minHorizontalDot) continue;

                    double toBlockY = centerY - muzzle.y;
                    double distSq = toBlockX * toBlockX + toBlockY * toBlockY + toBlockZ * toBlockZ;
                    if (distSq > range * range) continue;

                    BlockType blockType = world.getBlockType(bx, by, bz);
                    if (blockType == null) continue;

                    String blockTypeId = blockType.getId();
                    if (blockTypeId == null || !DestructibleBlockConfig.isDestructible(blockTypeId)) continue;

                    // Score: alignment + distance factor. Lower Y gets higher score (worse) so top is preferred.
                    double score = (1.0 - horizontalDot) + (horizDist / range) * 0.15;
                    candidates.add(new BlockCandidate(bx, by, bz, toBlockX, toBlockY, toBlockZ,
                            Math.sqrt(distSq), score));
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Sort by score ascending, then by Y descending (highest first = top-to-bottom priority)
        candidates.sort((a, b) -> {
            int cmp = Double.compare(a.score, b.score);
            if (cmp != 0) return cmp;
            return Integer.compare(b.blockY, a.blockY);
        });

        for (BlockCandidate candidate : candidates) {
            double dist = candidate.distance;
            if (dist < 1.0E-6) continue;

            double dirX = candidate.toBlockX / dist;
            double dirY = candidate.toBlockY / dist;
            double dirZ = candidate.toBlockZ / dist;

            // Line-of-sight check: reject if a non-destructible solid block is between muzzle and target
            Vector3i blockingBlock = TargetUtil.getTargetBlock(
                    world,
                    (id, fluidId) -> id != 0,
                    muzzle.x, muzzle.y, muzzle.z,
                    dirX, dirY, dirZ,
                    (int) Math.ceil(dist));

            if (blockingBlock != null) {
                // If the blocking block IS the target block, that's fine
                if (blockingBlock.x != candidate.blockX || blockingBlock.y != candidate.blockY
                        || blockingBlock.z != candidate.blockZ) {
                    // There's a different block in the way — check if it's closer
                    Vector3d blockCenter = new Vector3d(blockingBlock.x + 0.5, blockingBlock.y + 0.5, blockingBlock.z + 0.5);
                    double blockDistSq = dev.ninesliced.shotcave.JomlCompat.distanceSquared(muzzle, blockCenter);
                    double targetDistSq = dist * dist;
                    if (blockDistSq + 1.0E-6 < targetDistSq) {
                        continue; // Blocked by another solid block
                    }
                }
            }

            double targetHorizLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (targetHorizLen < 1.0E-8) continue;

            double targetHorizNormX = dirX / targetHorizLen;
            double targetHorizNormZ = dirZ / targetHorizLen;

            double blendedHorizX = lookXN * (1.0 - XZ_CORRECTION_FACTOR) + targetHorizNormX * XZ_CORRECTION_FACTOR;
            double blendedHorizZ = lookZN * (1.0 - XZ_CORRECTION_FACTOR) + targetHorizNormZ * XZ_CORRECTION_FACTOR;

            double blendedHorizLen = Math.sqrt(blendedHorizX * blendedHorizX + blendedHorizZ * blendedHorizZ);
            if (blendedHorizLen < 1.0E-8) continue;
            blendedHorizX /= blendedHorizLen;
            blendedHorizZ /= blendedHorizLen;

            Vector3d out = new Vector3d(blendedHorizX * targetHorizLen, dirY, blendedHorizZ * targetHorizLen);
            if (dev.ninesliced.shotcave.JomlCompat.lengthSquared(out) > 1.0E-8) {
                out.normalize();
                return out;
            }
        }

        return null;
    }

    /**
     * Computes an assisted direction toward the nearest valid target — either
     * a living entity or a destructible block — within a 30° horizontal cone.
     * Unlike calling entity and block methods separately, this evaluates both
     * pools together and picks the closest by distance so a nearby crate is
     * never overshadowed by a distant mob.
     */
    @Nullable
    public static Vector3d computeAssistedDirectionNearest(@Nonnull CommandBuffer<EntityStore> commandBuffer,
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

        World world = commandBuffer.getExternalData().getWorld();

        // Unified candidate list: toTargetX/Y/Z + distance + score
        List<double[]> candidates = new ArrayList<>();

        // ── Entity candidates ──
        Selector.selectNearbyEntities(commandBuffer, muzzle, range, candidateRef -> {
            if (!candidateRef.isValid()) return;
            if (candidateRef.equals(context.getEntity()) || candidateRef.equals(context.getOwningEntity())) return;

            Entity entity = EntityUtils.getEntity(candidateRef, commandBuffer);
            if (!(entity instanceof LivingEntity)) return;

            TransformComponent transform = commandBuffer.getComponent(candidateRef, TransformComponent.getComponentType());
            BoundingBox box = commandBuffer.getComponent(candidateRef, BoundingBox.getComponentType());
            if (transform == null || box == null) return;

            Vector3d entityPos = transform.getPosition();
            double toX = entityPos.x - muzzle.x;
            double toZ = entityPos.z - muzzle.z;
            double horizDist = Math.sqrt(toX * toX + toZ * toZ);
            if (horizDist < 0.5 || horizDist > range) return;

            double hLen = horizDist;
            double hNormX = toX / hLen;
            double hNormZ = toZ / hLen;
            double hDot = lookXN * hNormX + lookZN * hNormZ;
            if (hDot < minHorizontalDot) return;

            double boxCenterY = (box.getBoundingBox().min.y + box.getBoundingBox().max.y) * 0.5;
            double toY = entityPos.y + boxCenterY - muzzle.y;
            double distSq = toX * toX + toY * toY + toZ * toZ;
            if (distSq > rangeSq) return;

            double dist = Math.sqrt(distSq);
            candidates.add(new double[]{ toX, toY, toZ, dist });
        }, ref -> true);

        // ── Block candidates ──
        if (world != null) {
            int scanRange = (int) Math.ceil(range);
            int mx = (int) Math.floor(muzzle.x);
            int my = (int) Math.floor(muzzle.y);
            int mz = (int) Math.floor(muzzle.z);

            for (int dx = -scanRange; dx <= scanRange; dx++) {
                for (int dz = -scanRange; dz <= scanRange; dz++) {
                    for (int dy = -scanRange; dy <= scanRange; dy++) {
                        int bx = mx + dx;
                        int by = my + dy;
                        int bz = mz + dz;
                        if (by < 0 || by > 320) continue;

                        double cx = bx + 0.5;
                        double cy = by + 0.5;
                        double cz = bz + 0.5;
                        double toX = cx - muzzle.x;
                        double toZ = cz - muzzle.z;
                        double horizDist = Math.sqrt(toX * toX + toZ * toZ);
                        if (horizDist < 0.5 || horizDist > range) continue;

                        double hNormX = toX / horizDist;
                        double hNormZ = toZ / horizDist;
                        double hDot = lookXN * hNormX + lookZN * hNormZ;
                        if (hDot < minHorizontalDot) continue;

                        double toY = cy - muzzle.y;
                        double distSq = toX * toX + toY * toY + toZ * toZ;
                        if (distSq > rangeSq) continue;

                        BlockType blockType = world.getBlockType(bx, by, bz);
                        if (blockType == null) continue;
                        String blockTypeId = blockType.getId();
                        if (blockTypeId == null || !DestructibleBlockConfig.isDestructible(blockTypeId)) continue;

                        double dist = Math.sqrt(distSq);
                        candidates.add(new double[]{ toX, toY, toZ, dist });
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Sort by distance ascending — nearest target wins
        candidates.sort(Comparator.comparingDouble(a -> a[3]));

        for (double[] c : candidates) {
            double dist = c[3];
            if (dist < 1.0E-6) continue;

            double dirX = c[0] / dist;
            double dirY = c[1] / dist;
            double dirZ = c[2] / dist;

            // Line-of-sight: reject if a non-target solid block is closer
            if (world != null) {
                Vector3i blockingBlock = TargetUtil.getTargetBlock(
                        world, (id, fluidId) -> id != 0,
                        muzzle.x, muzzle.y, muzzle.z,
                        dirX, dirY, dirZ,
                        (int) Math.ceil(dist));
                if (blockingBlock != null) {
                    Vector3d bc = new Vector3d(blockingBlock.x + 0.5, blockingBlock.y + 0.5, blockingBlock.z + 0.5);
                    double blockDistSq = dev.ninesliced.shotcave.JomlCompat.distanceSquared(muzzle, bc);
                    double targetDistSq = dist * dist;
                    // If blocking block is closer AND is not in the same position as a destructible block target
                    if (blockDistSq + 1.0E-6 < targetDistSq) {
                        BlockType bt = world.getBlockType(blockingBlock.x, blockingBlock.y, blockingBlock.z);
                        if (bt == null || !DestructibleBlockConfig.isDestructible(bt.getId())) {
                            continue;
                        }
                    }
                }
            }

            double targetHorizLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
            if (targetHorizLen < 1.0E-8) continue;

            double thNormX = dirX / targetHorizLen;
            double thNormZ = dirZ / targetHorizLen;
            double bx = lookXN * (1.0 - XZ_CORRECTION_FACTOR) + thNormX * XZ_CORRECTION_FACTOR;
            double bz = lookZN * (1.0 - XZ_CORRECTION_FACTOR) + thNormZ * XZ_CORRECTION_FACTOR;
            double bLen = Math.sqrt(bx * bx + bz * bz);
            if (bLen < 1.0E-8) continue;
            bx /= bLen;
            bz /= bLen;

            Vector3d out = new Vector3d(bx * targetHorizLen, dirY, bz * targetHorizLen);
            if (dev.ninesliced.shotcave.JomlCompat.lengthSquared(out) > 1.0E-8) {
                out.normalize();
                return out;
            }
        }

        return null;
    }

    private static final class BlockCandidate {
        final int blockX, blockY, blockZ;
        final double toBlockX, toBlockY, toBlockZ;
        final double distance;
        final double score;

        BlockCandidate(int blockX, int blockY, int blockZ,
                       double toBlockX, double toBlockY, double toBlockZ,
                       double distance, double score) {
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.toBlockX = toBlockX;
            this.toBlockY = toBlockY;
            this.toBlockZ = toBlockZ;
            this.distance = distance;
            this.score = score;
        }
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
