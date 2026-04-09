package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RevivePromptHudService {

    private static final String HUD_IDENTIFIER = "UnstableRifts_RevivePrompt";
    private static final long STATE_HIDDEN = 0L;

    private static final ConcurrentHashMap<UUID, Long> LAST_STATE = new ConcurrentHashMap<>();

    private RevivePromptHudService() {
    }

    public static void show(@Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull String targetName) {
        if (HudVisibilityService.isHidden(playerRef.getUuid())) {
            return;
        }
        long state = targetName.hashCode();
        UUID uuid = playerRef.getUuid();

        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == state) {
            return;
        }
        LAST_STATE.put(uuid, state);

        RevivePromptHud hud = new RevivePromptHud(playerRef, targetName);
        if (!MultiHudCompat.setHud(player, playerRef, HUD_IDENTIFIER, hud)) {
            player.getHudManager().setCustomHud(playerRef, hud);
        }
    }

    public static void hide(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        Long previous = LAST_STATE.get(uuid);
        if (previous != null && previous == STATE_HIDDEN) {
            return;
        }
        LAST_STATE.put(uuid, STATE_HIDDEN);

        if (!MultiHudCompat.hideHud(player, playerRef, HUD_IDENTIFIER)) {
            player.getHudManager().setCustomHud(playerRef, null);
        }
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
}