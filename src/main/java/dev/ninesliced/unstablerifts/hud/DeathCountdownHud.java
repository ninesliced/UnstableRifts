package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.dungeon.DungeonConstants;

import javax.annotation.Nonnull;

/**
 * HUD overlay shown to dead players during the 30s revive window.
 * Displays a countdown timer and hint text.
 */
public final class DeathCountdownHud extends CustomUIHud {

    public static final String UI_PATH = "Hud/UnstableRifts/DeathCountdown.ui";
    public static final String HUD_ID = "UnstableRiftsDeathCountdown";

    private final int remainingSeconds;
    private final boolean beingRevived;
    private final float reviveProgress;

    public DeathCountdownHud(@Nonnull PlayerRef playerRef,
                             int remainingSeconds,
                             boolean beingRevived,
                             float reviveProgress) {
        super(playerRef);
        this.remainingSeconds = remainingSeconds;
        this.beingRevived = beingRevived;
        this.reviveProgress = reviveProgress;
    }

    public static void applyHud(@Nonnull Player player, @Nonnull PlayerRef playerRef,
                                @Nonnull DeathCountdownHud hud) {
        if (HudVisibilityService.isHidden(playerRef.getUuid())) {
            return;
        }
        MultiHudCompat.setHud(player, playerRef, HUD_ID, hud);
    }

    public static void hideHud(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        MultiHudCompat.hideHud(player, playerRef, HUD_ID);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);
        ui.set("#DeathCountdownRoot.Visible", true);

        ui.set("#DeathTitleLabel.TextSpans", Message.raw("YOU DIED"));
        ui.set("#DeathTitleLabel.Style.TextColor", DungeonConstants.COLOR_CRITICAL);

        if (beingRevived) {
            int progressPercent = Math.round(Math.min(1f, reviveProgress / 5.0f) * 100f);
            ui.set("#DeathCountdownLabel.TextSpans",
                    Message.raw("Being revived... " + progressPercent + "%"));
            ui.set("#DeathCountdownLabel.Style.TextColor", DungeonConstants.COLOR_SUCCESS);
            ui.set("#DeathHintLabel.TextSpans", Message.raw("Hold still!"));
        } else {
            ui.set("#DeathCountdownLabel.TextSpans",
                    Message.raw("Revive window: " + remainingSeconds + "s"));
            ui.set("#DeathCountdownLabel.Style.TextColor",
                    remainingSeconds <= 10 ? DungeonConstants.COLOR_CRITICAL : "#e8ecf0");
            ui.set("#DeathHintLabel.TextSpans",
                    Message.raw("Teammates can hold the interaction key near you to revive"));
        }
    }
}
