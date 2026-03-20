package dev.ninesliced.shotcave.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.packets.inventory.SetActiveSlot;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.util.Set;
import java.util.UUID;

public final class GivePartyPortalCommand extends AbstractPlayerCommand {

    private static final String PORTAL_ITEM_ID = "Shotcave_Ancient_Party_Portal";
    private static final String ADMIN_PERMISSION = "shotcave.admin";

    public GivePartyPortalCommand() {
        super("giveportal", "Give yourself the Shotcave party portal item");
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

    @SuppressWarnings("removal")
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

        Inventory inventory = player.getInventory();
        byte activeSlot = inventory.getActiveHotbarSlot();
        if (activeSlot < 0) {
            activeSlot = 0;
            inventory.setActiveHotbarSlot(ref, activeSlot, store);
            playerRef.getPacketHandler().writeNoCache(new SetActiveSlot(-1, activeSlot));
        }

        inventory.getHotbar().setItemStackForSlot((short) activeSlot, new ItemStack(PORTAL_ITEM_ID));
        playerRef.getPacketHandler().writeNoCache(new UpdatePlayerInventory(
                inventory.getStorage() != null ? inventory.getStorage().toPacket() : null,
                inventory.getArmor() != null ? inventory.getArmor().toPacket() : null,
                inventory.getHotbar() != null ? inventory.getHotbar().toPacket() : null,
                inventory.getUtility() != null ? inventory.getUtility().toPacket() : null,
                inventory.getTools() != null ? inventory.getTools().toPacket() : null,
                inventory.getBackpack() != null ? inventory.getBackpack().toPacket() : null
        ));
        context.sendMessage(Message.raw("Placed the Ancient Party Portal in your hand.").color(Color.GREEN));
    }
}
