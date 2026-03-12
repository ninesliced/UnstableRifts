package dev.ninesliced.shotcave.party;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PartyUiPage extends InteractiveCustomUIPage<PartyUiPage.UiEventData> {

    private static final String LAYOUT_PATH = "Pages/ShotcaveParty/PartyPortal.ui";
    private static final String MEMBER_ITEM_PATH = "Pages/ShotcaveParty/PartyMemberItem.ui";
    private static final String PARTY_ITEM_PATH = "Pages/ShotcaveParty/PartyListItem.ui";
    private static final Map<UUID, PartyUiPage> OPEN_PAGES = new ConcurrentHashMap<>();

    private final Shotcave plugin;

    public PartyUiPage(@Nonnull Shotcave plugin, @Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, UiEventData.CODEC);
        this.plugin = plugin;
    }

    public static void refreshOpenPages() {
        for (PartyUiPage page : new ArrayList<>(OPEN_PAGES.values())) {
            page.refreshIfOpen();
        }
    }

    @Nonnull
    private static String safeValue(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        OPEN_PAGES.put(this.playerRef.getUuid(), this);
        ui.append(LAYOUT_PATH);
        Player viewer = store.getComponent(ref, Player.getComponentType());

        PartyManager manager = this.plugin.getPartyManager();
        PartyManager.PartySnapshot currentParty = manager.getPartySnapshot(this.playerRef.getUuid());
        List<PartyManager.PartyInviteSnapshot> invites = manager.getInviteSnapshots(this.playerRef.getUuid());
        List<PartyManager.PartySnapshot> publicParties = manager.getPublicPartySnapshots();
        List<PartyManager.InviteCandidateSnapshot> inviteCandidates = manager.getInviteCandidates(this.playerRef.getUuid());
        boolean inParty = currentParty != null;
        boolean leader = inParty && currentParty.leaderId().equals(this.playerRef.getUuid().toString());

        ui.set("#CreatePartyCard.Visible", !inParty);
        ui.set("#CurrentPartyCard.Visible", inParty);
        ui.set("#InvitesCard.Visible", !invites.isEmpty());
        ui.set("#EmptyPublicState.Visible", publicParties.isEmpty());

        ui.set("#OverviewStatus.Text", inParty
                ? "Your party is ready. Invite players, switch privacy, or start the run."
                : "Create a party here or join any public party listed on the right.");

        bindClick(events, "#CloseButton", Action.CLOSE);
        bindClick(events, "#RefreshButton", Action.REFRESH);
        bindClick(events, "#CreatePublicButton", Action.CREATE_PUBLIC);
        bindClick(events, "#CreatePrivateButton", Action.CREATE_PRIVATE);

        if (inParty) {
            ui.set("#CurrentPartyName.Text", currentParty.name());

            // Show game status if a game is active
            var gameManager = this.plugin.getGameManager();
            var game = gameManager.findGameForParty(java.util.UUID.fromString(currentParty.id()));
            boolean gameActive = game != null && game.getState() != dev.ninesliced.shotcave.dungeon.GameState.COMPLETE;
            boolean canStart = leader && !gameActive;
            boolean canTeleportToDungeon = gameActive
                    && game.getInstanceWorld() != null
                    && viewer != null
                    && viewer.getWorld() != game.getInstanceWorld();

            if (gameActive) {
                String gameStatus = switch (game.getState()) {
                    case GENERATING -> "Generating dungeon... " + Math.round(game.getGenerationProgress() * 100) + "%";
                    case READY -> "Dungeon ready! Entering shortly...";
                    case ACTIVE ->
                            "Dungeon in progress — " + dev.ninesliced.shotcave.dungeon.Game.formatTime(game.getElapsedGameTime());
                    case BOSS -> "⚔ Boss Fight in progress!";
                    default -> "Game active";
                };
                ui.set("#CurrentPartyMeta.Text", gameStatus);
            } else {
                ui.set("#CurrentPartyMeta.Text", "Leader: " + currentParty.leaderName() + "  |  " + currentParty.memberCount() + " members");
            }

            ui.set("#CurrentPartyPrivacy.Text", currentParty.privacy().getDisplayName());
            ui.set("#InviteSection.Visible", leader);
            ui.set("#PrivacyButton.Visible", leader);
            ui.set("#StartButton.Visible", canStart);
            ui.set("#TeleportRow.Visible", canTeleportToDungeon);
            ui.set("#DisbandButton.Visible", leader);
            ui.set("#LeaveButton.Visible", !leader);
            ui.set("#PrivacyButton.Text", currentParty.privacy() == PartyPrivacy.PUBLIC ? "MAKE PRIVATE" : "MAKE PUBLIC");

            bindClick(events, "#PrivacyButton", Action.TOGGLE_PRIVACY);
            bindClick(events, "#StartButton", Action.START);
            bindClick(events, "#TeleportButton", Action.TELEPORT);
            bindClick(events, "#DisbandButton", Action.DISBAND);
            bindClick(events, "#LeaveButton", Action.LEAVE);
            bindClickWithValue(events, "#InviteButton", Action.INVITE, "#InvitePlayerDropdown.Value");

            buildInviteCandidates(ui, inviteCandidates);
            buildMembers(ui, events, currentParty, leader);
        }

        buildInvites(ui, events, invites);
        buildPublicParties(ui, events, currentParty, publicParties);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull UiEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        Action action = Action.from(data.action);
        if (action == null) {
            refresh(ref, store);
            return;
        }

        if (action == Action.CLOSE) {
            player.getPageManager().setPage(ref, store, Page.None);
            return;
        }

        PartyManager manager = this.plugin.getPartyManager();
        PartyManager.ActionResult result = null;

        switch (action) {
            case REFRESH -> {
            }
            case CREATE_PUBLIC -> result = manager.createParty(this.playerRef, PartyPrivacy.PUBLIC);
            case CREATE_PRIVATE -> result = manager.createParty(this.playerRef, PartyPrivacy.PRIVATE);
            case TOGGLE_PRIVACY -> {
                PartyManager.PartySnapshot currentParty = manager.getPartySnapshot(this.playerRef.getUuid());
                if (currentParty == null) {
                    result = PartyManager.ActionResult.error("You are not in a party.");
                } else {
                    PartyPrivacy next = currentParty.privacy() == PartyPrivacy.PUBLIC ? PartyPrivacy.PRIVATE : PartyPrivacy.PUBLIC;
                    result = manager.setPrivacy(this.playerRef, next);
                }
            }
            case INVITE -> result = manager.invite(this.playerRef, safeValue(data.value));
            case JOIN -> result = manager.joinParty(this.playerRef, safeValue(data.targetId));
            case KICK -> result = manager.kick(this.playerRef, safeValue(data.targetId));
            case LEAVE -> result = manager.leave(this.playerRef);
            case DISBAND -> result = manager.disband(this.playerRef);
            case START -> result = manager.startParty(this.playerRef);
            case TELEPORT -> result = this.plugin.getGameManager().teleportPlayerToDungeon(this.playerRef);
            default -> {
            }
        }

        if (result != null) {
            Color color = result.success() ? Color.GREEN : Color.RED;
            player.sendMessage(PartyManager.partyPrefix().insert(Message.raw(result.message()).color(color)));
        }

        refresh(ref, store);
    }

    private void refresh(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        build(ref, ui, events, store);
        sendUpdate(ui, events, true);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        OPEN_PAGES.remove(this.playerRef.getUuid(), this);
    }

    private void refreshIfOpen() {
        Ref<EntityStore> ref = this.playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            OPEN_PAGES.remove(this.playerRef.getUuid(), this);
            return;
        }

        Store<EntityStore> store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) {
                OPEN_PAGES.remove(this.playerRef.getUuid(), this);
                return;
            }

            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || player.getPageManager().getCustomPage() != this) {
                OPEN_PAGES.remove(this.playerRef.getUuid(), this);
                return;
            }

            refresh(ref, store);
        });
    }

    private void buildMembers(@Nonnull UICommandBuilder ui,
                              @Nonnull UIEventBuilder events,
                              @Nonnull PartyManager.PartySnapshot currentParty,
                              boolean leader) {
        int index = 0;
        for (PartyManager.PartyMemberSnapshot member : currentParty.members()) {
            String itemPath = "#CurrentPartyMembers[" + index + "]";
            ui.append("#CurrentPartyMembers", MEMBER_ITEM_PATH);
            ui.set(itemPath + " #MemberName.Text", member.name());
            ui.set(itemPath + " #MemberMeta.Text", member.leader() ? "Leader" : "Party Member");
            ui.set(itemPath + " #KickButton.Visible", leader && !member.leader());
            if (leader && !member.leader()) {
                bindClickWithTarget(events, itemPath + " #KickButton", Action.KICK, member.name());
            }
            index++;
        }
    }

    private void buildInviteCandidates(@Nonnull UICommandBuilder ui,
                                       @Nonnull List<PartyManager.InviteCandidateSnapshot> inviteCandidates) {
        ui.set("#InvitePlayerDropdown.Visible", !inviteCandidates.isEmpty());
        ui.set("#InviteButton.Visible", !inviteCandidates.isEmpty());
        ui.set("#NoInviteCandidates.Visible", inviteCandidates.isEmpty());

        if (inviteCandidates.isEmpty()) {
            ui.set("#NoInviteCandidates.Text", "No online players are currently available to invite.");
            return;
        }

        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (PartyManager.InviteCandidateSnapshot candidate : inviteCandidates) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(candidate.name()), candidate.id()));
        }
        ui.set("#InvitePlayerDropdown.Entries", entries);
        ui.set("#InvitePlayerDropdown.Value", inviteCandidates.get(0).id());
    }

    private void buildInvites(@Nonnull UICommandBuilder ui,
                              @Nonnull UIEventBuilder events,
                              @Nonnull List<PartyManager.PartyInviteSnapshot> invites) {
        int index = 0;
        for (PartyManager.PartyInviteSnapshot invite : invites) {
            String itemPath = "#InviteList[" + index + "]";
            ui.append("#InviteList", PARTY_ITEM_PATH);
            ui.set(itemPath + " #PartyName.Text", invite.partyName());
            ui.set(itemPath + " #PartyMeta.Text", "Invited by " + invite.leaderName());
            ui.set(itemPath + " #PartyDetails.Text", "Expires in " + Math.max(1L, invite.remainingMs() / 1000L) + "s");
            ui.set(itemPath + " #JoinButton.Text", "ACCEPT INVITE");
            bindClickWithTarget(events, itemPath + " #JoinButton", Action.JOIN, invite.partyId());
            index++;
        }
    }

    private void buildPublicParties(@Nonnull UICommandBuilder ui,
                                    @Nonnull UIEventBuilder events,
                                    @Nullable PartyManager.PartySnapshot currentParty,
                                    @Nonnull List<PartyManager.PartySnapshot> publicParties) {
        int index = 0;
        String currentPartyId = currentParty != null ? currentParty.id() : null;
        for (PartyManager.PartySnapshot party : publicParties) {
            if (party.id().equals(currentPartyId)) {
                continue;
            }

            String itemPath = "#PublicPartyList[" + index + "]";
            ui.append("#PublicPartyList", PARTY_ITEM_PATH);
            ui.set(itemPath + " #PartyName.Text", party.name());
            ui.set(itemPath + " #PartyMeta.Text", "Leader: " + party.leaderName());
            ui.set(itemPath + " #PartyDetails.Text", party.memberCount() + " members  |  " + party.privacy().getDisplayName());
            ui.set(itemPath + " #JoinButton.Text", "JOIN PARTY");
            bindClickWithTarget(events, itemPath + " #JoinButton", Action.JOIN, party.id());
            index++;
        }
    }

    private void bindClick(@Nonnull UIEventBuilder events, @Nonnull String elementId, @Nonnull Action action) {
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                elementId,
                new EventData().put(UiEventData.KEY_ACTION, action.name()),
                false
        );
    }

    private void bindClickWithTarget(@Nonnull UIEventBuilder events,
                                     @Nonnull String elementId,
                                     @Nonnull Action action,
                                     @Nonnull String targetId) {
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                elementId,
                new EventData()
                        .put(UiEventData.KEY_ACTION, action.name())
                        .put(UiEventData.KEY_TARGET_ID, targetId),
                false
        );
    }

    private void bindClickWithValue(@Nonnull UIEventBuilder events,
                                    @Nonnull String elementId,
                                    @Nonnull Action action,
                                    @Nonnull String valueSelector) {
        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                elementId,
                new EventData()
                        .put(UiEventData.KEY_ACTION, action.name())
                        .append(UiEventData.KEY_VALUE, valueSelector),
                false
        );
    }

    private enum Action {
        CLOSE,
        REFRESH,
        CREATE_PUBLIC,
        CREATE_PRIVATE,
        TOGGLE_PRIVACY,
        INVITE,
        JOIN,
        KICK,
        LEAVE,
        DISBAND,
        TELEPORT,
        START;

        @Nullable
        static Action from(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    public static final class UiEventData {
        static final String KEY_ACTION = "Action";
        static final String KEY_TARGET_ID = "TargetId";
        static final String KEY_VALUE = "@Value";
        static final BuilderCodec<UiEventData> CODEC = BuilderCodec.<UiEventData>builder(UiEventData.class, UiEventData::new)
                .append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (data, value) -> data.action = value, data -> data.action).add()
                .append(new KeyedCodec<>(KEY_TARGET_ID, Codec.STRING), (data, value) -> data.targetId = value, data -> data.targetId).add()
                .append(new KeyedCodec<>(KEY_VALUE, Codec.STRING), (data, value) -> data.value = value, data -> data.value).add()
                .build();

        private String action;
        private String targetId;
        private String value;
    }
}