package dev.ninesliced.unstablerifts.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import dev.ninesliced.unstablerifts.UnstableRifts;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

public class UnstableRiftsCommand extends AbstractCommand {

    public UnstableRiftsCommand(@Nonnull UnstableRifts plugin) {
        super("unstablerifts", "Manage UnstableRifts dungeon, camera, and debug tools");
        this.addAliases("sc");
        this.addSubCommand(new TopCameraCommand(plugin));
        this.addSubCommand(new DungeonCommand(plugin));
        this.addSubCommand(new CoinCommand());
        this.addSubCommand(new PickupDebugCommand());
        this.addSubCommand(new AcceptPartyInviteCommand(plugin));
        this.addSubCommand(new GivePartyPortalCommand());
        this.addSubCommand(new PartyCommand(plugin));
        this.addSubCommand(new LootCommand());
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected String generatePermissionNode() {
        return "";
    }

    @Nonnull
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("=== UnstableRifts Commands ===").color(Color.ORANGE));
        context.sendMessage(Message.raw("Roguelike dungeon shooter in Hytale.").color(Color.YELLOW));
        context.sendMessage(Message.raw("- /unstablerifts dungeon <dungeon>").color(Color.WHITE)
                .insert(Message.raw(" : Create and enter a configured dungeon instance.").color(Color.GRAY)));
        context.sendMessage(Message.raw("- /unstablerifts topcamera").color(Color.WHITE)
                .insert(Message.raw(" : Toggle top-down camera mode.").color(Color.GRAY)));
        context.sendMessage(Message.raw("- /unstablerifts giveportal").color(Color.WHITE)
                .insert(Message.raw(" : Admin only, gives the ancient party portal item in-hand.").color(Color.GRAY)));
        context.sendMessage(Message.raw("- /unstablerifts accept").color(Color.WHITE)
                .insert(Message.raw(" : Accept your most recent active party invite.").color(Color.GRAY)));
        context.sendMessage(Message.raw("- /unstablerifts party").color(Color.WHITE)
                .insert(Message.raw(" : Open party management commands and UI.").color(Color.GRAY)));
        context.sendMessage(Message.raw("- /unstablerifts loot").color(Color.WHITE)
                .insert(Message.raw(" : Spawn a random weapon drop at your feet.").color(Color.GRAY)));
        context.sendMessage(Message.raw("Tip: Run /unstablerifts --help to view all subcommands and usage.").color(Color.CYAN));
        return CompletableFuture.completedFuture(null);
    }
}