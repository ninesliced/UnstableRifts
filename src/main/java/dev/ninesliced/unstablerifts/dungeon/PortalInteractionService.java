package dev.ninesliced.unstablerifts.dungeon;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.UnstableRifts;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared portal interaction rules used by the HUD prompt and F-key activation.
 */
public final class PortalInteractionService {

    private static final long PORTAL_ENTRY_ARM_DELAY_MS = 1500L;

    private final UnstableRifts plugin;
    private final Map<UUID, Long> lastPortalStepOffAtByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> partiesInTransit = ConcurrentHashMap.newKeySet();

    public PortalInteractionService(@Nonnull UnstableRifts plugin) {
        this.plugin = plugin;
    }

    @Nullable
    public PortalPrompt resolvePrompt(@Nonnull Game game,
                                      @Nonnull UUID playerId,
                                      @Nonnull Vector3d playerPos) {
        Level level = game.getCurrentLevel();
        if (level == null) {
            clearPlayer(playerId);
            return null;
        }

        int bx = (int) Math.floor(playerPos.x);
        int by = (int) Math.floor(playerPos.y);
        int bz = (int) Math.floor(playerPos.z);
        PortalService.ActivePortal activePortal = plugin.getPortalService().getActivePortalAt(level, bx, by, bz);
        if (activePortal == null) {
            lastPortalStepOffAtByPlayer.put(playerId, System.currentTimeMillis());
            return null;
        }

        long now = System.currentTimeMillis();
        if (now - activePortal.activatedAt() < PORTAL_ENTRY_ARM_DELAY_MS) {
            return null;
        }

        long lastStepOffAt = lastPortalStepOffAtByPlayer.getOrDefault(playerId, Long.MIN_VALUE);
        if (lastStepOffAt <= activePortal.activatedAt()) {
            return null;
        }

        PortalMode portalMode = activePortal.portal().mode();
        if (portalMode == PortalMode.NEXT_LEVEL && game.getState() != GameState.TRANSITIONING) {
            return null;
        }

        return new PortalPrompt(activePortal, buildTitle(game, portalMode), buildDetail(game, portalMode));
    }

    public boolean tryInteract(@Nonnull PlayerRef playerRef,
                               @Nonnull Vector3d playerPos) {
        GameManager gameManager = plugin.getGameManager();
        Game game = gameManager.findGameForPlayer(playerRef.getUuid());
        if (game == null) {
            clearPlayer(playerRef.getUuid());
            return false;
        }

        PortalPrompt prompt = resolvePrompt(game, playerRef.getUuid(), playerPos);
        if (prompt == null) {
            return false;
        }

        if (prompt.activePortal().portal().mode() == PortalMode.NEXT_LEVEL) {
            if (!partiesInTransit.add(game.getPartyId())) {
                return true;
            }

            try {
                gameManager.onPortalEntered(game, playerRef.getUuid());
            } finally {
                partiesInTransit.remove(game.getPartyId());
            }
            return true;
        }

        Level level = game.getCurrentLevel();
        if (level == null) {
            return false;
        }

        int bx = (int) Math.floor(playerPos.x);
        int by = (int) Math.floor(playerPos.y);
        int bz = (int) Math.floor(playerPos.z);
        RoomData sourceRoom = level.findRoomAt(bx, by, bz);
        RoomData fallbackRoom = prompt.activePortal().room();
        if (sourceRoom == null) {
            sourceRoom = fallbackRoom;
        }

        gameManager.onClosestExitPortalEntered(game, playerRef.getUuid(), sourceRoom, fallbackRoom);
        return true;
    }

    public void clearPlayer(@Nonnull UUID playerId) {
        lastPortalStepOffAtByPlayer.remove(playerId);
    }

    public void clearAll() {
        lastPortalStepOffAtByPlayer.clear();
        partiesInTransit.clear();
    }

    @Nonnull
    private String buildTitle(@Nonnull Game game, @Nonnull PortalMode portalMode) {
        return switch (portalMode) {
            case NEXT_LEVEL -> plugin.getGameManager().hasNextLevelConfig(game)
                    ? "Advance to Next Level"
                    : "Return to Surface";
            case CLOSEST_EXIT -> "Closest Exit";
        };
    }

    @Nonnull
    private String buildDetail(@Nonnull Game game, @Nonnull PortalMode portalMode) {
        return switch (portalMode) {
            case NEXT_LEVEL -> plugin.getGameManager().hasNextLevelConfig(game)
                    ? "Teleport the party to the next floor"
                    : "Leave the dungeon";
            case CLOSEST_EXIT -> "Teleport to the nearest unlocked exit";
        };
    }

    public record PortalPrompt(@Nonnull PortalService.ActivePortal activePortal,
                               @Nonnull String title,
                               @Nonnull String detail) {
    }
}
