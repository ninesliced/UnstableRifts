package dev.ninesliced.shotcave.camera;

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

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TopCameraService {

    private final Map<UUID, Boolean> enabled = new ConcurrentHashMap<>();

    public void registerDisabledByDefault(@Nonnull PlayerRef playerRef) {
        enabled.putIfAbsent(playerRef.getUuid(), false);
    }

    public boolean toggle(@Nonnull PlayerRef playerRef) {
        return setEnabled(playerRef, !enabled.getOrDefault(playerRef.getUuid(), false));
    }

    public boolean setEnabled(@Nonnull PlayerRef playerRef, boolean enable) {
        enabled.put(playerRef.getUuid(), enable);
        if (enable) {
            applyTopCamera(playerRef);
        } else {
            resetCamera(playerRef);
        }
        return enable;
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
        cameraSettings.movementForceRotationType = MovementForceRotationType.AttachedToHead;
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