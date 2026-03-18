package dev.ninesliced.shotcave;

import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.SwitchActiveSlotEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;

import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
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
import dev.ninesliced.shotcave.coin.CoinCollectionSystem;
import dev.ninesliced.shotcave.command.PartyCommand;
import dev.ninesliced.shotcave.command.ShotcaveCommand;
import dev.ninesliced.shotcave.crate.CrateBreakDropSystem;
import dev.ninesliced.shotcave.dungeon.DungeonConfig;
import dev.ninesliced.shotcave.dungeon.DungeonInstanceService;
import dev.ninesliced.shotcave.dungeon.GameManager;
import dev.ninesliced.shotcave.guns.WeaponRegistry;
import dev.ninesliced.shotcave.hud.AmmoHudRuntime;
import dev.ninesliced.shotcave.interactions.BreakSoftBlockInteraction;
import dev.ninesliced.shotcave.interactions.ChainLightningInteraction;
import dev.ninesliced.shotcave.interactions.ConsumeAmmoInteraction;
import dev.ninesliced.shotcave.interactions.GunValidationInteraction;
import dev.ninesliced.shotcave.interactions.HideAmmoHudInteraction;
import dev.ninesliced.shotcave.interactions.ModularGunShootInteraction;

import dev.ninesliced.shotcave.interactions.ReloadInteraction;
import dev.ninesliced.shotcave.interactions.SpawnNPCAtImpactInteraction;
import dev.ninesliced.shotcave.interactions.UpdateAmmoHudInteraction;
import dev.ninesliced.shotcave.pickup.FKeyPickupPacketHandler;
import dev.ninesliced.shotcave.pickup.ItemDropSystem;
import dev.ninesliced.shotcave.pickup.ItemPickupConfig;
import dev.ninesliced.shotcave.pickup.ItemPickupHudRuntime;
import dev.ninesliced.shotcave.pickup.ItemPickupInteraction;
import dev.ninesliced.shotcave.tooltip.WeaponTooltipAdapter;
import dev.ninesliced.shotcave.tooltip.WeaponVirtualItems;
import dev.ninesliced.shotcave.party.PartyManager;
import dev.ninesliced.shotcave.party.ShotcavePartyPageSupplier;
import dev.ninesliced.shotcave.systems.ActiveSlotHudUpdateSystem;
import dev.ninesliced.shotcave.systems.DungeonTickSystem;
import dev.ninesliced.shotcave.systems.PrefabSpawnTrackingSystem;
import dev.ninesliced.shotcave.systems.DashComponent;
import dev.ninesliced.shotcave.systems.DashPlayerAddedSystem;
import dev.ninesliced.shotcave.systems.DashRollSystem;
import dev.ninesliced.shotcave.systems.DashTrailSystem;
import dev.ninesliced.shotcave.systems.DeathComponent;
import dev.ninesliced.shotcave.systems.DamageEffectComponent;
import dev.ninesliced.shotcave.systems.DamageEffectTickSystem;
import dev.ninesliced.shotcave.systems.DamageEffectVisualCleanupSystem;
import dev.ninesliced.shotcave.systems.SummonedEffectComponent;
import dev.ninesliced.shotcave.systems.SummonedNPCDamageEffectSystem;
import dev.ninesliced.shotcave.systems.DeathAggroSuppressionSystem;
import dev.ninesliced.shotcave.systems.DeathPlayerAddedSystem;
import dev.ninesliced.shotcave.systems.DungeonLethalDamageSystem;
import dev.ninesliced.shotcave.systems.PlayerDeathSystem;
import dev.ninesliced.shotcave.systems.ReviveInteractionPacketHandler;
import dev.ninesliced.shotcave.systems.RevivePromptHudRuntime;
import dev.ninesliced.shotcave.systems.ReviveTickSystem;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.List;

public class Shotcave extends JavaPlugin {

    private static Shotcave instance;

    private final TopCameraService cameraService = new TopCameraService();
    private final AmmoHudRuntime ammoHudRuntime = new AmmoHudRuntime();
    private final ItemPickupHudRuntime itemPickupHudRuntime = new ItemPickupHudRuntime();
    private final RevivePromptHudRuntime revivePromptHudRuntime = new RevivePromptHudRuntime();
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
                .register("Reload", ReloadInteraction.class, ReloadInteraction.CODEC)
                .register("UpdateAmmoHud", UpdateAmmoHudInteraction.class, UpdateAmmoHudInteraction.CODEC)
                .register("HideAmmoHud", HideAmmoHudInteraction.class, HideAmmoHudInteraction.CODEC)
                .register("ConsumeAmmo", ConsumeAmmoInteraction.class, ConsumeAmmoInteraction.CODEC)
                .register("SpawnNPCAtImpact", SpawnNPCAtImpactInteraction.class, SpawnNPCAtImpactInteraction.CODEC)
                .register("BreakSoftBlock", BreakSoftBlockInteraction.class, BreakSoftBlockInteraction.CODEC)
                .register("CratePickup", ItemPickupInteraction.class, ItemPickupInteraction.CODEC);

        Interaction.getAssetStore().loadAssets(
                "ninesliced:Shotcave",
                List.of(new ItemPickupInteraction(ItemPickupConfig.ITEM_PICKUP_INTERACTION_ID)));
        RootInteraction.getAssetStore().loadAssets(
                "ninesliced:Shotcave",
                List.of(ItemPickupInteraction.DEFAULT_ROOT));

