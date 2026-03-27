package dev.ninesliced.shotcave.pickup;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import org.joml.Vector3d;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.armor.ArmorDefinition;
import dev.ninesliced.shotcave.armor.ArmorDefinitions;
import dev.ninesliced.shotcave.armor.ArmorSetTracker;
import dev.ninesliced.shotcave.inventory.InventoryLockService;

import javax.annotation.Nonnull;

/**
 * F-key interaction that picks up PreventPickup item entities and adds them to
 * the player's inventory.
 */
public final class ItemPickupInteraction extends SimpleInstantInteraction {

    @Nonnull
    public static final BuilderCodec<ItemPickupInteraction> CODEC = BuilderCodec.builder(
            ItemPickupInteraction.class,
            ItemPickupInteraction::new,
            SimpleInstantInteraction.CODEC)
            .documentation("Picks up an F-key-protected item entity and adds it to the player's inventory.")
            .build();

    public static final String DEFAULT_ID = ItemPickupConfig.ITEM_PICKUP_INTERACTION_ID;

    public static final RootInteraction DEFAULT_ROOT = new RootInteraction(
            ItemPickupConfig.ITEM_PICKUP_INTERACTION_ID,
            ItemPickupConfig.ITEM_PICKUP_INTERACTION_ID);

    public ItemPickupInteraction(@Nonnull String id) {
        super(id);
    }

    protected ItemPickupInteraction() {
    }

