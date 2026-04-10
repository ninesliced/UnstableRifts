package dev.ninesliced.unstablerifts.pickup;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.armor.ArmorDefinition;
import dev.ninesliced.unstablerifts.armor.ArmorDefinitions;
import dev.ninesliced.unstablerifts.armor.ArmorSetTracker;
import dev.ninesliced.unstablerifts.armor.ArmorStatResolver;
import dev.ninesliced.unstablerifts.dungeon.*;
import dev.ninesliced.unstablerifts.guns.GunItemMetadata;
import dev.ninesliced.unstablerifts.guns.WeaponDefinition;
import dev.ninesliced.unstablerifts.guns.WeaponDefinitions;
import dev.ninesliced.unstablerifts.hud.AmmoHudService;
import dev.ninesliced.unstablerifts.inventory.InventoryLockService;
import dev.ninesliced.unstablerifts.network.UnstableRiftsPacketIds;
import dev.ninesliced.unstablerifts.shop.ShopPage;
import dev.ninesliced.unstablerifts.shop.ShopService;
import dev.ninesliced.unstablerifts.systems.DeathComponent;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Observes {@link SyncInteractionChains} packets for F-key presses and routes
 * them to doors, portals, and finally item pickup on the world thread.
 */
public final class FKeyPickupPacketHandler implements PlayerPacketWatcher {

    public FKeyPickupPacketHandler() {
    }

    /**
     * Handles the interaction key near supported dungeon/gameplay targets.
     */
    private static void attemptPickup(@Nonnull PlayerRef playerRef,
                                      @Nonnull Ref<EntityStore> playerEntityRef) {
        if (!playerEntityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();

        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null || player.wasRemoved()) {
            return;
        }

        if (ItemPickupConfig.isPickupDelayed(playerRef.getUuid())) {
            return;
        }

        DeathComponent death = store.getComponent(playerEntityRef, DeathComponent.getComponentType());
        if (death != null && death.isDead()) {
            return;
        }

        TransformComponent playerTransform = store.getComponent(
                playerEntityRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            return;
        }

        Vector3d playerPos = playerTransform.getPosition();

        // Portals now require explicit confirmation with the interaction key.
        if (tryUsePortal(playerRef, playerPos)) {
            return;
        }

        if (tryUnlockKeyDoor(playerRef, playerPos)) {
            return;
        }

        if (ItemPickupTracker.size() == 0) {
            // Open shop if near a shopkeeper and there is no pickup target competing for F.
            if (tryOpenShop(playerRef, playerEntityRef, store, playerPos)) {
                return;
            }
            return;
        }

        double pickupRadiusSq = ItemPickupConfig.ITEM_PICKUP_RADIUS
                * ItemPickupConfig.ITEM_PICKUP_RADIUS;

        ItemPickupTracker.TrackedItem closest =
                ItemPickupTracker.findClosestFKeyPickup(store, playerPos, pickupRadiusSq);

        if (closest != null) {
            // Special item handling — heal and ammo items apply effects instead of going to inventory.
            String itemId = closest.getItemId();
            if ("UnstableRifts_Heal_Item".equals(itemId)) {
                collectHealItem(closest, player, playerRef, playerEntityRef, store);
                return;
            }
            if ("UnstableRifts_Ammo_Item".equals(itemId)) {
                collectAmmoItem(closest, player, playerRef, playerEntityRef, store);
                return;
            }

            collectItem(closest, player, playerRef, playerEntityRef, store);
            return;
        }

        // Open shop if near a shopkeeper and there is no pickup target competing for F.
        if (tryOpenShop(playerRef, playerEntityRef, store, playerPos)) {
            return;
        }
    }

