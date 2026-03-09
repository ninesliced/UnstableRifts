package dev.ninesliced.shotcave;

import com.hypixel.hytale.server.core.event.events.ecs.SwitchActiveSlotEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.shotcave.camera.TopCameraService;
import dev.ninesliced.shotcave.command.ShotcaveCommand;
import dev.ninesliced.shotcave.dungeon.DungeonConfig;
import dev.ninesliced.shotcave.hud.AmmoHudRuntime;
import dev.ninesliced.shotcave.interactions.ChainLightningInteraction;
import dev.ninesliced.shotcave.interactions.ConsumeAmmoInteraction;
import dev.ninesliced.shotcave.interactions.GunValidationInteraction;
import dev.ninesliced.shotcave.interactions.HideAmmoHudInteraction;
import dev.ninesliced.shotcave.interactions.ModularGunShootInteraction;
import dev.ninesliced.shotcave.interactions.ReloadCheckInteraction;
import dev.ninesliced.shotcave.interactions.ReloadInteraction;
import dev.ninesliced.shotcave.interactions.SpawnNPCAtImpactInteraction;
import dev.ninesliced.shotcave.interactions.UpdateAmmoHudInteraction;
import dev.ninesliced.shotcave.systems.ActiveSlotHudUpdateSystem;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nonnull;
import java.nio.file.Path;

public class Shotcave extends JavaPlugin {

    private final TopCameraService cameraService = new TopCameraService();
    private final AmmoHudRuntime ammoHudRuntime = new AmmoHudRuntime();
    private Path dungeonConfigPath;

    public Shotcave(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.dungeonConfigPath = DungeonConfig.ensureRuntimeConfig(this.getDataDirectory());

        this.getCodecRegistry(Interaction.CODEC)
                .register("ChainLightning", ChainLightningInteraction.class, ChainLightningInteraction.CODEC)
                .register("ModularGunShoot", ModularGunShootInteraction.class, ModularGunShootInteraction.CODEC)
                .register("GunValidate", GunValidationInteraction.class, GunValidationInteraction.CODEC)
                .register("ReloadCheck", ReloadCheckInteraction.class, ReloadCheckInteraction.CODEC)
                .register("Reload", ReloadInteraction.class, ReloadInteraction.CODEC)
                .register("UpdateAmmoHud", UpdateAmmoHudInteraction.class, UpdateAmmoHudInteraction.CODEC)
                .register("HideAmmoHud", HideAmmoHudInteraction.class, HideAmmoHudInteraction.CODEC)
                .register("ConsumeAmmo", ConsumeAmmoInteraction.class, ConsumeAmmoInteraction.CODEC)
                .register("SpawnNPCAtImpact", SpawnNPCAtImpactInteraction.class, SpawnNPCAtImpactInteraction.CODEC);

        try {
            this.getEntityStoreRegistry().registerEntityEventType(SwitchActiveSlotEvent.class);
        } catch (IllegalArgumentException ignored) {
        }
        this.getEntityStoreRegistry().registerSystem(new ActiveSlotHudUpdateSystem());

        this.getEventRegistry().register(PlayerConnectEvent.class, this::onPlayerConnect);
        this.getCommandRegistry().registerCommand(new ShotcaveCommand(this));

        this.ammoHudRuntime.start(this);
    }

    @Override
    protected void shutdown() {
        this.ammoHudRuntime.stop();
        super.shutdown();
    }

    private void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        cameraService.registerDisabledByDefault(playerRef);
        ammoHudRuntime.onPlayerConnect(playerRef);
    }

    @Nonnull
    public Path getDungeonConfigPath() {
        if (this.dungeonConfigPath == null) {
            this.dungeonConfigPath = DungeonConfig.ensureRuntimeConfig(this.getDataDirectory());
        }
        return this.dungeonConfigPath;
    }

    @Nonnull
    public DungeonConfig loadDungeonConfig() {
        return DungeonConfig.load(this.getDungeonConfigPath());
    }

    public TopCameraService getCameraService() {
        return cameraService;
    }

    @NonNullDecl
    @Override
    public Path getDataDirectory() {
        return super.getDataDirectory().getParent().resolve("Shotcave");
    }
}
