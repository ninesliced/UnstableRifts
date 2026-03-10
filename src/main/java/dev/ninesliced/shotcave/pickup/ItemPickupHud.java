package dev.ninesliced.shotcave.pickup;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * HUD overlay shown near F-key-protected items. Displays item icon, name, and
 * F-key collect prompt.
 */
public final class ItemPickupHud extends CustomUIHud {

    public static final String UI_PATH = "Hud/Shotcave/CratePickupHud.ui";
    public static final String HUD_ID = "ItemPickup";

    private final String itemDisplayName;
    private final String itemIconPath;
    private final int itemQuantity;

    public ItemPickupHud(@Nonnull PlayerRef playerRef,
            @Nonnull String itemDisplayName,
            @Nullable String itemIconPath,
            int itemQuantity) {
        super(playerRef);
        this.itemDisplayName = itemDisplayName;
        this.itemIconPath = itemIconPath;
        this.itemQuantity = Math.max(0, itemQuantity);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);
        ui.set("#CratePickupRoot.Visible", true);

        if (this.itemIconPath != null && !this.itemIconPath.isBlank()) {
            ui.set("#CratePickupIcon.AssetPath", this.itemIconPath);
            ui.set("#CratePickupIcon.Visible", true);
        } else {
            ui.set("#CratePickupIcon.Visible", false);
        }

        ui.set("#CratePickupItemName.TextSpans",
                Message.raw(this.itemDisplayName));

        if (this.itemQuantity > 1) {
            ui.set("#CratePickupQuantity.TextSpans",
                    Message.raw("x" + this.itemQuantity));
            ui.set("#CratePickupQuantity.Visible", true);
        } else {
            ui.set("#CratePickupQuantity.Visible", false);
        }

        ui.set("#CratePickupKeyHint.Visible", true);
        ui.set("#CratePickupCollectLabel.TextSpans",
                Message.raw("Collect"));
        ui.set("#CratePickupCollectLabel.Visible", true);
    }

    @Nonnull
    public String getItemDisplayName() {
        return itemDisplayName;
    }

    @Nullable
    public String getItemIconPath() {
        return itemIconPath;
    }

    public int getItemQuantity() {
        return itemQuantity;
    }
}