    /**
     * Collects a single F-key item: untracks atomically, runs the inventory
     * transaction, removes entity only on success, re-tracks on partial/full
     * failure.
     */
    private static void collectItem(@Nonnull ItemPickupTracker.TrackedItem tracked,
                                    @Nonnull Player player,
                                    @Nonnull PlayerRef playerRef,
                                    @Nonnull Ref<EntityStore> playerEntityRef,
                                    @Nonnull Store<EntityStore> store) {

        Ref<EntityStore> itemRef = tracked.getRef();
        if (!itemRef.isValid()) {
            ItemPickupTracker.untrack(itemRef);
            return;
        }

        // Atomic untrack to prevent double-collection.
        ItemPickupTracker.TrackedItem removed = ItemPickupTracker.entries().remove(itemRef);
        if (removed == null) {
            return;
        }

        ItemComponent itemComponent = store.getComponent(itemRef, ItemComponent.getComponentType());
        if (itemComponent == null) {
            return;
        }

        ItemStack itemStack = itemComponent.getItemStack();
        if (ItemStack.isEmpty(itemStack)) {
            store.removeEntity(itemRef, RemoveReason.REMOVE);
            return;
        }

        // When the player's inventory is locked (dungeon), use the 3-slot weapon swap logic.
        UnstableRifts unstablerifts = UnstableRifts.getInstance();
        if (unstablerifts != null && unstablerifts.getInventoryLockService().isLocked(playerRef.getUuid())) {
            collectItemLocked(tracked, player, playerRef, playerEntityRef, store, itemRef, itemComponent, itemStack);
            return;
        }

        ItemStackTransaction transaction = player.giveItem(
                itemStack, playerEntityRef, store);
        ItemStack remainder = transaction.getRemainder();

        if (ItemStack.isEmpty(remainder)) {
            // Full pickup.
            itemComponent.setRemovedByPlayerPickup(true);
            store.removeEntity(itemRef, RemoveReason.REMOVE);
            ItemPickupConfig.applyPickupDelay(playerRef.getUuid());

            sendPickupNotification(playerRef, tracked, itemStack.getQuantity());

        } else if (!remainder.equals(itemStack)) {
            // Partial pickup — update remaining stack and re-track.
            int pickedUp = itemStack.getQuantity() - remainder.getQuantity();
            itemComponent.setItemStack(remainder);

            ItemPickupTracker.track(tracked);
            ItemPickupConfig.applyPickupDelay(playerRef.getUuid());

            if (pickedUp > 0) {
                sendPickupNotification(playerRef, tracked, pickedUp);
            }
        } else {
            // Inventory full — re-track, item stays in world.
            ItemPickupTracker.track(tracked);
        }
    }

