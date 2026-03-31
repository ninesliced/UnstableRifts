package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesSystems;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.physics.systems.GenericVelocityInstructionSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.system.PlayerVelocityInstructionSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.camera.TopCameraService;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Replaces the jump key with an invulnerable roll while in top-down
 * camera mode.
 *
 * <ul>
 *   <li>Input: jump rising edge ({@code current.jumping && !sent.jumping})</li>
 *   <li>The actual jump is suppressed</li>
 *   <li>A velocity impulse is applied in the player's facing direction</li>
 *   <li>The Roll animation is explicitly played on the Movement slot</li>
 *   <li>The {@link Invulnerable} component is added for the roll duration</li>
 * </ul>
 */
public final class RollSystem extends EntityTickingSystem<EntityStore> {

    /**
     * Number of server ticks the roll (and i-frames) lasts. At 20 TPS this is ~0.6s.
     */
    static final int ROLL_DURATION_TICKS = 12;
    private static final double ROLL_FORCE = 30.0;
    private static final long ROLL_COOLDOWN_MS = 600L;
    private static final String ROLL_ANIMATION = "Roll";
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.BEFORE,
                    MovementStatesSystems.TickingSystem.class),
            new SystemDependency<>(Order.BEFORE,
                    PlayerVelocityInstructionSystem.class),
            new SystemDependency<>(Order.BEFORE,
                    GenericVelocityInstructionSystem.class));
    @Nonnull
    private final ComponentType<EntityStore, PlayerRef> playerRefComponentType;
    @Nonnull
    private final ComponentType<EntityStore, MovementStatesComponent> movementStatesComponentType;
    @Nonnull
    private final ComponentType<EntityStore, Velocity> velocityComponentType;
    @Nonnull
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    @Nonnull
    private final ComponentType<EntityStore, RollComponent> rollComponentType;
    @Nonnull
    private final Query<EntityStore> query;
    @Nonnull
    private final TopCameraService cameraService;

    public RollSystem(
            @Nonnull TopCameraService cameraService,
            @Nonnull ComponentType<EntityStore, RollComponent> rollComponentType) {
        this.cameraService = cameraService;
        this.rollComponentType = rollComponentType;
        this.playerRefComponentType = PlayerRef.getComponentType();
        this.movementStatesComponentType = MovementStatesComponent.getComponentType();
        this.velocityComponentType = Velocity.getComponentType();
        this.transformComponentType = TransformComponent.getComponentType();
        this.query = Query.and(
                this.playerRefComponentType,
                this.movementStatesComponentType,
                this.velocityComponentType,
                this.transformComponentType,
                this.rollComponentType);
    }

    @Nonnull
    private static Vector3d directionFromBodyRotation(@Nonnull TransformComponent transform) {
        Rotation3f rotation = transform.getRotation();
        float yaw = rotation.yaw();
        double dirX = -Math.sin(yaw);
        double dirZ = -Math.cos(yaw);
        return new Vector3d(dirX, 0.0, dirZ);
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

        PlayerRef playerRef = archetypeChunk.getComponent(index, this.playerRefComponentType);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        if (!this.cameraService.isEnabled(playerRef.getUuid())) {
            return;
        }

        RollComponent roll = archetypeChunk.getComponent(index, this.rollComponentType);
        if (roll == null) {
            return;
        }

        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);

        MovementStatesComponent msc = archetypeChunk.getComponent(index, this.movementStatesComponentType);
        if (msc == null) {
            return;
        }
        MovementStates current = msc.getMovementStates();
        MovementStates sent = msc.getSentMovementStates();

        // Tick active roll — remove invulnerability and stop animation when it ends
        if (roll.isRolling()) {
            if (!roll.tickRoll()) {
                // Roll just ended — stop animation and remove i-frames
                AnimationUtils.stopAnimation(ref, AnimationSlot.Status, true, commandBuffer);
                commandBuffer.tryRemoveComponent(ref, Invulnerable.getComponentType());
            }
        }
        boolean jumpRisingEdge = current.jumping && !sent.jumping;

        // Suppress the actual jump — we always eat it in dungeon mode
        current.jumping = false;

        if (!jumpRisingEdge) {
            return;
        }

        // Cooldown check
        long now = System.currentTimeMillis();
        if ((now - roll.getLastRollTime()) < ROLL_COOLDOWN_MS) {
            return;
        }

        // Don't start a new roll if one is active
        if (roll.isRolling()) {
            return;
        }

        // Compute direction from body rotation
        TransformComponent transform = archetypeChunk.getComponent(index, this.transformComponentType);
        if (transform == null) {
            return;
        }
        Vector3d direction = directionFromBodyRotation(transform);
        Vector3d normalizedDir = new Vector3d(direction).normalize();
        Vector3d impulse = new Vector3d(normalizedDir).mul(ROLL_FORCE);

        // Apply velocity
        Velocity velocity = archetypeChunk.getComponent(index, this.velocityComponentType);
        if (velocity == null) {
            return;
        }
        velocity.addInstruction(impulse, null, ChangeVelocityType.Set);

        // Play roll animation on Status slot — avoids conflicting with the Movement
        // state machine which would hold the last frame and fight the built-in roll.
        AnimationUtils.playAnimation(ref, AnimationSlot.Status, ROLL_ANIMATION, true, commandBuffer);

        // Start roll tracking
        roll.setLastRollTime(now);
        roll.startRoll(normalizedDir, ROLL_DURATION_TICKS);

        // Grant invulnerability for the roll duration
        commandBuffer.ensureComponent(ref, Invulnerable.getComponentType());
    }
}