    /**
     * Validates player/target, checks distance, attempts inventory transaction,
     * then fully or partially removes the item entity.
     * Does NOT call generatePickedUpItem() to avoid infinite re-tracking loops.
     */
    @Override
    protected final void firstRun(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler) {

        Ref<EntityStore> ref = context.getEntity();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();

        Player playerComponent = commandBuffer.getComponent(ref, Player.getComponentType());
        if (playerComponent == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        Ref<EntityStore> targetRef = context.getTargetEntity();
        if (targetRef == null || !targetRef.isValid()) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        ItemComponent itemComponent = commandBuffer.getComponent(targetRef, ItemComponent.getComponentType());
        if (itemComponent == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        TransformComponent transformComponent = commandBuffer.getComponent(targetRef,
                TransformComponent.getComponentType());
        if (transformComponent == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        ItemStack itemStack = itemComponent.getItemStack();
        if (ItemStack.isEmpty(itemStack)) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        // Distance check.
        Vector3d itemEntityPosition = transformComponent.getPosition();
        TransformComponent playerTransform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        if (playerTransform != null) {
            Vector3d playerPos = playerTransform.getPosition();
            double dx = playerPos.x - itemEntityPosition.x;
            double dy = playerPos.y - itemEntityPosition.y;
            double dz = playerPos.z - itemEntityPosition.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            double maxDist = ItemPickupConfig.ITEM_PICKUP_RADIUS;
            if (distSq > maxDist * maxDist) {
                context.getState().state = InteractionState.Failed;
                return;
            }
        }

        // Locked inventory (dungeon) — weapon swap or armor equip logic.
        Shotcave shotcave = Shotcave.getInstance();
        PlayerRef playerRefComp = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (shotcave != null && playerRefComp != null
                && shotcave.getInventoryLockService().isLocked(playerRefComp.getUuid())) {

            // Check if this item is an armor piece
            ArmorDefinition armorDef = ArmorDefinitions.getById(itemStack.getItemId());
            if (armorDef != null) {
                // Armor pickup — equip to the correct armor slot
                InventoryComponent.Armor armorComp = commandBuffer.getComponent(ref, InventoryComponent.Armor.getComponentType());
                if (armorComp == null) return;

                ItemContainer armorInv = armorComp.getInventory();
                short targetSlot = (short) armorDef.getSlotType().getSlotIndex();
                ItemStack oldArmor = armorInv.getItemStack(targetSlot);
                armorInv.setItemStackForSlot(targetSlot, itemStack, false);

                if (!ItemStack.isEmpty(oldArmor)) {
                    ItemUtils.dropItem(ref, oldArmor, commandBuffer);
                }

                // Notify armor set tracker
                ArmorSetTracker tracker = shotcave.getArmorSetTracker();
                if (tracker != null) {
                    tracker.onArmorChanged(playerRefComp.getUuid(), armorInv);
                }

                InventoryComponent.Storage storageComp = commandBuffer.getComponent(ref, InventoryComponent.Storage.getComponentType());
                InventoryComponent.Hotbar hotbarComp = commandBuffer.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
                InventoryComponent.Utility utilityComp = commandBuffer.getComponent(ref, InventoryComponent.Utility.getComponentType());
                InventoryComponent.Tool toolComp = commandBuffer.getComponent(ref, InventoryComponent.Tool.getComponentType());
                InventoryComponent.Backpack backpackComp = commandBuffer.getComponent(ref, InventoryComponent.Backpack.getComponentType());

                playerRefComp.getPacketHandler().writeNoCache(new UpdatePlayerInventory(
                        storageComp != null ? storageComp.getInventory().toPacket() : null,
                        armorInv.toPacket(),
                        hotbarComp != null ? hotbarComp.getInventory().toPacket() : null,
                        utilityComp != null ? utilityComp.getInventory().toPacket() : null,
                        toolComp != null ? toolComp.getInventory().toPacket() : null,
                        backpackComp != null ? backpackComp.getInventory().toPacket() : null
                ));
                itemComponent.setRemovedByPlayerPickup(true);
                commandBuffer.removeEntity(targetRef, RemoveReason.REMOVE);
                playerComponent.notifyPickupItem(ref, itemStack, itemEntityPosition, commandBuffer);
                return;
            }

            // Weapon pickup — use 3-slot weapon swap logic.
            InventoryComponent.Hotbar hotbarComp = commandBuffer.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            if (hotbarComp == null) return;

            ItemContainer hotbar = hotbarComp.getInventory();

            short emptySlot = InventoryLockService.findEmptyWeaponSlot(hotbar);
            if (emptySlot >= 0) {
                hotbar.setItemStackForSlot(emptySlot, itemStack);
            } else {
                byte activeSlot = hotbarComp.getActiveSlot();
                if (activeSlot < 0 || activeSlot >= InventoryLockService.MAX_WEAPON_SLOTS) {
                    activeSlot = 0;
                }
                ItemStack oldWeapon = hotbar.getItemStack(activeSlot);
                hotbar.setItemStackForSlot(activeSlot, itemStack);
                if (!ItemStack.isEmpty(oldWeapon)) {
                    ItemUtils.dropItem(ref, oldWeapon, commandBuffer);
                }
            }

            InventoryComponent.Storage storageComp = commandBuffer.getComponent(ref, InventoryComponent.Storage.getComponentType());
            InventoryComponent.Armor armorComp = commandBuffer.getComponent(ref, InventoryComponent.Armor.getComponentType());
            InventoryComponent.Utility utilityComp = commandBuffer.getComponent(ref, InventoryComponent.Utility.getComponentType());
            InventoryComponent.Tool toolComp = commandBuffer.getComponent(ref, InventoryComponent.Tool.getComponentType());
            InventoryComponent.Backpack backpackComp = commandBuffer.getComponent(ref, InventoryComponent.Backpack.getComponentType());

            playerRefComp.getPacketHandler().writeNoCache(new UpdatePlayerInventory(
                    storageComp != null ? storageComp.getInventory().toPacket() : null,
                    armorComp != null ? armorComp.getInventory().toPacket() : null,
                    hotbar.toPacket(),
                    utilityComp != null ? utilityComp.getInventory().toPacket() : null,
                    toolComp != null ? toolComp.getInventory().toPacket() : null,
                    backpackComp != null ? backpackComp.getInventory().toPacket() : null
            ));
            itemComponent.setRemovedByPlayerPickup(true);
            commandBuffer.removeEntity(targetRef, RemoveReason.REMOVE);
            playerComponent.notifyPickupItem(ref, itemStack, itemEntityPosition, commandBuffer);
            return;
        }

        // Inventory transaction.
        ItemStackTransaction transaction = playerComponent.giveItem(itemStack, ref, commandBuffer);
        ItemStack remainder = transaction.getRemainder();

        if (ItemStack.isEmpty(remainder)) {
            // Full pickup.
            itemComponent.setRemovedByPlayerPickup(true);
            commandBuffer.removeEntity(targetRef, RemoveReason.REMOVE);
            playerComponent.notifyPickupItem(ref, itemStack, itemEntityPosition, commandBuffer);

        } else if (!remainder.equals(itemStack)) {
            // Partial pickup (inventory almost full).
            int pickedUp = itemStack.getQuantity() - remainder.getQuantity();
            itemComponent.setItemStack(remainder);

            if (pickedUp > 0) {
                playerComponent.notifyPickupItem(
                        ref, itemStack.withQuantity(pickedUp), itemEntityPosition, commandBuffer);
            }
        }
        // remainder == itemStack → inventory full, item stays.
    }

    @Nonnull
    @Override
    public String toString() {
        return "ItemPickupInteraction{} " + super.toString();
    }
}
