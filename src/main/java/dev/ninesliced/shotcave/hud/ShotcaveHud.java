package dev.ninesliced.shotcave.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class ShotcaveHud extends CustomUIHud {
    public static final String UI_PATH = "Hud/Shotcave/AmmoHud.ui";

    /**
     * Threshold below which ammo is considered "low" (fraction of max).
     */
    private static final double LOW_AMMO_THRESHOLD = 0.25;

    private static final String COLOR_BRIGHT = "#e8ecf0";
    private static final String COLOR_LOW = "#d4534a";
    private static final String BAR_NORMAL = "#7aa8d4";
    private static final String BAR_LOW = "#d4534a";

    private final int ammo;
    private final int maxAmmo;
    @Nullable
    private final String weaponName;

    public ShotcaveHud(@Nonnull PlayerRef playerRef, int ammo, int maxAmmo, @Nullable String weaponName) {
        super(playerRef);
        this.ammo = Math.max(0, ammo);
        this.maxAmmo = Math.max(1, maxAmmo);
        this.weaponName = weaponName;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);

        ui.set("#ShotcaveAmmoRoot.Visible", true);
        ui.set("#ShotcaveAmmoValue.TextSpans", Message.raw(Integer.toString(this.ammo)));
        ui.set("#ShotcaveMaxAmmoValue.TextSpans", Message.raw(Integer.toString(this.maxAmmo)));

        if (this.weaponName != null && !this.weaponName.isBlank()) {
            ui.set("#ShotcaveWeaponName.TextSpans", Message.raw(this.weaponName));
        }

        double ratio = (double) this.ammo / (double) this.maxAmmo;
        boolean isLow = ratio <= LOW_AMMO_THRESHOLD && this.ammo > 0;
        boolean isEmpty = this.ammo <= 0;

        int barWidth = 172;
        int fillRight = barWidth - (int) Math.round(ratio * barWidth);
        ui.set("#ShotcaveAmmoBarFill.Anchor.Right", fillRight);

        if (isEmpty || isLow) {
            String ammoColor = isEmpty ? COLOR_LOW : COLOR_LOW;
            String barColor = BAR_LOW;
            ui.set("#ShotcaveAmmoValue.Style.TextColor", ammoColor);
            ui.set("#ShotcaveAmmoBarFill.Background", barColor);
        }
    }
}
