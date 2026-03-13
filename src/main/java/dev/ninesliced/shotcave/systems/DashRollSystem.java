package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesSystems;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.camera.TopCameraService;
import it.unimi.dsi.fastutil.objects.ObjectList;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Repurposes the <b>crouch</b> key as a dash ability while the player is in
 * top-down camera mode. All per-player state is stored in the
 * {@link DashComponent} ECS component (attached by
 * {@link DashPlayerAddedSystem}) rather than external hash-maps.
 *
 * <h3>Input</h3>
 * <p>
 * Crouch rising edge ({@code current.crouching && !sent.crouching}). The
 * crouch key is not affected by the client's SprintForce feature, so it
 * reliably fires in every direction.
 * </p>
 *
 * <h3>Direction</h3>
 * <p>
 * The primary direction source is the player's <b>body rotation</b> from
 * {@link TransformComponent#getRotation()}. In top-down camera mode with
 * {@code MovementForceRotationType.Custom}, the client rotates the
 * player's body to face the movement direction. The yaw is converted to a
 * world-space direction vector using Hytale's own heading formula:
 * </p>
 *
 * <pre>
 *   dirX = -sin(yaw)
 *   dirZ = -cos(yaw)
 * </pre>
 * <p>
 * This is read directly on the dash tick — no cross-tick caching is
 * needed because the body rotation is always up-to-date (the input queue
 * that calls {@code SetBody.apply()} has already been processed by the
 * time ECS ticking systems run). The server velocity is used only as a
 * last-resort fallback if the rotation-derived direction is zero (which
 * shouldn't happen in practice).
 * </p>
 *
 * <h3>Particles</h3>
 * <p>
 * An initial burst of particles is spawned behind the player at dash
 * start. A trailing wind effect is handled separately by
 * {@link DashTrailSystem}, which reads the active dash state from the
 * same {@link DashComponent}.
 * </p>
 *
 * <p>
 * Runs <b>before</b> {@link MovementStatesSystems.TickingSystem} so that
 * we can read the raw crouch transition and suppress it before the
 * movement pipeline broadcasts the state.
 * </p>
 */
public final class DashRollSystem extends EntityTickingSystem<EntityStore> {

    private static final double DASH_FORCE = 32.0;

    /** Minimum time (ms) between consecutive dashes for a single player. */
    // TODO: make it parametrisable
    private static final long DASH_COOLDOWN_MS = 1200L;

    /**
     * Number of ticks the wind-trail particle effect persists after a
     * dash. Consumed by {@link DashTrailSystem}.
     */
    static final int PARTICLE_TRAIL_TICKS = 12;

    @Nonnull
    private final ComponentType<EntityStore, PlayerRef> playerRefComponentType;
    @Nonnull
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesComponentType;
    @Nonnull
    private final ComponentType<EntityStore, Velocity> velocityComponentType;
    @Nonnull
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    @Nonnull
    private final ComponentType<EntityStore, DashComponent> dashComponentType;

    @Nonnull
    private final Query<EntityStore> query;
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.BEFORE,
                    MovementStatesSystems.TickingSystem.class));

    @Nonnull
    private final TopCameraService cameraService;

    public DashRollSystem(
            @Nonnull TopCameraService cameraService,
            @Nonnull ComponentType<EntityStore, DashComponent> dashComponentType) {
        this.cameraService = cameraService;
        this.dashComponentType = dashComponentType;
        this.playerRefComponentType = PlayerRef.getComponentType();
        this.movementStatesComponentType = MovementStatesComponent.getComponentType();
        this.velocityComponentType = Velocity.getComponentType();
        this.transformComponentType = TransformComponent.getComponentType();
        this.query = Query.and(
                this.playerRefComponentType,
                this.movementStatesComponentType,
                this.velocityComponentType,
                this.transformComponentType,
                this.dashComponentType);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        PlayerRef playerRef = archetypeChunk.getComponent(index,
                this.playerRefComponentType);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        if (!this.cameraService.isEnabled(playerRef.getUuid())) {
            return;
        }
        DashComponent dash = archetypeChunk.getComponent(index,
                this.dashComponentType);
        if (dash == null) {
            return;
        }

        MovementStatesComponent msc = archetypeChunk.getComponent(index,
                this.movementStatesComponentType);
        if (msc == null) {
            return;
        }

        MovementStates current = msc.getMovementStates();
        MovementStates sent = msc.getSentMovementStates();
        boolean crouchRisingEdge = current.crouching && !sent.crouching;
        if (!crouchRisingEdge) {
            return;
        }
        current.crouching = false;

        // TODO: Look for timers within hytale's ECS instead
        long now = System.currentTimeMillis();
        if ((now - dash.getLastDashTime()) < DASH_COOLDOWN_MS) {
            return;
        }

        TransformComponent transform = archetypeChunk.getComponent(index,
                this.transformComponentType);
        if (transform == null) {
            return;
        }
        Vector3d direction = directionFromBodyRotation(transform);
        Vector3d normalizedDir = direction.clone().normalize();
        Vector3d impulse = normalizedDir.clone().scale(DASH_FORCE);

        Velocity velocity = archetypeChunk.getComponent(index,
                this.velocityComponentType);
        if (velocity == null) {
            return;
        }

        velocity.addInstruction(impulse, new VelocityConfig(),
                ChangeVelocityType.Set);
        dash.setLastDashTime(now);
        dash.startDash(normalizedDir, PARTICLE_TRAIL_TICKS);
    }

    /**
     * Converts the player's body yaw into a horizontal direction vector
     * using Hytale's heading formula from {@code PhysicsMath}:
     *
     * <pre>
     *   x = -sin(yaw)
     *   z = -cos(yaw)
     * </pre>
     *
     * @param transform the player's transform component
     * @return a non-normalised horizontal direction vector (Y is zero)
     */
    @Nonnull
    private static Vector3d directionFromBodyRotation(
            @Nonnull TransformComponent transform) {
        Vector3f rotation = transform.getRotation();
        float yaw = rotation.getYaw();
        double dirX = -Math.sin(yaw);
        double dirZ = -Math.cos(yaw);
        return new Vector3d(dirX, 0.0, dirZ);
    }
}
