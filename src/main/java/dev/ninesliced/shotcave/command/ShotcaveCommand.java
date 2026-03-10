package dev.ninesliced.shotcave.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import dev.ninesliced.shotcave.Shotcave;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.util.concurrent.CompletableFuture;

public class ShotcaveCommand extends AbstractCommand {

    public ShotcaveCommand(@Nonnull Shotcave plugin) {
        super("shotcave", "Manage Shotcave dungeon, camera, and debug tools");
        this.addAliases("sc");
        this.addSubCommand(new TopCameraCommand(plugin));
        this.addSubCommand(new DungeonCommand(plugin));
        this.addSubCommand(new CoinCommand());
        this.addSubCommand(new PickupDebugCommand());
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
        context.sendMessage(Message.raw("=== Shotcave Commands ===").color(Color.ORANGE));
        context.sendMessage(Message.raw("Roguelike dungeon shooter in Hytale.").color(Color.YELLOW));
        context.sendMessage(Message.raw("- /shotcave dungeon <dungeon>").color(Color.WHITE)
                .insert(Message.raw(" : Create and enter a configured dungeon instance.").color(Color.GRAY)));
        context.sendMessage(Message.raw("- /shotcave topcamera").color(Color.WHITE)
                .insert(Message.raw(" : Toggle top-down camera mode.").color(Color.GRAY)));
        context.sendMessage(Message.raw("Tip: Run /shotcave --help to view all subcommands and usage.").color(Color.CYAN));
        return CompletableFuture.completedFuture(null);
    }
}