package dev.ninesliced.shotcave.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MouseInputTargetType;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.protocol.packets.setup.ClientFeature;
import com.hypixel.hytale.protocol.packets.setup.UpdateFeatures;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TopCameraService {

    private static final float HALF_PI = (float) (Math.PI / 2.0);

    private final Map<UUID, Boolean> enabled = new ConcurrentHashMap<>();
    // Manual /shotcave topcamera toggles should survive dungeon cleanup resets.
    private final Map<UUID, Boolean> persistentEnabled = new ConcurrentHashMap<>();
    private final Set<UUID> pendingEnable = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> lastRoomRotation = new ConcurrentHashMap<>();

    public void registerDisabledByDefault(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        enabled.put(playerId, false);
        persistentEnabled.put(playerId, false);
        pendingEnable.remove(playerId);
    }

    public void enableByDefault(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        persistentEnabled.put(playerId, true);
        pendingEnable.remove(playerId);
        setResolvedState(playerRef, true, true);
    }

    public void scheduleEnableOnNextReady(@Nonnull PlayerRef playerRef) {
        enabled.put(playerRef.getUuid(), true);
        pendingEnable.add(playerRef.getUuid());
    }

    public void cancelDeferredEnable(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        boolean hadPending = pendingEnable.remove(playerId);
        boolean keepEnabled = persistentEnabled.getOrDefault(playerId, false);
        boolean hadRoomRotation = lastRoomRotation.remove(playerId) != null;
        setResolvedState(playerRef, keepEnabled, keepEnabled && (hadPending || hadRoomRotation));
    }

    public void clearState(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        enabled.remove(playerId);
        persistentEnabled.remove(playerId);
        pendingEnable.remove(playerId);
        lastRoomRotation.remove(playerId);
    }

    public boolean toggle(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        boolean nextEnabled = !enabled.getOrDefault(playerId, false);
        persistentEnabled.put(playerId, nextEnabled);
        pendingEnable.remove(playerId);
        return setResolvedState(playerRef, nextEnabled, false);
    }

    public boolean isEnabled(@Nonnull UUID uuid) {
        return enabled.getOrDefault(uuid, false);
    }

    public boolean setEnabled(@Nonnull PlayerRef playerRef, boolean enable) {
        pendingEnable.remove(playerRef.getUuid());
        return setResolvedState(playerRef, enable, false);
    }

    public void restoreDefault(@Nonnull PlayerRef playerRef) {
        UUID playerId = playerRef.getUuid();
        boolean hadPending = pendingEnable.remove(playerId);
        boolean shouldStayEnabled = persistentEnabled.getOrDefault(playerId, false);
        boolean hadRoomRotation = lastRoomRotation.remove(playerId) != null;
        setResolvedState(playerRef, shouldStayEnabled, shouldStayEnabled && (hadPending || hadRoomRotation));
    }

    public void refreshMovementProfile(@Nonnull PlayerRef playerRef) {
        restoreDefaultMovement(playerRef);

        if (enabled.getOrDefault(playerRef.getUuid(), false)) {
            disableSprintForce(playerRef);
            applyEqualizedMovement(playerRef);
            return;
        }

        enableSprintForce(playerRef);
    }

    public void handlePlayerReady(@Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        if (!pendingEnable.remove(playerId) || !enabled.getOrDefault(playerId, false)) {
            return;
        }

        applyTopCamera(playerRef);
    }

    /**
     * Called when the player enters a new room. Smoothly rotates the camera
     * yaw to align with the corridor direction so the camera shifts to the
     * side on turns instead of clipping through walls.
     */
    public void updateCameraForRoom(@Nonnull PlayerRef playerRef, int roomRotation) {
        if (!enabled.getOrDefault(playerRef.getUuid(), false)) return;

        Integer last = lastRoomRotation.get(playerRef.getUuid());
        if (last != null && last == roomRotation) return;

        lastRoomRotation.put(playerRef.getUuid(), roomRotation);
        sendCameraPacket(playerRef, roomRotationToYaw(roomRotation));
    }

    private void applyTopCamera(@Nonnull PlayerRef playerRef) {
        lastRoomRotation.remove(playerRef.getUuid());
        sendCameraPacket(playerRef, 0.0F);

        disableSprintForce(playerRef);
        applyEqualizedMovement(playerRef);
    }

    private void sendCameraPacket(@Nonnull PlayerRef playerRef, float yaw) {
        ServerCameraSettings cameraSettings = new ServerCameraSettings();
        cameraSettings.positionLerpSpeed = 0.05F;
        cameraSettings.rotationLerpSpeed = 0.08F;
        cameraSettings.distance = 14.0F;
        cameraSettings.allowPitchControls = false;
        cameraSettings.displayCursor = true;
        cameraSettings.displayReticle = false;
        cameraSettings.sendMouseMotion = true;
        cameraSettings.mouseInputTargetType = MouseInputTargetType.None;
        cameraSettings.isFirstPerson = false;
        cameraSettings.movementForceRotationType = MovementForceRotationType.Custom;
        cameraSettings.eyeOffset = true;
        cameraSettings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffsetRaycast;
        cameraSettings.positionOffset = new Position(0.0, 3.0, 0.0);
        cameraSettings.rotationType = RotationType.Custom;
        cameraSettings.rotation = new Direction(yaw, -0.9F, 0.0F);
        cameraSettings.mouseInputType = MouseInputType.LookAtPlane;
        cameraSettings.planeNormal = new Vector3f(0.0F, 1.0F, 0.0F);
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, cameraSettings));
    }

    private static float roomRotationToYaw(int roomRotation) {
        return switch (roomRotation & 3) {
            case 1 -> HALF_PI;
            case 2 -> HALF_PI * 2;
            case 3 -> -HALF_PI;
            default -> 0.0F;
        };
    }

    private boolean setResolvedState(@Nonnull PlayerRef playerRef, boolean enable, boolean forceReapply) {
        UUID playerId = playerRef.getUuid();
        boolean wasEnabled = enabled.getOrDefault(playerId, false);
        enabled.put(playerId, enable);

        if (enable) {
            if (forceReapply || !wasEnabled) {
                applyTopCamera(playerRef);
            }
            return true;
        }

        if (forceReapply || wasEnabled) {
            resetCamera(playerRef);
        }
        return false;
    }

    private void resetCamera(@Nonnull PlayerRef playerRef) {
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));

        enableSprintForce(playerRef);
        restoreDefaultMovement(playerRef);
    }

    private void disableSprintForce(@Nonnull PlayerRef playerRef) {
        Map<ClientFeature, Boolean> features = new EnumMap<>(ClientFeature.class);
        features.put(ClientFeature.SprintForce, false);
        playerRef.getPacketHandler().writeNoCache(new UpdateFeatures(features));
    }

    private void enableSprintForce(@Nonnull PlayerRef playerRef) {
        Map<ClientFeature, Boolean> features = new EnumMap<>(ClientFeature.class);
        features.put(ClientFeature.SprintForce, true);
        playerRef.getPacketHandler().writeNoCache(new UpdateFeatures(features));
    }

    private void applyEqualizedMovement(@Nonnull PlayerRef playerRef) {
        MovementManager movementManager = getMovementManager(playerRef);
        if (movementManager == null) {
            return;
        }

        MovementSettings settings = movementManager.getSettings();
        if (settings != null) {
            float sprintSpeed = settings.forwardSprintSpeedMultiplier;
            settings.forwardRunSpeedMultiplier = sprintSpeed;
            settings.backwardRunSpeedMultiplier = sprintSpeed;
            settings.strafeRunSpeedMultiplier = sprintSpeed;

            float walkSpeed = settings.forwardWalkSpeedMultiplier;
            settings.backwardWalkSpeedMultiplier = walkSpeed;
            settings.strafeWalkSpeedMultiplier = walkSpeed;

            float crouchSpeed = settings.forwardCrouchSpeedMultiplier;
            settings.backwardCrouchSpeedMultiplier = crouchSpeed;
            settings.strafeCrouchSpeedMultiplier = crouchSpeed;

            movementManager.update(playerRef.getPacketHandler());
        }
    }

    private void restoreDefaultMovement(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        Store<EntityStore> store = ref.getStore();
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null) {
            return;
        }

        movementManager.resetDefaultsAndUpdate(ref, store);
    }

    @Nullable
    private MovementManager getMovementManager(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }

        return ref.getStore().getComponent(ref, MovementManager.getComponentType());
    }
}
