package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.unstablerifts.dungeon.DungeonConstants;

import javax.annotation.Nonnull;

/**
 * HUD overlay shown to alive players while they are reviving a dead teammate.
 * Displays a progress bar and time text.
 */
public final class ReviveProgressHud extends CustomUIHud {

    public static final String UI_PATH = "Hud/UnstableRifts/ReviveProgress.ui";
    public static final String HUD_ID = "UnstableRiftsReviveProgress";

    private static final int BAR_MAX_WIDTH = 192;
    private static final int BAR_HEIGHT = 10;
    private static final float REVIVE_DURATION = 5.0f;

    private final String targetName;
    private final float progress;

    public ReviveProgressHud(@Nonnull PlayerRef playerRef,
                             @Nonnull String targetName,
                             float progress) {
        super(playerRef, HUD_ID);
        this.targetName = targetName;
        this.progress = progress;
    }

    public static void applyHud(@Nonnull Player player, @Nonnull PlayerRef playerRef,
                                @Nonnull ReviveProgressHud hud) {
        if (HudVisibilityService.isHidden(playerRef.getUuid())) {
            return;
        }
        player.getHudManager().addCustomHud(playerRef, hud);
    }

    public static void hideHud(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        player.getHudManager().removeCustomHud(playerRef, HUD_ID);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);
        ui.set("#ReviveProgressRoot.Visible", true);

        ui.set("#ReviveLabel.TextSpans", Message.raw("Reviving " + targetName + "..."));

        float ratio = Math.max(0f, Math.min(1f, progress / REVIVE_DURATION));
        int fillWidth = Math.round(BAR_MAX_WIDTH * ratio);

        Anchor barAnchor = new Anchor();
        barAnchor.setLeft(Value.of(0));
        barAnchor.setTop(Value.of(0));
        barAnchor.setWidth(Value.of(fillWidth));
        barAnchor.setHeight(Value.of(BAR_HEIGHT));
        ui.setObject("#ReviveBarFill.Anchor", barAnchor);

        ui.set("#ReviveBarFill.Background",
                ratio > 0.8f ? DungeonConstants.COLOR_POSITIVE_BRIGHT : DungeonConstants.COLOR_POSITIVE);

        String timeText = String.format("%.1f / %.1fs", Math.min(progress, REVIVE_DURATION), REVIVE_DURATION);
        ui.set("#ReviveTimeLabel.TextSpans", Message.raw(timeText));
    }
}
