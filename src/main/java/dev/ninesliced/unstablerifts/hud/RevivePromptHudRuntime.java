package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.dungeon.GameManager;
import dev.ninesliced.unstablerifts.dungeon.GameState;
import dev.ninesliced.unstablerifts.logging.UnstableRiftsLog;
import dev.ninesliced.unstablerifts.player.OnlinePlayers;
import dev.ninesliced.unstablerifts.systems.DeathComponent;
import dev.ninesliced.unstablerifts.systems.ReviveInteractionPacketHandler;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class RevivePromptHudRuntime {

    private static final HytaleLogger LOGGER = UnstableRiftsLog.forModule("Revive");
    private static final long POLL_INTERVAL_MS = 150L;
    private static final double REVIVE_RANGE = 2.0;
    private static final double REVIVE_RANGE_SQ = REVIVE_RANGE * REVIVE_RANGE;

    private ScheduledFuture<?> pollTask;

    public void start(@Nonnull UnstableRifts plugin) {
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
        startPoller();
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        RevivePromptHudService.clearAll();
    }

    public void onPlayerConnect(@Nonnull PlayerRef playerRef) {
        RevivePromptHudService.clear(playerRef);
    }

    private void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        RevivePromptHudService.clear(event.getPlayerRef());
    }

    private void startPoller() {
        if (pollTask != null) {
            pollTask.cancel(false);
        }
        pollTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                this::pollAllPlayers,
                POLL_INTERVAL_MS,
                POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void pollAllPlayers() {
        try {
            for (PlayerRef playerRef : OnlinePlayers.snapshot()) {
                pollSinglePlayer(playerRef);
            }
        } catch (Exception e) {
            LOGGER.at(java.util.logging.Level.WARNING).withCause(e).log("Revive HUD poll failed");
        }
    }

    private void pollSinglePlayer(@Nonnull PlayerRef playerRef) {
        if (!playerRef.isValid()) {
            return;
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        ref.getStore().getExternalData().getWorld().execute(() -> {
            try {
                if (!ref.isValid()) {
                    return;
                }

                Player player = ref.getStore().getComponent(ref, Player.getComponentType());
                if (player == null || player.wasRemoved()) {
                    return;
                }

                DeathComponent deathComponent = ref.getStore().getComponent(ref, DeathComponent.getComponentType());
                if (deathComponent != null && deathComponent.isDead()) {
                    RevivePromptHudService.hide(player, playerRef);
                    return;
                }

                TransformComponent transform = ref.getStore().getComponent(ref, TransformComponent.getComponentType());
                if (transform == null) {
                    RevivePromptHudService.hide(player, playerRef);
                    return;
                }

                UnstableRifts unstablerifts = UnstableRifts.getInstance();
                if (unstablerifts == null) {
                    RevivePromptHudService.hide(player, playerRef);
                    return;
                }

                GameManager gameManager = unstablerifts.getGameManager();
                Game game = gameManager.findGameForPlayer(playerRef.getUuid());
                if (game == null || (game.getState() != GameState.ACTIVE && game.getState() != GameState.BOSS)) {
                    RevivePromptHudService.hide(player, playerRef);
                    return;
                }

                Vector3d myPos = transform.getPosition();
                String nearestName = null;
                double nearestDistSq = Double.MAX_VALUE;
                boolean interactionActive = ReviveInteractionPacketHandler.isInteractionActive(
                        playerRef.getUuid(), System.currentTimeMillis());

                for (UUID deadUuid : game.getDeadPlayers()) {
                    if (deadUuid.equals(playerRef.getUuid())) {
                        continue;
                    }

                    PlayerRef deadRef = Universe.get().getPlayer(deadUuid);
                    if (deadRef == null || !deadRef.isValid()) {
                        continue;
                    }

                    Ref<EntityStore> deadEntityRef = deadRef.getReference();
                    if (deadEntityRef == null || !deadEntityRef.isValid()) {
                        continue;
                    }

                    DeathComponent deadDeath = deadEntityRef.getStore().getComponent(deadEntityRef, DeathComponent.getComponentType());
                    if (deadDeath == null || !deadDeath.isInReviveWindow()) {
                        continue;
                    }

                    Vector3d revivePos = gameManager.getReviveMarkerService().getReviveMarkerPosition(deadUuid);
                    if (revivePos == null) {
                        continue;
                    }

                    double dx = myPos.x - revivePos.x;
                    double dy = myPos.y - revivePos.y;
                    double dz = myPos.z - revivePos.z;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > REVIVE_RANGE_SQ || distSq >= nearestDistSq) {
                        continue;
                    }

                    Player deadPlayer = deadEntityRef.getStore().getComponent(deadEntityRef, Player.getComponentType());
                    nearestName = deadPlayer != null && deadPlayer.getDisplayName() != null
                            ? deadPlayer.getDisplayName()
                            : deadRef.getUsername();
                    nearestDistSq = distSq;
                }

                if (nearestName != null && !interactionActive) {
                    RevivePromptHudService.show(player, playerRef, nearestName);
                } else {
                    RevivePromptHudService.hide(player, playerRef);
                }
            } catch (Exception e) {
                LOGGER.at(java.util.logging.Level.FINE).withCause(e).log("Error polling revive HUD for player");
            }
        });
    }
}
