package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.dungeon.GameManager;
import dev.ninesliced.unstablerifts.dungeon.Level;
import dev.ninesliced.unstablerifts.dungeon.RoomData;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Tracks NPCs created by prefab spawn markers and assigns them to the room that owns the marker.
 */
public final class PrefabSpawnTrackingSystem extends EntityTickingSystem<EntityStore> {

    private static final double MARKER_MATCH_DISTANCE_SQ = 1.0;

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.of(SpawnMarkerReference.getComponentType(), NPCEntity.getComponentType());
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UnstableRifts unstablerifts = UnstableRifts.getInstance();
        if (unstablerifts == null) {
            return;
        }

        World world = store.getExternalData().getWorld();
        Game game = unstablerifts.getGameManager().findGameForWorld(world);
        if (game == null) {
            return;
        }

        Level level = game.getCurrentLevel();
        if (level == null || level.getRooms().isEmpty()) {
            return;
        }

        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        SpawnMarkerReference spawnMarkerReference = archetypeChunk.getComponent(index, SpawnMarkerReference.getComponentType());
        if (spawnMarkerReference == null) {
            return;
        }

        Ref<EntityStore> markerRef = spawnMarkerReference.getReference().getEntity(store);
        if (markerRef == null || !markerRef.isValid()) {
            return;
        }

        TransformComponent markerTransform = store.getComponent(markerRef, TransformComponent.getComponentType());
        if (markerTransform == null) {
            return;
        }

        RoomData room = findRoomForMarker(level, markerTransform.getPosition());
        if (room == null) {
            return;
        }

        if (npcRef.isValid()) {
            room.addSpawnedMob(npcRef);
            GameManager gameManager = unstablerifts.getGameManager();
            UUIDComponent uuidComp = store.getComponent(npcRef, UUIDComponent.getComponentType());
            if (uuidComp != null) {
                gameManager.getMobSpawningService().registerDungeonMob(uuidComp.getUuid(), room);
            }
        }
    }

    private RoomData findRoomForMarker(@Nonnull Level level, @Nonnull Vector3d markerPosition) {
        for (RoomData room : level.getRooms()) {
            if (room.hasPrefabMobMarkerNear(markerPosition, MARKER_MATCH_DISTANCE_SQ)) {
                return room;
            }
        }
        return null;
    }
}
