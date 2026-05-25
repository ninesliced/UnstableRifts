package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.protocol.PlayerSkinUpdate;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Logger;

/**
 * Applies and restores the visible ghost model used by the custom dungeon death state.
 */
public final class GhostPlayerAppearanceController {

    public static final String GHOST_MODEL_ID = "UnstableRifts_Player_Ghost";

    private static final Logger LOGGER = Logger.getLogger(GhostPlayerAppearanceController.class.getName());

    private static boolean warnedMissingGhostModel;

    private GhostPlayerAppearanceController() {
    }

    public static void apply(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull Store<EntityStore> store,
                             @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return;
        }

        Model ghostModel = createGhostModel();
        if (ghostModel == null) {
            return;
        }

        captureOriginalIfNeeded(commandBuffer, store, ref);
        queueGhostSkinClear(store, ref);

        ModelComponent currentModel = store.getComponent(ref, ModelComponent.getComponentType());
        if (!isGhostModel(currentModel)) {
            commandBuffer.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(ghostModel));
        }
    }

    public static void apply(@Nonnull Store<EntityStore> store,
                             @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return;
        }

        Model ghostModel = createGhostModel();
        if (ghostModel == null) {
            return;
        }

        captureOriginalIfNeeded(store, ref);
        queueGhostSkinClear(store, ref);

        ModelComponent currentModel = store.getComponent(ref, ModelComponent.getComponentType());
        if (!isGhostModel(currentModel)) {
            store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(ghostModel));
        }
    }

    public static void restore(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return;
        }

        RestoreModel restoreModel = resolveRestoreModel(store, ref);
        restoreOriginalPlayerSkin(commandBuffer, store, ref);
        if (restoreModel.model() != null) {
            commandBuffer.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(restoreModel.model()));
        }
        if (restoreModel.fromPlayerSkin()) {
            markPlayerSkinOutdated(store, ref);
        }
        commandBuffer.tryRemoveComponent(ref, GhostPlayerAppearanceComponent.getComponentType());
    }

    public static void restore(@Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return;
        }

        RestoreModel restoreModel = resolveRestoreModel(store, ref);
        restoreOriginalPlayerSkin(store, ref);
        if (restoreModel.model() != null) {
            store.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(restoreModel.model()));
        }
        if (restoreModel.fromPlayerSkin()) {
            markPlayerSkinOutdated(store, ref);
        }
        store.tryRemoveComponent(ref, GhostPlayerAppearanceComponent.getComponentType());
    }

    private static void queueGhostSkinClear(@Nonnull Store<EntityStore> store,
                                            @Nonnull Ref<EntityStore> ref) {
        EntityTrackerSystems.Visible visible = store.getComponent(ref, EntityTrackerSystems.Visible.getComponentType());
        if (visible == null || visible.visibleTo.isEmpty()) {
            return;
        }

        PlayerSkinUpdate update = new PlayerSkinUpdate((PlayerSkin) null);
        for (EntityTrackerSystems.EntityViewer viewer : visible.visibleTo.values()) {
            try {
                viewer.queueUpdate(ref, update);
            } catch (IllegalArgumentException ignored) {
                // Viewer visibility can change between collection and queueing.
            }
        }
    }

    private static void captureOriginalIfNeeded(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                @Nonnull Store<EntityStore> store,
                                                @Nonnull Ref<EntityStore> ref) {
        GhostPlayerAppearanceComponent existing = store.getComponent(ref, GhostPlayerAppearanceComponent.getComponentType());
        if (existing != null) {
            return;
        }
        commandBuffer.putComponent(ref, GhostPlayerAppearanceComponent.getComponentType(), createStateFromCurrentModel(store, ref));
    }

    private static void captureOriginalIfNeeded(@Nonnull Store<EntityStore> store,
                                                @Nonnull Ref<EntityStore> ref) {
        GhostPlayerAppearanceComponent existing = store.getComponent(ref, GhostPlayerAppearanceComponent.getComponentType());
        if (existing != null) {
            return;
        }
        store.putComponent(ref, GhostPlayerAppearanceComponent.getComponentType(), createStateFromCurrentModel(store, ref));
    }

    @Nonnull
    private static GhostPlayerAppearanceComponent createStateFromCurrentModel(@Nonnull Store<EntityStore> store,
                                                                              @Nonnull Ref<EntityStore> ref) {
        ModelComponent currentComponent = store.getComponent(ref, ModelComponent.getComponentType());
        Model currentModel = currentComponent != null ? currentComponent.getModel() : null;
        PlayerSkinComponent playerSkinComponent = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        PlayerSkin originalSkin = playerSkinComponent != null ? playerSkinComponent.getPlayerSkin() : null;
        if (!isValidCosmeticSkin(originalSkin)) {
            originalSkin = null;
        }
        boolean currentIsGhost = isGhostModel(currentComponent);
        boolean restoreFromPlayerSkin = currentModel == null
                || currentIsGhost
                || "Player".equals(currentModel.getModelAssetId());
        Model.ModelReference originalReference = currentModel != null && !currentIsGhost
                ? currentModel.toReference()
                : null;
        return new GhostPlayerAppearanceComponent(originalReference, originalSkin, restoreFromPlayerSkin);
    }

    @Nonnull
    private static RestoreModel resolveRestoreModel(@Nonnull Store<EntityStore> store,
                                                    @Nonnull Ref<EntityStore> ref) {
        GhostPlayerAppearanceComponent state = store.getComponent(ref, GhostPlayerAppearanceComponent.getComponentType());
        if (state != null) {
            if (state.shouldRestoreFromPlayerSkin()) {
                Model playerSkinModel = createPlayerSkinModel(store, ref, state);
                if (playerSkinModel != null) {
                    return new RestoreModel(playerSkinModel, true);
                }
            }

            Model.ModelReference originalReference = state.getOriginalModelReference();
            if (originalReference != null) {
                Model model = createModelFromReference(originalReference);
                if (model != null) {
                    return new RestoreModel(model, false);
                }
            }
        }

        ModelComponent currentModel = store.getComponent(ref, ModelComponent.getComponentType());
        if (isGhostModel(currentModel)) {
            Model playerSkinModel = createPlayerSkinModel(store, ref, state);
            if (playerSkinModel != null) {
                return new RestoreModel(playerSkinModel, true);
            }
            Model fallbackModel = createDefaultPlayerModel();
            if (fallbackModel != null) {
                return new RestoreModel(fallbackModel, false);
            }
        }

        return new RestoreModel(null, false);
    }

    @Nullable
    private static Model createGhostModel() {
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(GHOST_MODEL_ID);
        if (modelAsset == null) {
            if (!warnedMissingGhostModel) {
                warnedMissingGhostModel = true;
                LOGGER.warning("Missing ghost player model asset: " + GHOST_MODEL_ID);
            }
            return null;
        }
        return Model.createUnitScaleModel(modelAsset);
    }

    @Nullable
    private static Model createPlayerSkinModel(@Nonnull Store<EntityStore> store,
                                               @Nonnull Ref<EntityStore> ref,
                                               @Nullable GhostPlayerAppearanceComponent state) {
        PlayerSkinComponent playerSkinComponent = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        PlayerSkin playerSkin = playerSkinComponent != null ? playerSkinComponent.getPlayerSkin() : null;
        if (!isValidCosmeticSkin(playerSkin) && state != null) {
            playerSkin = state.getOriginalPlayerSkin();
        }
        if (!isValidCosmeticSkin(playerSkin)) {
            return null;
        }
        return CosmeticsModule.get().createModel(playerSkin);
    }

    @Nullable
    private static Model createModelFromReference(@Nonnull Model.ModelReference modelReference) {
        String modelAssetId = modelReference.getModelAssetId();
        if (modelAssetId == null) {
            return null;
        }
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(modelAssetId);
        if (modelAsset == null) {
            return null;
        }
        if (modelReference.getScale() <= 0.0f) {
            return Model.createUnitScaleModel(modelAsset);
        }
        try {
            return modelReference.toModel();
        } catch (IllegalArgumentException exception) {
            LOGGER.warning("Failed to restore model reference " + modelReference
                    + "; falling back to unit scale model: " + exception.getMessage());
            return Model.createUnitScaleModel(modelAsset);
        }
    }

    @Nullable
    private static Model createDefaultPlayerModel() {
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset("Player");
        return modelAsset != null ? Model.createUnitScaleModel(modelAsset) : null;
    }

    private static void restoreOriginalPlayerSkin(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                  @Nonnull Store<EntityStore> store,
                                                  @Nonnull Ref<EntityStore> ref) {
        GhostPlayerAppearanceComponent state = store.getComponent(ref, GhostPlayerAppearanceComponent.getComponentType());
        PlayerSkin originalSkin = state != null ? state.getOriginalPlayerSkin() : null;
        if (isValidCosmeticSkin(originalSkin)) {
            commandBuffer.putComponent(ref, PlayerSkinComponent.getComponentType(), new PlayerSkinComponent(new PlayerSkin(originalSkin)));
            return;
        }

        PlayerSkinComponent currentSkin = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (currentSkin != null && !isValidCosmeticSkin(currentSkin.getPlayerSkin())) {
            commandBuffer.tryRemoveComponent(ref, PlayerSkinComponent.getComponentType());
            return;
        }

        markPlayerSkinOutdated(store, ref);
    }

    private static void restoreOriginalPlayerSkin(@Nonnull Store<EntityStore> store,
                                                  @Nonnull Ref<EntityStore> ref) {
        GhostPlayerAppearanceComponent state = store.getComponent(ref, GhostPlayerAppearanceComponent.getComponentType());
        PlayerSkin originalSkin = state != null ? state.getOriginalPlayerSkin() : null;
        if (isValidCosmeticSkin(originalSkin)) {
            store.putComponent(ref, PlayerSkinComponent.getComponentType(), new PlayerSkinComponent(new PlayerSkin(originalSkin)));
            return;
        }

        PlayerSkinComponent currentSkin = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (currentSkin != null && !isValidCosmeticSkin(currentSkin.getPlayerSkin())) {
            store.tryRemoveComponent(ref, PlayerSkinComponent.getComponentType());
            return;
        }

        markPlayerSkinOutdated(store, ref);
    }

    private static void markPlayerSkinOutdated(@Nonnull Store<EntityStore> store,
                                               @Nonnull Ref<EntityStore> ref) {
        PlayerSkinComponent playerSkinComponent = store.getComponent(ref, PlayerSkinComponent.getComponentType());
        if (playerSkinComponent != null) {
            playerSkinComponent.setNetworkOutdated();
        }
    }

    private static boolean isGhostModel(@Nullable ModelComponent modelComponent) {
        Model model = modelComponent != null ? modelComponent.getModel() : null;
        return model != null && GHOST_MODEL_ID.equals(model.getModelAssetId());
    }

    private static boolean isBlankPlayerSkin(@Nonnull PlayerSkin skin) {
        return skin.bodyCharacteristic == null
                && skin.underwear == null
                && skin.face == null
                && skin.eyes == null
                && skin.ears == null
                && skin.mouth == null
                && skin.facialHair == null
                && skin.haircut == null
                && skin.eyebrows == null
                && skin.pants == null
                && skin.overpants == null
                && skin.undertop == null
                && skin.overtop == null
                && skin.shoes == null
                && skin.headAccessory == null
                && skin.faceAccessory == null
                && skin.earAccessory == null
                && skin.skinFeature == null
                && skin.gloves == null
                && skin.cape == null;
    }

    private static boolean isValidCosmeticSkin(@Nullable PlayerSkin skin) {
        if (skin == null || isBlankPlayerSkin(skin)) {
            return false;
        }
        try {
            CosmeticsModule.get().validateSkin(skin);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private record RestoreModel(@Nullable Model model, boolean fromPlayerSkin) {
    }
}
