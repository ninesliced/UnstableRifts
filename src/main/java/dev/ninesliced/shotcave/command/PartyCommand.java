package dev.ninesliced.shotcave.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.party.PartyManager;
import dev.ninesliced.shotcave.party.PartyPrivacy;
import dev.ninesliced.shotcave.party.PartyUiPage;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class PartyCommand extends AbstractCommand {

    private final Shotcave plugin;

    public PartyCommand(@Nonnull Shotcave plugin) {
        super("party", "Manage Shotcave parties");
        this.plugin = plugin;
        this.addAliases("p");
        this.addSubCommand(new CreateCommand(plugin));
        this.addSubCommand(new InviteCommand(plugin));
        this.addSubCommand(new JoinCommand(plugin));
        this.addSubCommand(new LeaveCommand(plugin));
        this.addSubCommand(new DisbandCommand(plugin));
        this.addSubCommand(new KickCommand(plugin));
        this.addSubCommand(new PrivacyCommand(plugin));
        this.addSubCommand(new ListCommand(plugin));
        this.addSubCommand(new UiCommand(plugin));
        this.addSubCommand(new StartCommand(plugin));
    }

    static void sendResult(@Nonnull CommandContext context, @Nonnull PartyManager.ActionResult result) {
        Color color = result.success() ? Color.GREEN : Color.RED;
        context.sendMessage(PartyManager.partyPrefix().insert(Message.raw(result.message()).color(color)));
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
        context.sendMessage(PartyManager.partyPrefix().insert(Message.raw("Commands:").color(Color.WHITE)));
        context.sendMessage(Message.raw("/shotcave party ui").color(Color.WHITE).insert(Message.raw(" opens the Shotcave Party UI.").color(Color.GRAY)));
        context.sendMessage(Message.raw("/shotcave party create [public|private]").color(Color.WHITE).insert(Message.raw(" creates a party.").color(Color.GRAY)));
        context.sendMessage(Message.raw("/shotcave party invite <player>").color(Color.WHITE).insert(Message.raw(" invites a player.").color(Color.GRAY)));
        context.sendMessage(Message.raw("/shotcave accept").color(Color.WHITE).insert(Message.raw(" accepts your most recent active invite.").color(Color.GRAY)));
        context.sendMessage(Message.raw("/shotcave party join <partyId|leader>").color(Color.WHITE).insert(Message.raw(" joins a public party or accepts an invite.").color(Color.GRAY)));
        context.sendMessage(Message.raw("/shotcave party kick <player>").color(Color.WHITE).insert(Message.raw(" removes a member.").color(Color.GRAY)));
        context.sendMessage(Message.raw("/shotcave party privacy <public|private>").color(Color.WHITE).insert(Message.raw(" changes party visibility.").color(Color.GRAY)));
        context.sendMessage(Message.raw("/shotcave party leave").color(Color.WHITE).insert(Message.raw(" leaves the party.").color(Color.GRAY)));
        context.sendMessage(Message.raw("/shotcave party disband").color(Color.WHITE).insert(Message.raw(" deletes your party.").color(Color.GRAY)));
        context.sendMessage(Message.raw("/shotcave party list").color(Color.WHITE).insert(Message.raw(" lists available parties.").color(Color.GRAY)));
        context.sendMessage(Message.raw("/shotcave party start").color(Color.WHITE).insert(Message.raw(" launches the party into a fresh generated dungeon instance.").color(Color.GRAY)));
        return CompletableFuture.completedFuture(null);
    }

    private static final class CreateCommand extends AbstractPlayerCommand {
        private final Shotcave plugin;
        private final OptionalArg<String> privacyArg = this.withOptionalArg("privacy", "public or private", ArgTypes.STRING);

        private CreateCommand(@Nonnull Shotcave plugin) {
            super("create", "Create a party");
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
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            PartyPrivacy privacy = PartyPrivacy.from(this.privacyArg.get(context));
            if (privacy == null) {
                privacy = PartyPrivacy.PRIVATE;
            }
            sendResult(context, this.plugin.getPartyManager().createParty(playerRef, privacy));
        }
    }

    private static final class InviteCommand extends AbstractPlayerCommand {
        private final Shotcave plugin;
        private final RequiredArg<String> targetArg = this.withRequiredArg("player", "Player name", ArgTypes.STRING);

        private InviteCommand(@Nonnull Shotcave plugin) {
            super("invite", "Invite a player to your party");
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
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            sendResult(context, this.plugin.getPartyManager().invite(playerRef, this.targetArg.get(context)));
        }
    }

    private static final class JoinCommand extends AbstractPlayerCommand {
        private final Shotcave plugin;
        private final RequiredArg<String> selectorArg = this.withRequiredArg("party", "Party id or leader", ArgTypes.STRING);

        private JoinCommand(@Nonnull Shotcave plugin) {
            super("join", "Join a public party or accept an invite");
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
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            sendResult(context, this.plugin.getPartyManager().joinParty(playerRef, this.selectorArg.get(context)));
        }
    }

    private static final class LeaveCommand extends AbstractPlayerCommand {
        private final Shotcave plugin;

        private LeaveCommand(@Nonnull Shotcave plugin) {
            super("leave", "Leave your current party");
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
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            sendResult(context, this.plugin.getPartyManager().leave(playerRef));
        }
    }

    private static final class DisbandCommand extends AbstractPlayerCommand {
        private final Shotcave plugin;

        private DisbandCommand(@Nonnull Shotcave plugin) {
            super("disband", "Delete your party");
            this.plugin = plugin;
            this.addAliases("delete");
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
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            sendResult(context, this.plugin.getPartyManager().disband(playerRef));
        }
    }

    private static final class KickCommand extends AbstractPlayerCommand {
        private final Shotcave plugin;
        private final RequiredArg<String> targetArg = this.withRequiredArg("player", "Party member name", ArgTypes.STRING);

        private KickCommand(@Nonnull Shotcave plugin) {
            super("kick", "Remove a party member");
            this.plugin = plugin;
            this.addAliases("remove");
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
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            sendResult(context, this.plugin.getPartyManager().kick(playerRef, this.targetArg.get(context)));
        }
    }

    private static final class PrivacyCommand extends AbstractPlayerCommand {
        private final Shotcave plugin;
        private final RequiredArg<String> privacyArg = this.withRequiredArg("privacy", "public or private", ArgTypes.STRING);

        private PrivacyCommand(@Nonnull Shotcave plugin) {
            super("privacy", "Set party visibility");
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
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            PartyPrivacy privacy = PartyPrivacy.from(this.privacyArg.get(context));
            if (privacy == null) {
                context.sendMessage(PartyManager.partyPrefix().insert(Message.raw("Expected 'public' or 'private'.").color(Color.RED)));
                return;
            }
            sendResult(context, this.plugin.getPartyManager().setPrivacy(playerRef, privacy));
        }
    }

    private static final class ListCommand extends AbstractPlayerCommand {
        private final Shotcave plugin;

        private ListCommand(@Nonnull Shotcave plugin) {
            super("list", "List your party and public parties");
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
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            PartyManager manager = this.plugin.getPartyManager();
            PartyManager.PartySnapshot current = manager.getPartySnapshot(playerRef.getUuid());
            if (current != null) {
                context.sendMessage(PartyManager.partyPrefix().insert(Message.raw("Current party: " + current.name() + " [" + current.privacy().name().toLowerCase(Locale.ROOT) + "]").color(Color.WHITE)));
                for (PartyManager.PartyMemberSnapshot member : current.members()) {
                    String suffix = member.leader() ? " (Leader)" : "";
                    context.sendMessage(Message.raw("- " + member.name() + suffix).color(Color.GRAY));
                }
            } else {
                context.sendMessage(PartyManager.partyPrefix().insert(Message.raw("You are not in a party.").color(Color.YELLOW)));
            }

            var publicParties = manager.getPublicPartySnapshots();
            if (publicParties.isEmpty()) {
                context.sendMessage(PartyManager.partyPrefix().insert(Message.raw("No public parties are currently open.").color(Color.YELLOW)));
                return;
            }

            context.sendMessage(PartyManager.partyPrefix().insert(Message.raw("Public parties:").color(Color.WHITE)));
            for (PartyManager.PartySnapshot party : publicParties) {
                context.sendMessage(Message.raw("- " + party.name() + " by " + party.leaderName() + " [" + party.memberCount() + " members] (" + party.id() + ")").color(Color.GRAY));
            }
        }
    }

    private static final class UiCommand extends AbstractPlayerCommand {
        private final Shotcave plugin;

        private UiCommand(@Nonnull Shotcave plugin) {
            super("ui", "Open the Shotcave Party UI");
            this.plugin = plugin;
            this.addAliases("menu", "open");
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
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                context.sendMessage(PartyManager.partyPrefix().insert(Message.raw("Could not resolve player.").color(Color.RED)));
                return;
            }
            player.getPageManager().openCustomPage(ref, store, new PartyUiPage(this.plugin, playerRef));
        }
    }

    private static final class StartCommand extends AbstractPlayerCommand {
        private final Shotcave plugin;

        private StartCommand(@Nonnull Shotcave plugin) {
            super("start", "Launch a fresh generated dungeon instance for the party");
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
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            sendResult(context, this.plugin.getPartyManager().startParty(playerRef));
        }
    }
}