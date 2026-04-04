package dev.ninesliced.unstablerifts;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.SwitchActiveSlotEvent;
import com.hypixel.hytale.server.core.event.events.player.*;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.armor.*;
import dev.ninesliced.unstablerifts.camera.TopCameraService;
import dev.ninesliced.unstablerifts.coin.CoinCollectionSystem;
import dev.ninesliced.unstablerifts.command.PartyCommand;
import dev.ninesliced.unstablerifts.command.UnstableRiftsCommand;
import dev.ninesliced.unstablerifts.crate.CrateBreakDropSystem;
import dev.ninesliced.unstablerifts.crate.DestructibleBlockConfig;
import dev.ninesliced.unstablerifts.dungeon.*;
import dev.ninesliced.unstablerifts.dungeon.map.DungeonMapService;
import dev.ninesliced.unstablerifts.guns.WeaponRegistry;
import dev.ninesliced.unstablerifts.hud.AmmoHudRuntime;
import dev.ninesliced.unstablerifts.hud.HudVisibilityService;
import dev.ninesliced.unstablerifts.hud.PortalPromptHudService;
import dev.ninesliced.unstablerifts.hud.RevivePromptHudRuntime;
import dev.ninesliced.unstablerifts.interactions.*;
import dev.ninesliced.unstablerifts.inventory.DropBlockSystem;
import dev.ninesliced.unstablerifts.inventory.InventoryLockService;
import dev.ninesliced.unstablerifts.inventory.SlotSwitchBlockSystem;
import dev.ninesliced.unstablerifts.party.PartyManager;
import dev.ninesliced.unstablerifts.party.UnstableRiftsPartyPageSupplier;
import dev.ninesliced.unstablerifts.pickup.*;
import dev.ninesliced.unstablerifts.systems.*;
import dev.ninesliced.unstablerifts.tooltip.ArmorTooltipAdapter;
import dev.ninesliced.unstablerifts.tooltip.ArmorVirtualItems;
import dev.ninesliced.unstablerifts.tooltip.WeaponTooltipAdapter;
import dev.ninesliced.unstablerifts.tooltip.WeaponVirtualItems;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public class UnstableRifts extends JavaPlugin {

    private static final Logger LOGGER = Logger.getLogger(UnstableRifts.class.getName());

    private static UnstableRifts instance;

    // Runtime UX services
    private final TopCameraService cameraService = new TopCameraService();
    private final AmmoHudRuntime ammoHudRuntime = new AmmoHudRuntime();
    private final ItemPickupHudRuntime itemPickupHudRuntime = new ItemPickupHudRuntime();
    private final RevivePromptHudRuntime revivePromptHudRuntime = new RevivePromptHudRuntime();

    // Core gameplay services
    private final InventoryLockService inventoryLockService = new InventoryLockService();
    private final DungeonInstanceService dungeonInstanceService = new DungeonInstanceService(this);
    private final PartyManager partyManager = new PartyManager(this);
    private final ArmorSetTracker armorSetTracker = new ArmorSetTracker();
    private final GameManager gameManager = new GameManager(this);
    private final DungeonMapService dungeonMapService = new DungeonMapService();
    private final DoorService doorService = new DoorService();
    private final PortalService portalService = new PortalService();
    private final PortalInteractionService portalInteractionService = new PortalInteractionService(this);

    private Path dungeonConfigPath;

    public UnstableRifts(@Nonnull JavaPluginInit init) {
        super(init);
    }

    public static UnstableRifts getInstance() {
        return instance;
    }

    public ArmorSetTracker getArmorSetTracker() {
        return armorSetTracker;
    }

    @Override
    protected void setup() {
        instance = this;
        this.dungeonConfigPath = DungeonConfig.ensureRuntimeConfig(this.getDataDirectory());
        DestructibleBlockConfig.load();

        registerPageCodecs();
        registerInteractions();
        RegisteredComponentTypes componentTypes = registerComponents();
        WeaponRegistry.registerAll();
        ArmorRegistry.registerAll();
        registerPacketHandlers();
        registerEventTypes();
        registerSystems(componentTypes);
        registerEventHandlers();
        registerCommands();
        startRuntimes();
    }

    @Override
    protected void shutdown() {
        this.itemPickupHudRuntime.stop();
        this.revivePromptHudRuntime.stop();
        this.ammoHudRuntime.stop();
        this.portalInteractionService.clearAll();
        PortalPromptHudService.clearAll();
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
        portalInteractionService.clearPlayer(playerRef.getUuid());
        PortalPromptHudService.clear(playerRef);
        gameManager.onPlayerConnect(playerRef);
        partyManager.reconnectMember(playerRef);
    }

    private void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        Player player = event.getPlayer();
        PlayerRef playerRef = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        this.cameraService.handlePlayerReady(ref);
        this.gameManager.handlePostReadyResync(playerRef, player);
        this.gameManager.handlePlayerReconnect(playerRef);
        this.gameManager.releasePendingRecovery(playerRef.getUuid());

        World world = player.getWorld();
        if (world == null) {
            return;
        }

        var game = this.gameManager.findGameForPlayer(playerRef.getUuid());
        if (game == null || game.getInstanceWorld() != world) {
            this.gameManager.normalizeOutsideDungeonState(playerRef, player, game);
        }
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

    private void registerPageCodecs() {
        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC)
                .register("UnstableRiftsPartyPortal", UnstableRiftsPartyPageSupplier.class, UnstableRiftsPartyPageSupplier.CODEC)
                .register("UnstableRiftsDoorConfig", DoorConfigPageSupplier.class, DoorConfigPageSupplier.CODEC)
                .register("UnstableRiftsPortalConfig", PortalConfigPageSupplier.class, PortalConfigPageSupplier.CODEC)
                .register("UnstableRiftsMobSpawnerConfig", MobSpawnerConfigPageSupplier.class, MobSpawnerConfigPageSupplier.CODEC)
                .register("UnstableRiftsRoomConfig", RoomConfigPageSupplier.class, RoomConfigPageSupplier.CODEC);
    }

    private void registerInteractions() {
        this.getCodecRegistry(Interaction.CODEC)
                .register("ChainLightning", ChainLightningInteraction.class, ChainLightningInteraction.CODEC)
                .register("ContinuousBeam", ContinuousBeamInteraction.class, ContinuousBeamInteraction.CODEC)
                .register("ModularGunShoot", ModularGunShootInteraction.class, ModularGunShootInteraction.CODEC)
                .register("GunValidate", GunValidationInteraction.class, GunValidationInteraction.CODEC)
                .register("Reload", ReloadInteraction.class, ReloadInteraction.CODEC)
                .register("UpdateAmmoHud", UpdateAmmoHudInteraction.class, UpdateAmmoHudInteraction.CODEC)
                .register("HideAmmoHud", HideAmmoHudInteraction.class, HideAmmoHudInteraction.CODEC)
                .register("ConsumeAmmo", ConsumeAmmoInteraction.class, ConsumeAmmoInteraction.CODEC)
                .register("SpawnNPCAtImpact", SpawnNPCAtImpactInteraction.class, SpawnNPCAtImpactInteraction.CODEC)
                .register("BreakSoftBlock", BreakSoftBlockInteraction.class, BreakSoftBlockInteraction.CODEC)
                .register("CratePickup", ItemPickupInteraction.class, ItemPickupInteraction.CODEC)
                .register("ArmorAbility", ArmorAbilityInteraction.class, ArmorAbilityInteraction.CODEC);

        Interaction.getAssetStore().loadAssets(
                "ninesliced:UnstableRifts",
                List.of(new ItemPickupInteraction(ItemPickupConfig.ITEM_PICKUP_INTERACTION_ID)));
        RootInteraction.getAssetStore().loadAssets(
                "ninesliced:UnstableRifts",
                List.of(ItemPickupInteraction.DEFAULT_ROOT));
    }

    @Nonnull
    private RegisteredComponentTypes registerComponents() {
        ComponentType<ChunkStore, MobSpawnerData> mobSpawnerDataType =
                this.getChunkStoreRegistry().registerComponent(MobSpawnerData.class, "MobSpawnerData", MobSpawnerData.CODEC);
        MobSpawnerData.setComponentType(mobSpawnerDataType);

        ComponentType<ChunkStore, DoorData> doorDataType =
                this.getChunkStoreRegistry().registerComponent(DoorData.class, "DoorData", DoorData.CODEC);
        DoorData.setComponentType(doorDataType);

        ComponentType<ChunkStore, PortalData> portalDataType =
                this.getChunkStoreRegistry().registerComponent(PortalData.class, "PortalData", PortalData.CODEC);
        PortalData.setComponentType(portalDataType);

        ComponentType<ChunkStore, RoomConfigData> roomConfigDataType =
                this.getChunkStoreRegistry().registerComponent(RoomConfigData.class, "RoomConfigData", RoomConfigData.CODEC);
        RoomConfigData.setComponentType(roomConfigDataType);

        ComponentType<EntityStore, PlayerRef> playerRefComponentType = PlayerRef.getComponentType();

        ComponentType<EntityStore, DeathComponent> deathComponentType =
                this.getEntityStoreRegistry().registerComponent(DeathComponent.class, DeathComponent::new);
        DeathComponent.setComponentType(deathComponentType);

        ComponentType<EntityStore, RollComponent> rollComponentType =
                this.getEntityStoreRegistry().registerComponent(RollComponent.class, RollComponent::new);
        RollComponent.setComponentType(rollComponentType);

        ComponentType<EntityStore, DamageEffectComponent> damageEffectComponentType =
                this.getEntityStoreRegistry().registerComponent(DamageEffectComponent.class, DamageEffectComponent::new);
        DamageEffectComponent.setComponentType(damageEffectComponentType);

        ComponentType<EntityStore, SummonedEffectComponent> summonedEffectComponentType =
                this.getEntityStoreRegistry().registerComponent(SummonedEffectComponent.class, SummonedEffectComponent::new);
        SummonedEffectComponent.setComponentType(summonedEffectComponentType);

        ComponentType<EntityStore, ArmorChargeComponent> armorChargeComponentType =
                this.getEntityStoreRegistry().registerComponent(ArmorChargeComponent.class, ArmorChargeComponent::new);
        ArmorChargeComponent.setComponentType(armorChargeComponentType);

        return new RegisteredComponentTypes(playerRefComponentType, deathComponentType, rollComponentType, armorChargeComponentType);
    }

    private void registerPacketHandlers() {
        PacketAdapters.registerInbound(new FKeyPickupPacketHandler());
        PacketAdapters.registerInbound(new ReviveInteractionPacketHandler());
        PacketAdapters.registerInbound(new ArmorAbilityPacketHandler());

        WeaponTooltipAdapter tooltipAdapter = new WeaponTooltipAdapter();
        PacketAdapters.registerOutbound(tooltipAdapter);
        PacketAdapters.registerInbound(tooltipAdapter);

        ArmorTooltipAdapter armorTooltipAdapter = new ArmorTooltipAdapter();
        PacketAdapters.registerOutbound(armorTooltipAdapter);
        PacketAdapters.registerInbound(armorTooltipAdapter);
    }

    private void registerEventTypes() {
        try {
            this.getEntityStoreRegistry().registerEntityEventType(SwitchActiveSlotEvent.class);
        } catch (IllegalArgumentException e) {
            // Already registered by another plugin
        }
        try {
            this.getEntityStoreRegistry().registerEntityEventType(BreakBlockEvent.class);
        } catch (IllegalArgumentException e) {
            // Already registered by another plugin
        }
        try {
            this.getEntityStoreRegistry().registerEntityEventType(DropItemEvent.PlayerRequest.class);
        } catch (IllegalArgumentException e) {
            // Already registered by another plugin
        }
    }

    private void registerSystems(@Nonnull RegisteredComponentTypes componentTypes) {
        this.getEntityStoreRegistry().registerSystem(new ActiveSlotHudUpdateSystem());
        this.getEntityStoreRegistry().registerSystem(new DungeonTickSystem());
        this.getEntityStoreRegistry().registerSystem(new PrefabSpawnTrackingSystem());
        this.getEntityStoreRegistry().registerSystem(new MobDeathTrackingSystem());
        this.getEntityStoreRegistry().registerSystem(new NPCScaleHolderSystem());

        this.getEntityStoreRegistry().registerSystem(
                new DeathPlayerAddedSystem(componentTypes.playerRefComponentType(), componentTypes.deathComponentType()));
        this.getEntityStoreRegistry().registerSystem(new CreativeFallDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new DungeonLethalDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new PlayerDeathSystem());
        this.getEntityStoreRegistry().registerSystem(new ReviveTickSystem());
        this.getEntityStoreRegistry().registerSystem(new DeathAggroSuppressionSystem());

        this.getEntityStoreRegistry().registerSystem(
                new RollPlayerAddedSystem(componentTypes.playerRefComponentType(), componentTypes.rollComponentType()));
        this.getEntityStoreRegistry().registerSystem(
                new RollSystem(this.cameraService, componentTypes.rollComponentType()));

        this.getEntityStoreRegistry().registerSystem(new DamageEffectTickSystem());
        this.getEntityStoreRegistry().registerSystem(new DamageEffectVisualCleanupSystem());
        this.getEntityStoreRegistry().registerSystem(new SummonedNPCDamageEffectSystem());
        this.getEntityStoreRegistry().registerSystem(new MeleeDamageEffectSystem());
        this.getEntityStoreRegistry().registerSystem(new ScaledNPCDamageSystem());
        this.getEntityStoreRegistry().registerSystem(new MeleeBlockBreakSystem());

        this.getEntityStoreRegistry().registerSystem(new DropBlockSystem(this.inventoryLockService));
        this.getEntityStoreRegistry().registerSystem(new SlotSwitchBlockSystem(this.inventoryLockService));
        this.getEntityStoreRegistry().registerSystem(new VoidSafetySystem(this.inventoryLockService));
        this.getEntityStoreRegistry().registerSystem(new ItemDropSystem());
        this.getEntityStoreRegistry().registerSystem(new CrateBreakDropSystem());
        this.getEntityStoreRegistry().registerSystem(new CoinCollectionSystem());
        this.getEntityStoreRegistry().registerSystem(new KeyItemCollectionSystem());

        this.getEntityStoreRegistry().registerSystem(new ArmorChargeSystem());
        this.getEntityStoreRegistry().registerSystem(
                new ArmorChargePlayerAddedSystem(componentTypes.playerRefComponentType(), componentTypes.armorChargeComponentType()));
    }

    private void registerEventHandlers() {
        this.getEventRegistry().register(PlayerConnectEvent.class, this::onPlayerConnect);
        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::onPlayerAddedToWorld);
        this.getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, this::onPlayerRemovedFromWorld);
        this.getEventRegistry().registerGlobal(RemoveWorldEvent.class, this::onWorldRemoved);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            this.gameManager.onPlayerDisconnect(event.getPlayerRef());
            this.partyManager.handleDisconnect(event.getPlayerRef());
            this.cameraService.clearState(event.getPlayerRef());
            this.portalInteractionService.clearPlayer(event.getPlayerRef().getUuid());
            PortalPromptHudService.clear(event.getPlayerRef());
            WeaponVirtualItems.onPlayerDisconnect(event.getPlayerRef().getUuid());
            ArmorVirtualItems.onPlayerDisconnect(event.getPlayerRef().getUuid());
            armorSetTracker.removePlayer(event.getPlayerRef().getUuid());
            HudVisibilityService.clear(event.getPlayerRef().getUuid());
        });
    }

    private void registerCommands() {
        this.getCommandRegistry().registerCommand(new UnstableRiftsCommand(this));
        this.getCommandRegistry().registerCommand(new PartyCommand(this));
    }

    private void startRuntimes() {
        this.ammoHudRuntime.start(this);
        this.itemPickupHudRuntime.start(this);
        this.revivePromptHudRuntime.start(this);
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

    @Nonnull
    public InventoryLockService getInventoryLockService() {
        return this.inventoryLockService;
    }

    @Nonnull
    public DungeonMapService getDungeonMapService() {
        return this.dungeonMapService;
    }

    @Nonnull
    public DoorService getDoorService() {
        return this.doorService;
    }

    @Nonnull
    public PortalService getPortalService() {
        return this.portalService;
    }

    @Nonnull
    public PortalInteractionService getPortalInteractionService() {
        return this.portalInteractionService;
    }

    @NonNullDecl
    @Override
    public Path getDataDirectory() {
        return super.getDataDirectory().getParent().resolve("UnstableRifts");
    }

    private record RegisteredComponentTypes(
            @Nonnull ComponentType<EntityStore, PlayerRef> playerRefComponentType,
            @Nonnull ComponentType<EntityStore, DeathComponent> deathComponentType,
            @Nonnull ComponentType<EntityStore, RollComponent> rollComponentType,
            @Nonnull ComponentType<EntityStore, ArmorChargeComponent> armorChargeComponentType) {
    }
}
