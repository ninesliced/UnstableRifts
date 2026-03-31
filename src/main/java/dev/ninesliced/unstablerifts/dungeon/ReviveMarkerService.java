package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages revive marker entities: spawning a downed-player marker at the
 * death position, querying its location, and despawning it on revive.
 */
public final class ReviveMarkerService {

    private static final String REVIVE_MARKER_MODEL_ID = "NPC_Spawn_Marker";
    private static final String REVIVE_MARKER_DOWN_ANIMATION = "Death";

    /**
     * Revive marker entity refs keyed by the downed player's UUID.
     */
    private final Map<UUID, Ref<EntityStore>> reviveMarkers = new ConcurrentHashMap<>();

    public void spawnReviveMarker(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull Ref<EntityStore> deadPlayerRef,
                                  @Nonnull UUID playerId,
                                  @Nonnull Vector3d position) {
        despawnReviveMarker(commandBuffer, playerId);

        Holder<EntityStore> holder = store.getRegistry().newHolder();
        PlayerSkinComponent playerSkinComponent = store.getComponent(deadPlayerRef, PlayerSkinComponent.getComponentType());
        TransformComponent deadPlayerTransform = store.getComponent(deadPlayerRef, TransformComponent.getComponentType());
        holder.addComponent(TransformComponent.getComponentType(),
                new TransformComponent(new Vector3d(position),
                        deadPlayerTransform != null ? deadPlayerTransform.getRotation().clone() : Rotation3f.ZERO));
        holder.addComponent(UUIDComponent.getComponentType(), UUIDComponent.randomUUID());
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(Nameplate.getComponentType(), new Nameplate("downed"));
        holder.addComponent(Intangible.getComponentType(), Intangible.INSTANCE);
        holder.addComponent(Invulnerable.getComponentType(), Invulnerable.INSTANCE);
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
        if (playerSkinComponent != null) {
            holder.addComponent(PlayerSkinComponent.getComponentType(),
                    new PlayerSkinComponent(playerSkinComponent.getPlayerSkin()));
        }
        Model markerModel = buildReviveMarkerModel(playerSkinComponent);
        if (markerModel != null) {
            holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(markerModel));
            holder.addComponent(PersistentModel.getComponentType(), new PersistentModel(markerModel.toReference()));
            if (markerModel.getFirstBoundAnimationId(REVIVE_MARKER_DOWN_ANIMATION) != null) {
                ActiveAnimationComponent activeAnimationComponent = new ActiveAnimationComponent();
                activeAnimationComponent.setPlayingAnimation(AnimationSlot.Status, REVIVE_MARKER_DOWN_ANIMATION);
                holder.addComponent(ActiveAnimationComponent.getComponentType(), activeAnimationComponent);
            }
        }

        Ref<EntityStore> markerRef = commandBuffer.addEntity(holder, AddReason.SPAWN);
        reviveMarkers.put(playerId, markerRef);
    }

    public void despawnReviveMarker(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull UUID playerId) {
        Ref<EntityStore> markerRef = reviveMarkers.remove(playerId);
        if (markerRef == null || !markerRef.isValid()) return;

        if (markerRef.getStore() == commandBuffer.getStore()) {
            commandBuffer.tryRemoveEntity(markerRef, RemoveReason.REMOVE);
            return;
        }
        removeReviveMarkerOnOwningWorld(markerRef);
    }

    public void despawnReviveMarker(@Nonnull UUID playerId) {
        Ref<EntityStore> markerRef = reviveMarkers.remove(playerId);
        if (markerRef == null || !markerRef.isValid()) return;
        removeReviveMarkerOnOwningWorld(markerRef);
    }

    @Nullable
    public Vector3d getReviveMarkerPosition(@Nonnull UUID playerId) {
        Ref<EntityStore> markerRef = reviveMarkers.get(playerId);
        if (markerRef == null || !markerRef.isValid()) {
            reviveMarkers.remove(playerId, markerRef);
            return null;
        }
        TransformComponent transform = markerRef.getStore().getComponent(markerRef, TransformComponent.getComponentType());
        return transform != null ? new Vector3d(transform.getPosition()) : null;
    }

    public void clear() {
        reviveMarkers.clear();
    }

    private void removeReviveMarkerOnOwningWorld(@Nonnull Ref<EntityStore> markerRef) {
        Store<EntityStore> markerStore = markerRef.getStore();
        markerStore.getExternalData().getWorld().execute(() -> {
            if (markerRef.isValid()) {
                markerStore.removeEntity(markerRef, RemoveReason.REMOVE);
            }
        });
    }

    @Nullable
    private Model buildReviveMarkerModel(@Nullable PlayerSkinComponent playerSkinComponent) {
        if (playerSkinComponent != null) {
            Model playerModel = CosmeticsModule.get().createModel(playerSkinComponent.getPlayerSkin());
            if (playerModel != null) return playerModel;
        }
        ModelAsset markerModelAsset = ModelAsset.getAssetMap().getAsset(REVIVE_MARKER_MODEL_ID);
        return markerModelAsset != null ? Model.createUnitScaleModel(markerModelAsset) : null;
    }
}
