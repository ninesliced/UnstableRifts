package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.inventory.InventoryLockService;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Prevents dungeon players from falling into the void by teleporting them
 * back to a safe Y when their position drops below a threshold.
 * <p>
 * The engine's {@code OutOfWorldDamage} system applies damage at Y &lt; 0
 * and instant-kills at Y &lt; -32. This system fires every tick and catches
 * falling players before they reach that zone, avoiding the default death
 * and respawn flow that would desync them from the dungeon instance.
 */
public final class VoidSafetySystem extends EntityTickingSystem<EntityStore> {

    private static final double VOID_THRESHOLD_Y = -2.0;
    private static final double SAFE_Y = 80.0;

    @Nonnull
    private final ComponentType<EntityStore, PlayerRef> playerRefType;
    @Nonnull
    private final ComponentType<EntityStore, TransformComponent> transformType;
    @Nonnull
    private final Query<EntityStore> query;
    @Nonnull
    private final InventoryLockService lockService;

    public VoidSafetySystem(@Nonnull InventoryLockService lockService) {
        this.lockService = lockService;
        this.playerRefType = PlayerRef.getComponentType();
        this.transformType = TransformComponent.getComponentType();
        this.query = Query.and(this.playerRefType, this.transformType);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        PlayerRef playerRef = archetypeChunk.getComponent(index, this.playerRefType);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        if (!this.lockService.isLocked(playerRef.getUuid())) {
            return;
        }

        TransformComponent transform = archetypeChunk.getComponent(index, this.transformType);
        if (transform == null) {
            return;
        }

        Vector3d pos = transform.getPosition();
        if (pos.y >= VOID_THRESHOLD_Y) {
            return;
        }

        // Teleport back up, keeping X/Z intact
        Vector3d safePos = new Vector3d(pos.x, SAFE_Y, pos.z);
        Teleport tp = Teleport.createForPlayer(safePos, new Rotation3f());
        commandBuffer.addComponent(archetypeChunk.getReferenceTo(index),
                Teleport.getComponentType(), tp);
    }
}
