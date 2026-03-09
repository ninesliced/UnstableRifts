package dev.ninesliced.shotcave.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;

import javax.annotation.Nonnull;

public final class AcceptPartyInviteCommand extends AbstractPlayerCommand {

    private final Shotcave plugin;

    public AcceptPartyInviteCommand(@Nonnull Shotcave plugin) {
        super("accept", "Accept your most recent active party invite");
        this.plugin = plugin;
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
        PartyCommand.sendResult(context, this.plugin.getPartyManager().acceptInvite(playerRef));
    }
}