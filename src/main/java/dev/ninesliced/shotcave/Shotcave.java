package dev.ninesliced.shotcave;

import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.shotcave.camera.TopCameraService;
import dev.ninesliced.shotcave.command.ShotcaveCommand;
import dev.ninesliced.shotcave.interactions.ChainLightningInteraction;
import dev.ninesliced.shotcave.interactions.GunValidationInteraction;
import dev.ninesliced.shotcave.interactions.HideAmmoHudInteraction;
import dev.ninesliced.shotcave.interactions.ModularGunShootInteraction;
import dev.ninesliced.shotcave.interactions.ReloadCheckInteraction;
import dev.ninesliced.shotcave.interactions.ReloadInteraction;
import dev.ninesliced.shotcave.interactions.UpdateAmmoHudInteraction;

import javax.annotation.Nonnull;

public class Shotcave extends JavaPlugin {

    private final TopCameraService cameraService = new TopCameraService();

    public Shotcave(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.getCodecRegistry(Interaction.CODEC)
            .register("ChainLightning", ChainLightningInteraction.class, ChainLightningInteraction.CODEC)
            .register("ModularGunShoot", ModularGunShootInteraction.class, ModularGunShootInteraction.CODEC)
            .register("GunValidate", GunValidationInteraction.class, GunValidationInteraction.CODEC)
            .register("ReloadCheck", ReloadCheckInteraction.class, ReloadCheckInteraction.CODEC)
            .register("Reload", ReloadInteraction.class, ReloadInteraction.CODEC)
            .register("UpdateAmmoHud", UpdateAmmoHudInteraction.class, UpdateAmmoHudInteraction.CODEC)
            .register("HideAmmoHud", HideAmmoHudInteraction.class, HideAmmoHudInteraction.CODEC);

        this.getEventRegistry().register(PlayerConnectEvent.class, this::onPlayerConnect);
        this.getCommandRegistry().registerCommand(new ShotcaveCommand(this));
    }

    private void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        cameraService.registerDisabledByDefault(playerRef);
    }

    public TopCameraService getCameraService() {
        return cameraService;
    }
}