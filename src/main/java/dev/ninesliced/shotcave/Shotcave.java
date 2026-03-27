package dev.ninesliced.shotcave;

import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
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
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.component.Ref;
import dev.ninesliced.shotcave.camera.TopCameraService;
import dev.ninesliced.shotcave.coin.CoinCollectionSystem;
import dev.ninesliced.shotcave.command.PartyCommand;
import dev.ninesliced.shotcave.command.ShotcaveCommand;
import dev.ninesliced.shotcave.crate.CrateBreakDropSystem;
import dev.ninesliced.shotcave.dungeon.DoorConfigPageSupplier;
import dev.ninesliced.shotcave.dungeon.DoorService;
import dev.ninesliced.shotcave.crate.DestructibleBlockConfig;
import dev.ninesliced.shotcave.dungeon.DungeonConfig;
import dev.ninesliced.shotcave.dungeon.DungeonInstanceService;
import dev.ninesliced.shotcave.dungeon.GameManager;
import dev.ninesliced.shotcave.dungeon.MobSpawnerConfigPageSupplier;
import dev.ninesliced.shotcave.dungeon.MobSpawnerData;
import dev.ninesliced.shotcave.dungeon.PortalService;
import dev.ninesliced.shotcave.dungeon.map.DungeonMapService;
import dev.ninesliced.shotcave.inventory.InventoryLockService;
import dev.ninesliced.shotcave.inventory.DropBlockSystem;
import dev.ninesliced.shotcave.inventory.SlotSwitchBlockSystem;
import dev.ninesliced.shotcave.guns.WeaponRegistry;
import dev.ninesliced.shotcave.hud.AmmoHudRuntime;
import dev.ninesliced.shotcave.interactions.BreakSoftBlockInteraction;
import dev.ninesliced.shotcave.interactions.ChainLightningInteraction;
import dev.ninesliced.shotcave.interactions.ContinuousBeamInteraction;
import dev.ninesliced.shotcave.interactions.ConsumeAmmoInteraction;
import dev.ninesliced.shotcave.interactions.GunValidationInteraction;
import dev.ninesliced.shotcave.interactions.HideAmmoHudInteraction;
import dev.ninesliced.shotcave.interactions.ModularGunShootInteraction;

