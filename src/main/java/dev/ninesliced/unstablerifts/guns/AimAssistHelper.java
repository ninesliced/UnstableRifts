package dev.ninesliced.unstablerifts.guns;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.LivingEntity;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.selector.Selector;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import dev.ninesliced.unstablerifts.crate.DestructibleBlockConfig;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared aim-assist logic for hitscan weapons.
 */
public final class AimAssistHelper {
    private static final double XZ_CONE_RAD = Math.toRadians(30.0);
    private static final double XZ_CORRECTION_FACTOR = 0.35;
    private static final double[] BLOCK_ASSIST_YAW_OFFSETS_DEGREES = {
            0.0, -5.0, 5.0, -10.0, 10.0, -15.0, 15.0, -20.0, 20.0, -25.0, 25.0, -30.0, 30.0
    };
    private static final double[] BLOCK_ASSIST_PITCH_OFFSETS_DEGREES = {0.0, -6.0, 6.0};

    private AimAssistHelper() {
    }

    /**
     * Safe block type lookup that avoids triggering chunk state transitions
     * during system ticks. Uses getChunkIfLoaded (already-ticking chunks only).
     */
    @Nullable
    private static BlockType getBlockTypeSafe(@Nonnull World world, int x, int y, int z) {
        if (y < 0 || y >= 320) return null;
        long chunkIdx = ChunkUtil.indexChunkFromBlock(x, z);
        WorldChunk chunk = world.getChunkIfLoaded(chunkIdx);
        if (chunk == null) return null;
        int blockId = chunk.getBlock(x, y, z);
        return blockId == 0 ? null : BlockType.getAssetMap().getAsset(blockId);
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
                double blockDistSq = muzzle.distanceSquared(blockCenter);
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
            if (out.lengthSquared() > 1.0E-8) {
                out.normalize();
                return out;
            }
        }

