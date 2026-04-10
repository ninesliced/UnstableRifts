package dev.ninesliced.unstablerifts.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public final class PortalPromptHud extends CustomUIHud {

    public static final String UI_PATH = "Hud/UnstableRifts/PortalPrompt.ui";
    public static final String HUD_ID = "UnstableRifts_PortalPrompt";

    private final String title;
    private final String detail;

    public PortalPromptHud(@Nonnull PlayerRef playerRef,
                           @Nonnull String title,
                           @Nonnull String detail) {
        super(playerRef, HUD_ID);
        this.title = title;
        this.detail = detail;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);
        ui.set("#PortalPromptRoot.Visible", true);
        ui.set("#PortalPromptTitle.TextSpans", Message.raw(title));
        ui.set("#PortalPromptDetail.TextSpans", Message.raw(detail));
    }
}
