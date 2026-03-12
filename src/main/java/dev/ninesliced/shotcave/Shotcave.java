package dev.ninesliced.shotcave;

import com.hypixel.hytale.server.core.event.events.ecs.SwitchActiveSlotEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import dev.ninesliced.shotcave.camera.TopCameraService;
import dev.ninesliced.shotcave.command.PartyCommand;
import dev.ninesliced.shotcave.command.ShotcaveCommand;
import dev.ninesliced.shotcave.dungeon.DungeonConfig;
import dev.ninesliced.shotcave.dungeon.DungeonInstanceService;
import dev.ninesliced.shotcave.dungeon.GameManager;
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
import dev.ninesliced.shotcave.party.PartyManager;
import dev.ninesliced.shotcave.party.ShotcavePartyPageSupplier;
import dev.ninesliced.shotcave.systems.ActiveSlotHudUpdateSystem;
import dev.ninesliced.shotcave.systems.DungeonTickSystem;
import dev.ninesliced.shotcave.systems.PrefabSpawnTrackingSystem;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nonnull;
import java.nio.file.Path;

public class Shotcave extends JavaPlugin {

    private static Shotcave instance;

    private final TopCameraService cameraService = new TopCameraService();
    private final AmmoHudRuntime ammoHudRuntime = new AmmoHudRuntime();
    private final DungeonInstanceService dungeonInstanceService = new DungeonInstanceService(this);
    private final PartyManager partyManager = new PartyManager(this);
    private final GameManager gameManager = new GameManager(this);
    private Path dungeonConfigPath;

    public Shotcave(@Nonnull JavaPluginInit init) {
        super(init);
    }

    public static Shotcave getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        instance = this;
        this.dungeonConfigPath = DungeonConfig.ensureRuntimeConfig(this.getDataDirectory());

        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC)
                .register("ShotcavePartyPortal", ShotcavePartyPageSupplier.class, ShotcavePartyPageSupplier.CODEC);

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
        this.getEntityStoreRegistry().registerSystem(new DungeonTickSystem());
        this.getEntityStoreRegistry().registerSystem(new PrefabSpawnTrackingSystem());

        this.getEventRegistry().register(PlayerConnectEvent.class, this::onPlayerConnect);
        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::onPlayerAddedToWorld);
        this.getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, this::onPlayerRemovedFromWorld);
        this.getEventRegistry().registerGlobal(RemoveWorldEvent.class, this::onWorldRemoved);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            this.partyManager.handleDisconnect(event.getPlayerRef());
            this.cameraService.clearState(event.getPlayerRef());
            this.gameManager.onPlayerDisconnect(event.getPlayerRef().getUuid());
        });
        this.getCommandRegistry().registerCommand(new ShotcaveCommand(this));
        this.getCommandRegistry().registerCommand(new PartyCommand(this));

        this.ammoHudRuntime.start(this);
    }

    @Override
    protected void shutdown() {
        this.ammoHudRuntime.stop();
        this.gameManager.shutdown();
        instance = null;
        super.shutdown();
    }

    private void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        cameraService.registerDisabledByDefault(playerRef);
        ammoHudRuntime.onPlayerConnect(playerRef);
        gameManager.onPlayerConnect(playerRef);
    }

    private void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        this.cameraService.handlePlayerReady(event.getPlayerRef());
    }

    private void onPlayerAddedToWorld(@Nonnull AddPlayerToWorldEvent event) {
        var holder = event.getHolder();
        PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
        Player player = holder.getComponent(Player.getComponentType());
        if (playerRef == null || player == null) {
            return;
        }
        this.gameManager.onPlayerAddedToWorld(playerRef, player, event.getWorld());
    }

    private void onPlayerRemovedFromWorld(@Nonnull RemovedPlayerFromWorldEvent event) {
        var holder = event.getHolder();
        PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
        Player player = holder.getComponent(Player.getComponentType());
        if (playerRef == null || player == null) {
            return;
        }
        this.gameManager.onPlayerRemovedFromWorld(playerRef, player, event.getWorld());
    }

    private void onWorldRemoved(@Nonnull RemoveWorldEvent event) {
        this.gameManager.onInstanceWorldRemoved(event.getWorld());
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

    @Nonnull
    public DungeonInstanceService getDungeonInstanceService() {
        return this.dungeonInstanceService;
    }

    public TopCameraService getCameraService() {
        return cameraService;
    }

    @Nonnull
    public PartyManager getPartyManager() {
        return this.partyManager;
    }

    @Nonnull
    public GameManager getGameManager() {
        return this.gameManager;
    }

    @NonNullDecl
    @Override
    public Path getDataDirectory() {
        return super.getDataDirectory().getParent().resolve("Shotcave");
    }
}
