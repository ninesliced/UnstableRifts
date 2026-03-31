package dev.ninesliced.unstablerifts.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.Set;
import java.util.UUID;

public final class GivePartyPortalCommand extends AbstractPlayerCommand {

    private static final String PORTAL_ITEM_ID = "UnstableRifts_Ancient_Party_Portal";
    private static final String ADMIN_PERMISSION = "unstablerifts.admin";

    public GivePartyPortalCommand() {
        super("giveportal", "Give yourself the UnstableRifts party portal item");
        this.addAliases("portalitem", "partyportal");
    }

    private static boolean isAdmin(@Nonnull Player player) {
        PermissionsModule permissions = PermissionsModule.get();
        if (permissions == null) {
            return false;
        }

        UUID uuid = ((CommandSender) player).getUuid();
        Set<String> groups = permissions.getGroupsForUser(uuid);
        return groups.contains("OP") || permissions.hasPermission(uuid, ADMIN_PERMISSION);
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected String generatePermissionNode() {
        return "";
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            context.sendMessage(Message.raw("Could not resolve player.").color(Color.RED));
            return;
        }

        if (!isAdmin(player)) {
            context.sendMessage(Message.raw("This command is restricted to OP/admin players.").color(Color.RED));
            return;
        }

        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp == null) return;

        byte activeSlot = hotbarComp.getActiveSlot();
        if (activeSlot < 0) {
            activeSlot = 0;
            hotbarComp.setActiveSlot(activeSlot);
            playerRef.getPacketHandler().writeNoCache(new SetActiveSlot(-1, activeSlot));
        }

        hotbarComp.getInventory().setItemStackForSlot(activeSlot, new ItemStack(PORTAL_ITEM_ID));

        InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        InventoryComponent.Tool toolComp = store.getComponent(ref, InventoryComponent.Tool.getComponentType());
        InventoryComponent.Backpack backpackComp = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());

        playerRef.getPacketHandler().writeNoCache(new UpdatePlayerInventory(
                storageComp != null ? storageComp.getInventory().toPacket() : null,
                armorComp != null ? armorComp.getInventory().toPacket() : null,
                hotbarComp.getInventory().toPacket(),
                utilityComp != null ? utilityComp.getInventory().toPacket() : null,
                toolComp != null ? toolComp.getInventory().toPacket() : null,
                backpackComp != null ? backpackComp.getInventory().toPacket() : null
        ));
        context.sendMessage(Message.raw("Placed the Ancient Party Portal in your hand.").color(Color.GREEN));
    }
}
