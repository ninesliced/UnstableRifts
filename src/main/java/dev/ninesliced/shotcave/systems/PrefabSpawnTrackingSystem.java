package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.dungeon.Game;
import dev.ninesliced.shotcave.dungeon.GameManager;
import dev.ninesliced.shotcave.dungeon.Level;
import dev.ninesliced.shotcave.dungeon.RoomData;

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
        Shotcave shotcave = Shotcave.getInstance();
        if (shotcave == null) {
            return;
        }

        World world = store.getExternalData().getWorld();
        Game game = shotcave.getGameManager().findGameForWorld(world);
        if (game == null) {
            return;
        }

        Level level = game.getCurrentLevel();
        if (level == null || level.getRooms().isEmpty()) {
            return;
        }

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

        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        if (npcRef.isValid()) {
            room.addSpawnedMob(npcRef);
            GameManager gameManager = shotcave.getGameManager();
            UUIDComponent uuidComp = store.getComponent(npcRef, UUIDComponent.getComponentType());
            if (uuidComp != null) {
                gameManager.registerDungeonMob(uuidComp.getUuid(), room);
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