        WeaponRegistry.registerAll();

        PacketAdapters.registerInbound(new FKeyPickupPacketHandler());
        PacketAdapters.registerInbound(new ReviveInteractionPacketHandler());

        // Weapon tooltip adapter — rewrites inventory items to virtual
        // IDs with per-instance name/description/quality, and translates
        // inbound interaction packets back to real IDs.
        WeaponTooltipAdapter tooltipAdapter = new WeaponTooltipAdapter();
        PacketAdapters.registerOutbound(tooltipAdapter);
        PacketAdapters.registerInbound(tooltipAdapter);

        try {
            this.getEntityStoreRegistry().registerEntityEventType(SwitchActiveSlotEvent.class);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            this.getEntityStoreRegistry().registerEntityEventType(BreakBlockEvent.class);
        } catch (IllegalArgumentException ignored) {
        }

        this.getEntityStoreRegistry().registerSystem(new ActiveSlotHudUpdateSystem());
        this.getEntityStoreRegistry().registerSystem(new DungeonTickSystem());
        this.getEntityStoreRegistry().registerSystem(new PrefabSpawnTrackingSystem());

        // Dash system — register component, then the three systems that use it.
        ComponentType<EntityStore, DashComponent> dashComponentType =
                this.getEntityStoreRegistry().registerComponent(DashComponent.class, DashComponent::new);
        DashComponent.setComponentType(dashComponentType);

        ComponentType<EntityStore, PlayerRef> playerRefComponentType = PlayerRef.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformComponentType = TransformComponent.getComponentType();

        this.getEntityStoreRegistry().registerSystem(
                new DashPlayerAddedSystem(playerRefComponentType, dashComponentType));
        this.getEntityStoreRegistry().registerSystem(
                new DashRollSystem(this.cameraService, dashComponentType));
        this.getEntityStoreRegistry().registerSystem(
                new DashTrailSystem(dashComponentType, transformComponentType, playerRefComponentType));

        // Death system — register component, then the systems that use it.
        ComponentType<EntityStore, DeathComponent> deathComponentType =
                this.getEntityStoreRegistry().registerComponent(DeathComponent.class, DeathComponent::new);
        DeathComponent.setComponentType(deathComponentType);

        this.getEntityStoreRegistry().registerSystem(
                new DeathPlayerAddedSystem(playerRefComponentType, deathComponentType));
        this.getEntityStoreRegistry().registerSystem(new DungeonLethalDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new PlayerDeathSystem());
        this.getEntityStoreRegistry().registerSystem(new ReviveTickSystem());
        this.getEntityStoreRegistry().registerSystem(new DeathAggroSuppressionSystem());

        // Damage effect DoT system — register component, then system.
        ComponentType<EntityStore, DamageEffectComponent> damageEffectComponentType =
                this.getEntityStoreRegistry().registerComponent(DamageEffectComponent.class, DamageEffectComponent::new);
        DamageEffectComponent.setComponentType(damageEffectComponentType);

        this.getEntityStoreRegistry().registerSystem(new DamageEffectTickSystem());
        this.getEntityStoreRegistry().registerSystem(new DamageEffectVisualCleanupSystem());

        // Summoned NPC effect system — marks summoned NPCs with weapon effect, applies DoT on their hits
        ComponentType<EntityStore, SummonedEffectComponent> summonedEffectComponentType =
                this.getEntityStoreRegistry().registerComponent(SummonedEffectComponent.class, SummonedEffectComponent::new);
        SummonedEffectComponent.setComponentType(summonedEffectComponentType);

        this.getEntityStoreRegistry().registerSystem(new SummonedNPCDamageEffectSystem());

        // Item pickup: intercept item entity spawns to apply F-key / score-collect
        // behaviour.
        this.getEntityStoreRegistry().registerSystem(new ItemDropSystem());
        this.getEntityStoreRegistry().registerSystem(new CrateBreakDropSystem());
        this.getEntityStoreRegistry().registerSystem(new CoinCollectionSystem());

        this.getEventRegistry().register(PlayerConnectEvent.class, this::onPlayerConnect);
        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::onPlayerAddedToWorld);
        this.getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, this::onPlayerRemovedFromWorld);
        this.getEventRegistry().registerGlobal(RemoveWorldEvent.class, this::onWorldRemoved);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            this.partyManager.handleDisconnect(event.getPlayerRef());
            this.cameraService.clearState(event.getPlayerRef());
            this.gameManager.onPlayerDisconnect(event.getPlayerRef());
            WeaponVirtualItems.onPlayerDisconnect(event.getPlayerRef().getUuid());
        });
        this.getCommandRegistry().registerCommand(new ShotcaveCommand(this));
        this.getCommandRegistry().registerCommand(new PartyCommand(this));

        this.ammoHudRuntime.start(this);
        this.itemPickupHudRuntime.start(this);
        this.revivePromptHudRuntime.start(this);
    }

    @Override
    protected void shutdown() {
        this.itemPickupHudRuntime.stop();
        this.revivePromptHudRuntime.stop();
        this.ammoHudRuntime.stop();
        this.gameManager.shutdown();
        instance = null;
        super.shutdown();
    }

    private void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        cameraService.registerDisabledByDefault(playerRef);
        ammoHudRuntime.onPlayerConnect(playerRef);
        itemPickupHudRuntime.onPlayerConnect(playerRef);
        revivePromptHudRuntime.onPlayerConnect(playerRef);
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
