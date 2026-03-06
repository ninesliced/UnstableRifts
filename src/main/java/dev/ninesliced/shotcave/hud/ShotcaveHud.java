package dev.ninesliced.shotcave.hud;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

public final class ShotcaveHud extends CustomUIHud {
    public static final String UI_PATH = "Hud/Shotcave/AmmoHud.ui";

    private final int ammo;
    private final int maxAmmo;

    public ShotcaveHud(@Nonnull PlayerRef playerRef, int ammo, int maxAmmo) {
        super(playerRef);
        this.ammo = Math.max(0, ammo);
        this.maxAmmo = Math.max(1, maxAmmo);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder ui) {
        ui.append(UI_PATH);

        ui.set("#ShotcaveAmmoRoot.Visible", true);
        ui.set("#ShotcaveAmmoValue.TextSpans", Message.raw(Integer.toString(this.ammo)));
        ui.set("#ShotcaveMaxAmmoValue.TextSpans", Message.raw(Integer.toString(this.maxAmmo)));
    }
}
