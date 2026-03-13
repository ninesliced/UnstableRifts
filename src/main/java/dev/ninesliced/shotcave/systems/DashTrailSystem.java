package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;

import javax.annotation.Nonnull;

public final class DashTrailSystem extends EntityTickingSystem<EntityStore> {

    private static final String TRAIL_PARTICLE_ID = "Block_Break_Dust";
    private static final double PARTICLE_Y_OFFSET = 0.8;
    private static final double TRAIL_SCATTER = 0.35;

    @Nonnull
    private final ComponentType<EntityStore, DashComponent> dashComponentType;
    @Nonnull
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    @Nonnull
    private final ComponentType<EntityStore, PlayerRef> playerRefComponentType;

    @Nonnull
    private final Query<EntityStore> query;

    // ── Constructor ─────────────────────────────────────────────────

    public DashTrailSystem(
            @Nonnull ComponentType<EntityStore, DashComponent> dashComponentType,
            @Nonnull ComponentType<EntityStore, TransformComponent> transformComponentType,
            @Nonnull ComponentType<EntityStore, PlayerRef> playerRefComponentType) {
        this.dashComponentType = dashComponentType;
        this.transformComponentType = transformComponentType;
        this.playerRefComponentType = playerRefComponentType;
        this.query = Query.and(dashComponentType, transformComponentType, playerRefComponentType);
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

        DashComponent dash = archetypeChunk.getComponent(index, this.dashComponentType);
        if (dash == null || !dash.isDashing()) {
            return;
        }

        if (!dash.tickTrail()) {
            return;
        }

        Vector3d dashDir = dash.getDashDirection();
        if (dashDir == null) {
            return;
        }

        TransformComponent transform = archetypeChunk.getComponent(index,
                this.transformComponentType);
        if (transform == null) {
            return;
        }

        Vector3d playerPos = transform.getPosition();

        // Collect nearby players to receive the particle packet.
        SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource = commandBuffer
                .getResource(EntityModule.get().getPlayerSpatialResourceType());
        List<Ref<EntityStore>> nearbyPlayers = SpatialResource.getThreadLocalReferenceList();
        playerSpatialResource.getSpatialStructure().collect(
                playerPos, ParticleUtil.DEFAULT_PARTICLE_DISTANCE, nearbyPlayers);

        if (nearbyPlayers.isEmpty()) {
            return;
        }

        // Perpendicular vector for lateral scatter.
        Vector3d perp = new Vector3d(-dashDir.z, 0.0, dashDir.x);

        // Deterministic pseudo-random scatter derived from the remaining
        // tick count so that each tick produces a visually distinct offset.
        int remaining = dash.getTrailTicksRemaining();
        double seed = (remaining * 7.0 + 13.0) % 17.0;
        double lateralScatter = ((seed / 17.0) - 0.5) * 2.0 * TRAIL_SCATTER;
        double backScatter = ((seed * 3.0) % 11.0) / 11.0 * 0.6;

        double px = playerPos.x
                - dashDir.x * (0.3 + backScatter)
                + perp.x * lateralScatter;
        double py = playerPos.y + PARTICLE_Y_OFFSET
                + ((seed % 5.0) / 5.0 - 0.25) * 0.4;
        double pz = playerPos.z
                - dashDir.z * (0.3 + backScatter)
                + perp.z * lateralScatter;

        ParticleUtil.spawnParticleEffect(
                TRAIL_PARTICLE_ID,
                px, py, pz,
                nearbyPlayers,
                commandBuffer);
    }
}
