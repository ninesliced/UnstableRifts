package dev.ninesliced.shotcave.camera;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MouseInputTargetType;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.Vector3f;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TopCameraService {

    private final Map<UUID, Boolean> enabled = new ConcurrentHashMap<>();
    private final Set<UUID> pendingEnable = ConcurrentHashMap.newKeySet();

    public void registerDisabledByDefault(@Nonnull PlayerRef playerRef) {
        enabled.put(playerRef.getUuid(), false);
        pendingEnable.remove(playerRef.getUuid());
    }

    public void enableByDefault(@Nonnull PlayerRef playerRef) {
        enabled.put(playerRef.getUuid(), true);
        pendingEnable.remove(playerRef.getUuid());
        applyTopCamera(playerRef);
    }

    public void scheduleEnableOnNextReady(@Nonnull PlayerRef playerRef) {
        enabled.put(playerRef.getUuid(), true);
        pendingEnable.add(playerRef.getUuid());
    }

    public void cancelDeferredEnable(@Nonnull PlayerRef playerRef) {
        enabled.put(playerRef.getUuid(), false);
        pendingEnable.remove(playerRef.getUuid());
    }

    public void clearState(@Nonnull PlayerRef playerRef) {
        enabled.remove(playerRef.getUuid());
        pendingEnable.remove(playerRef.getUuid());
    }

    public boolean toggle(@Nonnull PlayerRef playerRef) {
        return setEnabled(playerRef, !enabled.getOrDefault(playerRef.getUuid(), false));
    }

    public boolean setEnabled(@Nonnull PlayerRef playerRef, boolean enable) {
        enabled.put(playerRef.getUuid(), enable);
        if (enable) {
            pendingEnable.remove(playerRef.getUuid());
            applyTopCamera(playerRef);
        } else {
            pendingEnable.remove(playerRef.getUuid());
            resetCamera(playerRef);
        }
        return enable;
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

    private void applyTopCamera(@Nonnull PlayerRef playerRef) {
        ServerCameraSettings cameraSettings = new ServerCameraSettings();
        cameraSettings.positionLerpSpeed = 0.2F;
        cameraSettings.rotationLerpSpeed = 0.2F;
        cameraSettings.distance = 6.0F;
        cameraSettings.allowPitchControls = false;
        cameraSettings.displayCursor = true;
        cameraSettings.displayReticle = false;
        cameraSettings.sendMouseMotion = true;
        cameraSettings.mouseInputTargetType = MouseInputTargetType.None;
        cameraSettings.isFirstPerson = false;
        cameraSettings.movementForceRotationType = MovementForceRotationType.Custom;
        cameraSettings.eyeOffset = true;
        cameraSettings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffsetRaycast;
        cameraSettings.rotationType = RotationType.Custom;
        cameraSettings.rotation = new Direction(0.0F, -0.9F, 0.0F);
        cameraSettings.mouseInputType = MouseInputType.LookAtPlane;
        cameraSettings.planeNormal = new Vector3f(0.0F, 1.0F, 0.0F);
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, cameraSettings));
    }

    private void resetCamera(@Nonnull PlayerRef playerRef) {
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
    }
}