    /**
     * Locked-inventory pickup: places the weapon in the first empty slot (0-2),
     * or swaps the held weapon if all 3 slots are full (dropping the old one).
     */
    private static void collectItemLocked(@Nonnull ItemPickupTracker.TrackedItem tracked,
                                          @Nonnull Player player,
                                          @Nonnull PlayerRef playerRef,
                                          @Nonnull Ref<EntityStore> playerEntityRef,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull Ref<EntityStore> itemRef,
                                          @Nonnull ItemComponent itemComponent,
                                          @Nonnull ItemStack pickedItem) {

        ArmorDefinition armorDef = ArmorDefinitions.getById(pickedItem.getItemId());
        if (armorDef != null) {
            collectArmorLocked(tracked, player, playerRef, playerEntityRef, store, itemRef, itemComponent, pickedItem, armorDef);
            return;
        }

        InventoryComponent.Hotbar hotbarComp = store.getComponent(playerEntityRef, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp == null) {
            ItemPickupTracker.track(tracked);
            return;
        }

        ItemContainer hotbar = hotbarComp.getInventory();
        short emptySlot = InventoryLockService.findEmptyWeaponSlot(hotbar);

        if (emptySlot >= 0) {
            // Free slot available — place directly.
            hotbar.setItemStackForSlot(emptySlot, pickedItem);
        } else {
            // All 3 weapon slots full — swap with held slot.
            byte activeSlot = hotbarComp.getActiveSlot();
            if (activeSlot < 0 || activeSlot >= InventoryLockService.MAX_WEAPON_SLOTS) {
                activeSlot = 0;
            }

            ItemStack oldWeapon = hotbar.getItemStack(activeSlot);
            hotbar.setItemStackForSlot(activeSlot, pickedItem);

            // Drop the old weapon back into the world.
            if (!ItemStack.isEmpty(oldWeapon)) {
                ItemUtils.dropItem(playerEntityRef, oldWeapon, store);
            }
        }

        // Sync inventory to client — use write() (not writeNoCache) so the
        // outbound tooltip adapters apply virtual item IDs and send the
        // UpdateItems/UpdateTranslations definitions ahead of this packet.
        InventoryComponent.Storage storageComp = store.getComponent(playerEntityRef, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Armor armorComp = store.getComponent(playerEntityRef, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Utility utilityComp = store.getComponent(playerEntityRef, InventoryComponent.Utility.getComponentType());
        InventoryComponent.Tool toolComp = store.getComponent(playerEntityRef, InventoryComponent.Tool.getComponentType());
        InventoryComponent.Backpack backpackComp = store.getComponent(playerEntityRef, InventoryComponent.Backpack.getComponentType());

        playerRef.getPacketHandler().write(new UpdatePlayerInventory(
                storageComp != null ? storageComp.getInventory().toPacket() : null,
                armorComp != null ? armorComp.getInventory().toPacket() : null,
                hotbar.toPacket(),
                utilityComp != null ? utilityComp.getInventory().toPacket() : null,
                toolComp != null ? toolComp.getInventory().toPacket() : null,
                backpackComp != null ? backpackComp.getInventory().toPacket() : null
        ));

        // Remove the picked-up entity from the world.
        itemComponent.setRemovedByPlayerPickup(true);
        store.removeEntity(itemRef, RemoveReason.REMOVE);
        ItemPickupConfig.applyPickupDelay(playerRef.getUuid());

        sendPickupNotification(playerRef, tracked, pickedItem.getQuantity());
    }

    private static void collectArmorLocked(@Nonnull ItemPickupTracker.TrackedItem tracked,
                                           @Nonnull Player player,
                                           @Nonnull PlayerRef playerRef,
                                           @Nonnull Ref<EntityStore> playerEntityRef,
                                           @Nonnull Store<EntityStore> store,
                                           @Nonnull Ref<EntityStore> itemRef,
                                           @Nonnull ItemComponent itemComponent,
                                           @Nonnull ItemStack armorItem,
                                           @Nonnull ArmorDefinition armorDef) {

        InventoryComponent.Armor armorComp = store.getComponent(playerEntityRef, InventoryComponent.Armor.getComponentType());
        if (armorComp == null) {
            ItemPickupTracker.track(tracked);
            return;
        }

        ItemContainer armorInv = armorComp.getInventory();
        short targetSlot = (short) armorDef.slotType().getSlotIndex();
        ItemStack oldArmor = armorInv.getItemStack(targetSlot);
        armorInv.setItemStackForSlot(targetSlot, armorItem, false);

        if (!ItemStack.isEmpty(oldArmor)) {
            ItemUtils.dropItem(playerEntityRef, oldArmor, store);
        }

        UnstableRifts unstablerifts = UnstableRifts.getInstance();
        if (unstablerifts != null) {
            ArmorSetTracker tracker = unstablerifts.getArmorSetTracker();
            if (tracker != null) {
                tracker.onArmorChanged(playerRef.getUuid(), armorInv);
            }
        }

        InventoryComponent.Storage storageComp = store.getComponent(playerEntityRef, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Hotbar hotbarComp = store.getComponent(playerEntityRef, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Utility utilityComp = store.getComponent(playerEntityRef, InventoryComponent.Utility.getComponentType());
        InventoryComponent.Tool toolComp = store.getComponent(playerEntityRef, InventoryComponent.Tool.getComponentType());
        InventoryComponent.Backpack backpackComp = store.getComponent(playerEntityRef, InventoryComponent.Backpack.getComponentType());

        playerRef.getPacketHandler().write(new UpdatePlayerInventory(
                storageComp != null ? storageComp.getInventory().toPacket() : null,
                armorInv.toPacket(),
                hotbarComp != null ? hotbarComp.getInventory().toPacket() : null,
                utilityComp != null ? utilityComp.getInventory().toPacket() : null,
                toolComp != null ? toolComp.getInventory().toPacket() : null,
                backpackComp != null ? backpackComp.getInventory().toPacket() : null
        ));

        itemComponent.setRemovedByPlayerPickup(true);
        store.removeEntity(itemRef, RemoveReason.REMOVE);
        ItemPickupConfig.applyPickupDelay(playerRef.getUuid());

        sendPickupNotification(playerRef, tracked, armorItem.getQuantity());
    }

    private static boolean tryUsePortal(@Nonnull PlayerRef playerRef,
                                        @Nonnull Vector3d playerPos) {
        UnstableRifts unstablerifts = UnstableRifts.getInstance();
        if (unstablerifts == null) {
            return false;
        }

        return unstablerifts.getPortalInteractionService().tryInteract(playerRef, playerPos);
    }

    private static boolean tryUnlockKeyDoor(@Nonnull PlayerRef playerRef,
                                            @Nonnull Vector3d playerPos) {
        UnstableRifts unstablerifts = UnstableRifts.getInstance();
        if (unstablerifts == null) {
            return false;
        }

        Game game = unstablerifts.getGameManager().findGameForPlayer(playerRef.getUuid());
        if (game == null) {
            return false;
        }

        World world = game.getInstanceWorld();
        if (world == null) {
            return false;
        }

        Level level = game.getCurrentLevel();
        if (level == null) {
            return false;
        }

        DoorService.NearbyKeyDoor nearbyDoor = unstablerifts.getDoorService().findNearbyKeyDoor(
                level, playerPos, DoorService.KEY_INTERACTION_RADIUS);
        if (nearbyDoor == null) {
            return false;
        }

        if (!game.useKey()) {
            sendInteractionNotification(playerRef,
                    "This door is locked. Your team needs a key.",
                    "door_locked");
            return true;
        }

        unstablerifts.getDoorService().unlockKeyDoor(level, world, nearbyDoor.target());

        int remainingKeys = game.getTeamKeys();
        sendInteractionNotification(playerRef,
                "Door unlocked! (" + remainingKeys + " key" + (remainingKeys != 1 ? "s" : "") + " left)",
                "door_unlocked");
        return true;
    }

    private static boolean tryOpenShop(@Nonnull PlayerRef playerRef,
                                       @Nonnull Ref<EntityStore> playerEntityRef,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull Vector3d playerPos) {
        UnstableRifts unstablerifts = UnstableRifts.getInstance();
        if (unstablerifts == null) return false;

        Game game = unstablerifts.getGameManager().findGameForPlayer(playerRef.getUuid());
        if (game == null) return false;

        ShopService shopService = unstablerifts.getShopService();
        ShopService.ShopRoomInventory nearby = shopService.findNearbyShop(game, playerPos);
        if (nearby == null) return false;

        Player player = store.getComponent(playerEntityRef, Player.getComponentType());
        if (player == null) return false;

        ShopPage shopPage = new ShopPage(playerRef, game, nearby);
        player.getPageManager().openCustomPage(playerEntityRef, store, shopPage);
        return true;
    }

    /**
     * Collects a heal item: restores player HP to max, removes entity.
     */
    private static void collectHealItem(@Nonnull ItemPickupTracker.TrackedItem tracked,
                                        @Nonnull Player player,
                                        @Nonnull PlayerRef playerRef,
                                        @Nonnull Ref<EntityStore> playerEntityRef,
                                        @Nonnull Store<EntityStore> store) {

        Ref<EntityStore> itemRef = tracked.getRef();
        if (!itemRef.isValid()) {
            ItemPickupTracker.untrack(itemRef);
            return;
        }

        // Atomic untrack to prevent double-collection.
        ItemPickupTracker.TrackedItem removed = ItemPickupTracker.entries().remove(itemRef);
        if (removed == null) {
            return;
        }

        // Heal the player to max HP.
        EntityStatMap statMap = store.getComponent(playerEntityRef, EntityStatMap.getComponentType());
        if (statMap != null) {
            int healthIdx = DefaultEntityStatTypes.getHealth();
            EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
            if (healthStat != null) {
                float before = healthStat.get();
                statMap.setStatValue(healthIdx, healthStat.getMax());
                float healed = healthStat.getMax() - before;

                try {
                    String text = healed > 0
                            ? "Healed! (+" + (int) healed + " HP)"
                            : "Already at full health!";
                    NotificationUtil.sendNotification(
                            playerRef.getPacketHandler(),
                            Message.raw(text),
                            null,
                            "heal_collected");
                } catch (Exception e) {
                    // Best-effort notification
                }
            }
        }

        ItemComponent itemComponent = store.getComponent(itemRef, ItemComponent.getComponentType());
        if (itemComponent != null) {
            itemComponent.setRemovedByPlayerPickup(true);
        }
        store.removeEntity(itemRef, RemoveReason.REMOVE);
        ItemPickupConfig.applyPickupDelay(playerRef.getUuid());
    }

    /**
     * Collects an ammo item: refills the held weapon's ammo to max, removes entity.
     */
    private static void collectAmmoItem(@Nonnull ItemPickupTracker.TrackedItem tracked,
                                        @Nonnull Player player,
                                        @Nonnull PlayerRef playerRef,
                                        @Nonnull Ref<EntityStore> playerEntityRef,
                                        @Nonnull Store<EntityStore> store) {

        Ref<EntityStore> itemRef = tracked.getRef();
        if (!itemRef.isValid()) {
            ItemPickupTracker.untrack(itemRef);
            return;
        }

        // Check if the player is holding a weapon with ammo.
        ItemStack heldItem = player.getInventory() != null ? player.getInventory().getItemInHand() : null;
        if (heldItem == null || ItemStack.isEmpty(heldItem)) {
            // Re-track, item stays in world.
            try {
                NotificationUtil.sendNotification(
                        playerRef.getPacketHandler(),
                        Message.raw("Equip a weapon first!"),
                        null,
                        "ammo_fail");
            } catch (Exception e) {
                // Best-effort
            }
            return;
        }

        String weaponId = heldItem.getItemId();
        WeaponDefinition definition = weaponId != null ? WeaponDefinitions.getById(weaponId) : null;

        int baseMaxAmmo = definition != null && definition.baseMaxAmmo() > 0
                ? definition.baseMaxAmmo()
                : GunItemMetadata.getBaseMaxAmmo(heldItem, -1);
        if (baseMaxAmmo <= 0) {
            // Not a ranged weapon.
            try {
                NotificationUtil.sendNotification(
                        playerRef.getPacketHandler(),
                        Message.raw("Equip a weapon first!"),
                        null,
                        "ammo_fail");
            } catch (Exception e) {
                // Best-effort
            }
            return;
        }

        double armorAmmoCapacityBonus = 0.0;
        InventoryComponent.Armor armorComp = store.getComponent(playerEntityRef, InventoryComponent.Armor.getComponentType());
        if (armorComp != null) {
            armorAmmoCapacityBonus = ArmorStatResolver.getTotalAmmoCapacityBonus(armorComp.getInventory());
        }
        int effectiveMaxAmmo = GunItemMetadata.getEffectiveMaxAmmo(heldItem, baseMaxAmmo, armorAmmoCapacityBonus);
        int currentAmmo = GunItemMetadata.getInt(heldItem, GunItemMetadata.AMMO_KEY, effectiveMaxAmmo);

        if (currentAmmo >= effectiveMaxAmmo) {
            // Already full.
            try {
                NotificationUtil.sendNotification(
                        playerRef.getPacketHandler(),
                        Message.raw("Ammo already full!"),
                        null,
                        "ammo_fail");
            } catch (Exception e) {
                // Best-effort
            }
            return;
        }

        // Atomic untrack to prevent double-collection.
        ItemPickupTracker.TrackedItem removed = ItemPickupTracker.entries().remove(itemRef);
        if (removed == null) {
            return;
        }

        // Refill ammo to max.
        ItemStack updated = GunItemMetadata.setInt(heldItem, GunItemMetadata.AMMO_KEY, effectiveMaxAmmo);
        updated = GunItemMetadata.ensureAmmo(updated, baseMaxAmmo, effectiveMaxAmmo);

        // Apply to held slot.
        InventoryComponent.Hotbar hotbarComp = store.getComponent(playerEntityRef, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp != null) {
            byte activeSlot = hotbarComp.getActiveSlot();
            ItemContainer hotbar = hotbarComp.getInventory();
            hotbar.replaceItemStackInSlot(activeSlot, heldItem, updated);
        }

        // Update ammo HUD.
        AmmoHudService.clear(playerRef);
        AmmoHudService.updateForHeldItem(player, playerRef, updated, false, playerEntityRef);

        int refilled = effectiveMaxAmmo - currentAmmo;
        try {
            NotificationUtil.sendNotification(
                    playerRef.getPacketHandler(),
                    Message.raw("Ammo refilled! (+" + refilled + ")"),
                    null,
                    "ammo_collected");
        } catch (Exception e) {
            // Best-effort notification
        }

        ItemComponent itemComponent = store.getComponent(itemRef, ItemComponent.getComponentType());
        if (itemComponent != null) {
            itemComponent.setRemovedByPlayerPickup(true);
        }
        store.removeEntity(itemRef, RemoveReason.REMOVE);
        ItemPickupConfig.applyPickupDelay(playerRef.getUuid());
    }

    private static void sendPickupNotification(@Nonnull PlayerRef playerRef,
                                               @Nonnull ItemPickupTracker.TrackedItem tracked,
                                               int quantity) {
        try {
            String displayName = tracked.getDisplayName() != null
                    ? tracked.getDisplayName()
                    : tracked.getItemId();

            String text;
            if (quantity > 1) {
                text = "Picked up " + displayName + " x" + quantity;
            } else {
                text = "Picked up " + displayName;
            }

            NotificationUtil.sendNotification(
                    playerRef.getPacketHandler(),
                    Message.raw(text),
                    null,
                    "crate_item_pickup");
        } catch (Exception e) {
            // Best-effort notification — player still received the item
        }
    }

    private static void sendInteractionNotification(@Nonnull PlayerRef playerRef,
                                                    @Nonnull String text,
                                                    @Nonnull String notificationId) {
        try {
            NotificationUtil.sendNotification(
                    playerRef.getPacketHandler(),
                    Message.raw(text),
                    null,
                    notificationId);
        } catch (Exception e) {
            // Best-effort feedback only.
        }
    }

    @Override
    public void accept(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (packet.getId() != UnstableRiftsPacketIds.SYNC_INTERACTION_CHAINS) {
            return;
        }

        SyncInteractionChains interactionChains = (SyncInteractionChains) packet;
        SyncInteractionChain[] updates = interactionChains.updates;

        if (updates == null || updates.length == 0) {
            return;
        }

        boolean hasUsePress = false;
        for (SyncInteractionChain chain : updates) {
            if (chain.interactionType == InteractionType.Use) {
                hasUsePress = true;
                break;
            }
        }

        if (!hasUsePress) {
            return;
        }

        if (!playerRef.isValid()) {
            return;
        }

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        World world;
        try {
            world = store.getExternalData().getWorld();
        } catch (Exception e) {
            return;
        }

        if (world == null) {
            return;
        }

        world.execute(() -> attemptPickup(playerRef, playerEntityRef));
    }
}