        return rawDirection;
    }

    /**
     * Computes an assisted direction toward a visible destructible block
     * (crate / barrel) using a small fan of sampled rays inside the aim cone.
     * This keeps block targeting responsive on long-range weapons without
     * scanning every block in range.
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

        List<BlockCandidate> candidates = collectSampledBlockCandidates(world, muzzle, rawDirection, range);

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort((a, b) -> {
            int cmp = Double.compare(a.score(), b.score());
            if (cmp != 0) return cmp;
            cmp = Double.compare(a.distance(), b.distance());
            if (cmp != 0) return cmp;
            return Integer.compare(b.blockY(), a.blockY());
        });

        for (BlockCandidate candidate : candidates) {
            double dist = candidate.distance();
            if (dist < 1.0E-6) continue;

            double dirX = candidate.toBlockX() / dist;
            double dirY = candidate.toBlockY() / dist;
            double dirZ = candidate.toBlockZ() / dist;

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
            if (out.lengthSquared() > 1.0E-8) {
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
            candidates.add(new double[]{toX, toY, toZ, dist, 0.0});
        }, ref -> true);

        // ── Block candidates ──
        if (world != null) {
            for (BlockCandidate blockCandidate : collectSampledBlockCandidates(world, muzzle, rawDirection, range)) {
                candidates.add(new double[]{
                        blockCandidate.toBlockX(),
                        blockCandidate.toBlockY(),
                        blockCandidate.toBlockZ(),
                        blockCandidate.distance(),
                        blockCandidate.score()
                });
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Sort by distance ascending — nearest target wins
        candidates.sort(Comparator
                .comparingDouble((double[] a) -> a[3])
                .thenComparingDouble(a -> a[4]));

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
                    double blockDistSq = muzzle.distanceSquared(bc);
                    double targetDistSq = dist * dist;
                    // If blocking block is closer AND is not in the same position as a destructible block target
                    if (blockDistSq + 1.0E-6 < targetDistSq) {
                        BlockType bt = getBlockTypeSafe(world, blockingBlock.x, blockingBlock.y, blockingBlock.z);
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
            if (out.lengthSquared() > 1.0E-8) {
                out.normalize();
                return out;
            }
        }

        return null;
    }

    @Nonnull
    private static List<BlockCandidate> collectSampledBlockCandidates(@Nonnull World world,
                                                                      @Nonnull Vector3d muzzle,
                                                                      @Nonnull Vector3d rawDirection,
                                                                      double range) {
        List<BlockCandidate> candidates = new ArrayList<>();
        Vector3d normalizedLook = new Vector3d(rawDirection);
        if (normalizedLook.lengthSquared() < 1.0E-8) {
            return candidates;
        }

        normalizedLook.normalize();
        int blockRange = (int) Math.ceil(range);
        Set<Long> seenBlocks = new HashSet<>();

        for (double yawOffset : BLOCK_ASSIST_YAW_OFFSETS_DEGREES) {
            for (double pitchOffset : BLOCK_ASSIST_PITCH_OFFSETS_DEGREES) {
                Vector3d sampleDirection = rotateSampleDirection(normalizedLook, yawOffset, pitchOffset);
                Vector3i hit = TargetUtil.getTargetBlock(
                        world,
                        (id, fluidId) -> id != 0,
                        muzzle.x,
                        muzzle.y,
                        muzzle.z,
                        sampleDirection.x,
                        sampleDirection.y,
                        sampleDirection.z,
                        blockRange);

                if (hit == null) {
                    continue;
                }

                BlockType blockType = getBlockTypeSafe(world, hit.x, hit.y, hit.z);
                if (blockType == null) {
                    continue;
                }

                String blockTypeId = blockType.getId();
                if (blockTypeId == null || !DestructibleBlockConfig.isDestructible(blockTypeId)) {
                    continue;
                }

                long packed = packBlock(hit.x, hit.y, hit.z);
                if (!seenBlocks.add(packed)) {
                    continue;
                }

                double centerX = hit.x + 0.5;
                double centerY = hit.y + 0.5;
                double centerZ = hit.z + 0.5;
                double toBlockX = centerX - muzzle.x;
                double toBlockY = centerY - muzzle.y;
                double toBlockZ = centerZ - muzzle.z;
                double distSq = toBlockX * toBlockX + toBlockY * toBlockY + toBlockZ * toBlockZ;
                if (distSq > range * range) {
                    continue;
                }

                double dist = Math.sqrt(distSq);
                double score = Math.abs(yawOffset) + Math.abs(pitchOffset) * 0.35 + (dist / Math.max(1.0, range)) * 0.15;
                candidates.add(new BlockCandidate(hit.x, hit.y, hit.z, toBlockX, toBlockY, toBlockZ, dist, score));
            }
        }

        return candidates;
    }

    @Nonnull
    private static Vector3d rotateSampleDirection(@Nonnull Vector3d baseDirection,
                                                  double yawOffsetDegrees,
                                                  double pitchOffsetDegrees) {
        Vector3d rotated = new Vector3d(baseDirection);

        if (Math.abs(yawOffsetDegrees) > 1.0E-6) {
            rotated.rotateAxis(Math.toRadians(yawOffsetDegrees), 0.0, 1.0, 0.0);
        }

        if (Math.abs(pitchOffsetDegrees) > 1.0E-6) {
            Vector3d rightAxis = new Vector3d(rotated).cross(0.0, 1.0, 0.0);
            if (rightAxis.lengthSquared() <= 1.0E-8) {
                rightAxis.set(1.0, 0.0, 0.0);
            } else {
                rightAxis.normalize();
            }
            rotated.rotateAxis(Math.toRadians(pitchOffsetDegrees), rightAxis.x, rightAxis.y, rightAxis.z);
        }

        if (rotated.lengthSquared() > 1.0E-8) {
            rotated.normalize();
        }
        return rotated;
    }

    private static long packBlock(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38)
                | (((long) z & 0x3FFFFFFL) << 12)
                | ((long) y & 0xFFFL);
    }

    private record BlockCandidate(int blockX, int blockY, int blockZ, double toBlockX, double toBlockY, double toBlockZ,
                                  double distance, double score) {
    }

    private record AimCandidate(@Nonnull Ref<EntityStore> candidate, double toTargetX, double toTargetY,
                                double toTargetZ, double distance, double score) {
    }
}
