package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public final class RevivePromptHud extends CustomUIHud {

    public static final String UI_PATH = "Hud/UnstableRifts/RevivePrompt.ui";
    public static final String HUD_ID = "UnstableRifts_RevivePrompt";

    private final String targetName;

    public RevivePromptHud(@Nonnull PlayerRef playerRef, @Nonnull String targetName) {
        super(playerRef, HUD_ID);
        this.targetName = targetName;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);
        ui.set("#RevivePromptRoot.Visible", true);
        ui.set("#RevivePromptTarget.TextSpans", Message.raw(targetName));
        ui.set("#RevivePromptAction.TextSpans", Message.raw("Revive"));
    }
}
