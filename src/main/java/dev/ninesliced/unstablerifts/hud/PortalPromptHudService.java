package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Show/hide logic for the portal confirmation HUD with per-player deduplication.
 */
public final class PortalPromptHudService {

    private static final String HUD_IDENTIFIER = PortalPromptHud.HUD_ID;
    private static final long STATE_HIDDEN = 0L;

    private static final ConcurrentHashMap<UUID, Long> LAST_STATE = new ConcurrentHashMap<>();

    private PortalPromptHudService() {
    }

    public static void show(@Nonnull Player player,
                            @Nonnull PlayerRef playerRef,
                            @Nonnull String title,
                            @Nonnull String detail) {
        if (HudVisibilityService.isHidden(playerRef.getUuid())) {
            return;
        }
        long state = computeState(title, detail);
        UUID uuid = playerRef.getUuid();

        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == state) {
            return;
        }
        LAST_STATE.put(uuid, state);

        PortalPromptHud hud = new PortalPromptHud(playerRef, title, detail);
        player.getHudManager().addCustomHud(playerRef, hud);
    }

    public static void hide(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        Long previous = LAST_STATE.get(uuid);
        if (previous == null || previous == STATE_HIDDEN) {
            return;
        }
        LAST_STATE.put(uuid, STATE_HIDDEN);

        player.getHudManager().removeCustomHud(playerRef, HUD_IDENTIFIER);
    }

    public static void clear(@Nonnull PlayerRef playerRef) {
        LAST_STATE.remove(playerRef.getUuid());
    }

    public static boolean isActive(@Nonnull UUID playerUuid) {
        Long state = LAST_STATE.get(playerUuid);
        return state != null && state != STATE_HIDDEN;
    }

    public static void clearAll() {
        LAST_STATE.clear();
    }

    private static long computeState(@Nonnull String title, @Nonnull String detail) {
        long h = 9721L;
        h = 31L * h + title.hashCode();
        h = 31L * h + detail.hashCode();
        return h;
    }
}