import dev.ninesliced.shotcave.interactions.ReloadInteraction;
import dev.ninesliced.shotcave.interactions.SpawnNPCAtImpactInteraction;
import dev.ninesliced.shotcave.interactions.UpdateAmmoHudInteraction;
import dev.ninesliced.shotcave.armor.ArmorAbilityPacketHandler;
import dev.ninesliced.shotcave.pickup.FKeyPickupPacketHandler;
import dev.ninesliced.shotcave.pickup.ItemDropSystem;
import dev.ninesliced.shotcave.pickup.ItemPickupConfig;
import dev.ninesliced.shotcave.pickup.ItemPickupHudRuntime;
import dev.ninesliced.shotcave.pickup.ItemPickupInteraction;
import dev.ninesliced.shotcave.pickup.KeyItemCollectionSystem;
import dev.ninesliced.shotcave.armor.ArmorAbilityInteraction;
import dev.ninesliced.shotcave.armor.ArmorChargeComponent;
import dev.ninesliced.shotcave.armor.ArmorChargePlayerAddedSystem;
import dev.ninesliced.shotcave.armor.ArmorChargeSystem;
import dev.ninesliced.shotcave.armor.ArmorRegistry;
import dev.ninesliced.shotcave.armor.ArmorSetTracker;
import dev.ninesliced.shotcave.tooltip.ArmorTooltipAdapter;
import dev.ninesliced.shotcave.tooltip.ArmorVirtualItems;
import dev.ninesliced.shotcave.tooltip.WeaponTooltipAdapter;
import dev.ninesliced.shotcave.tooltip.WeaponVirtualItems;
import dev.ninesliced.shotcave.party.PartyManager;
import dev.ninesliced.shotcave.party.ShotcavePartyPageSupplier;
import dev.ninesliced.shotcave.systems.ActiveSlotHudUpdateSystem;
import dev.ninesliced.shotcave.systems.DungeonTickSystem;
import dev.ninesliced.shotcave.systems.PrefabSpawnTrackingSystem;
import dev.ninesliced.shotcave.systems.DeathComponent;
import dev.ninesliced.shotcave.systems.DamageEffectComponent;
import dev.ninesliced.shotcave.systems.DamageEffectTickSystem;
import dev.ninesliced.shotcave.systems.DamageEffectVisualCleanupSystem;
import dev.ninesliced.shotcave.systems.SummonedEffectComponent;
import dev.ninesliced.shotcave.systems.MeleeBlockBreakSystem;
import dev.ninesliced.shotcave.systems.MeleeDamageEffectSystem;
import dev.ninesliced.shotcave.systems.SummonedNPCDamageEffectSystem;
import dev.ninesliced.shotcave.systems.VoidSafetySystem;
import dev.ninesliced.shotcave.systems.DeathAggroSuppressionSystem;
import dev.ninesliced.shotcave.systems.DeathPlayerAddedSystem;
import dev.ninesliced.shotcave.systems.DungeonLethalDamageSystem;
import dev.ninesliced.shotcave.systems.PlayerDeathSystem;
import dev.ninesliced.shotcave.systems.ReviveInteractionPacketHandler;
import dev.ninesliced.shotcave.systems.RevivePromptHudRuntime;
import dev.ninesliced.shotcave.systems.ReviveTickSystem;
import dev.ninesliced.shotcave.systems.RollComponent;
import dev.ninesliced.shotcave.systems.RollPlayerAddedSystem;
import dev.ninesliced.shotcave.systems.RollSystem;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
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
    private final InventoryLockService inventoryLockService = new InventoryLockService();
    private final DungeonInstanceService dungeonInstanceService = new DungeonInstanceService(this);
    private final PartyManager partyManager = new PartyManager(this);
    private final ArmorSetTracker armorSetTracker = new ArmorSetTracker();
    private final GameManager gameManager = new GameManager(this);
    private final DungeonMapService dungeonMapService = new DungeonMapService();
    private final DoorService doorService = new DoorService();
    private final PortalService portalService = new PortalService();
    private Path dungeonConfigPath;

    public Shotcave(@Nonnull JavaPluginInit init) {
        super(init);
    }

    public static Shotcave getInstance() {
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

        this.getCodecRegistry(OpenCustomUIInteraction.PAGE_CODEC)
                .register("ShotcavePartyPortal", ShotcavePartyPageSupplier.class, ShotcavePartyPageSupplier.CODEC)
                .register("ShotcaveDoorConfig", DoorConfigPageSupplier.class, DoorConfigPageSupplier.CODEC)
                .register("ShotcaveMobSpawnerConfig", MobSpawnerConfigPageSupplier.class, MobSpawnerConfigPageSupplier.CODEC);

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
                "ninesliced:Shotcave",
                List.of(new ItemPickupInteraction(ItemPickupConfig.ITEM_PICKUP_INTERACTION_ID)));
        RootInteraction.getAssetStore().loadAssets(
                "ninesliced:Shotcave",
                List.of(ItemPickupInteraction.DEFAULT_ROOT));

        // Mob spawner block component — persists spawner config in prefabs
        ComponentType<ChunkStore, MobSpawnerData> mobSpawnerDataType =
                this.getChunkStoreRegistry().registerComponent(MobSpawnerData.class, "MobSpawnerData", MobSpawnerData.CODEC);
        MobSpawnerData.setComponentType(mobSpawnerDataType);

        WeaponRegistry.registerAll();
        ArmorRegistry.registerAll();

        PacketAdapters.registerInbound(new FKeyPickupPacketHandler());
        PacketAdapters.registerInbound(new ReviveInteractionPacketHandler());
        PacketAdapters.registerInbound(new ArmorAbilityPacketHandler());

        // Weapon tooltip adapter — rewrites inventory items to virtual
        // IDs with per-instance name/description/quality, and translates
        // inbound interaction packets back to real IDs.
        WeaponTooltipAdapter tooltipAdapter = new WeaponTooltipAdapter();
        PacketAdapters.registerOutbound(tooltipAdapter);
        PacketAdapters.registerInbound(tooltipAdapter);

        // Armor tooltip adapter — same pattern for armor inventory slots.
        ArmorTooltipAdapter armorTooltipAdapter = new ArmorTooltipAdapter();
        PacketAdapters.registerOutbound(armorTooltipAdapter);
        PacketAdapters.registerInbound(armorTooltipAdapter);

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

        this.getEntityStoreRegistry().registerSystem(new ActiveSlotHudUpdateSystem());
        this.getEntityStoreRegistry().registerSystem(new DungeonTickSystem());
        this.getEntityStoreRegistry().registerSystem(new PrefabSpawnTrackingSystem());

        ComponentType<EntityStore, PlayerRef> playerRefComponentType = PlayerRef.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformComponentType = TransformComponent.getComponentType();

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

        // Roll system — jump key becomes an invulnerable roll in dungeon mode.
        ComponentType<EntityStore, RollComponent> rollComponentType =
                this.getEntityStoreRegistry().registerComponent(RollComponent.class, RollComponent::new);
        RollComponent.setComponentType(rollComponentType);

        this.getEntityStoreRegistry().registerSystem(
                new RollPlayerAddedSystem(playerRefComponentType, rollComponentType));
        this.getEntityStoreRegistry().registerSystem(
                new RollSystem(this.cameraService, rollComponentType));

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

        // Toxic gas zone — barrel explosions spawn a deployable AOE (initialized above)

        // Melee weapon effect/modifier system — applies SC_Effect DoT and WEAPON_DAMAGE scaling
        this.getEntityStoreRegistry().registerSystem(new MeleeDamageEffectSystem());

        // Ensure destructible blocks (crates, barrels) break on melee hit
        this.getEntityStoreRegistry().registerSystem(new MeleeBlockBreakSystem());

        // Inventory lock: block drops and slot switches beyond slot 2 when locked
        this.getEntityStoreRegistry().registerSystem(new DropBlockSystem(this.inventoryLockService));
        this.getEntityStoreRegistry().registerSystem(new SlotSwitchBlockSystem(this.inventoryLockService));
        this.getEntityStoreRegistry().registerSystem(new VoidSafetySystem(this.inventoryLockService));
        // Item pickup: intercept item entity spawns to apply F-key / score-collect
        // behaviour.
        this.getEntityStoreRegistry().registerSystem(new ItemDropSystem());
        this.getEntityStoreRegistry().registerSystem(new CrateBreakDropSystem());
        this.getEntityStoreRegistry().registerSystem(new CoinCollectionSystem());
        this.getEntityStoreRegistry().registerSystem(new KeyItemCollectionSystem());

        // Armor charge system — passive 30s charge for armor set abilities.
        ComponentType<EntityStore, ArmorChargeComponent> armorChargeComponentType =
                this.getEntityStoreRegistry().registerComponent(ArmorChargeComponent.class, ArmorChargeComponent::new);
        ArmorChargeComponent.setComponentType(armorChargeComponentType);

        this.getEntityStoreRegistry().registerSystem(new ArmorChargeSystem());
        this.getEntityStoreRegistry().registerSystem(
                new ArmorChargePlayerAddedSystem(playerRefComponentType, armorChargeComponentType));

        try {
            this.getEntityStoreRegistry().registerEntityEventType(DropItemEvent.PlayerRequest.class);
        } catch (IllegalArgumentException e) {
            // Already registered by another plugin
        }

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
            ArmorVirtualItems.onPlayerDisconnect(event.getPlayerRef().getUuid());
            armorSetTracker.removePlayer(event.getPlayerRef().getUuid());
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
        Ref<EntityStore> ref = event.getPlayerRef();
        Player player = event.getPlayer();
        PlayerRef playerRef = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        this.cameraService.handlePlayerReady(ref);

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

    @NonNullDecl
    @Override
    public Path getDataDirectory() {
        return super.getDataDirectory().getParent().resolve("Shotcave");
    }
}
