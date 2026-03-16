package dev.ninesliced.shotcave.party;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.dungeon.DungeonConfig;
import dev.ninesliced.shotcave.dungeon.DungeonInstanceService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PartyManager {

    private static final Duration INVITE_LIFETIME = Duration.ofMinutes(5);

    private final Shotcave plugin;
    private final Map<UUID, Party> parties = new LinkedHashMap<>();
    private final Map<UUID, UUID> partyByMember = new HashMap<>();
    private final Map<UUID, Map<UUID, PartyInvite>> invitesByPlayer = new HashMap<>();

    public PartyManager(@Nonnull Shotcave plugin) {
        this.plugin = plugin;
    }

    @Nonnull
    private static Transform resolveSpawnTransform(@Nonnull World world,
                                                   @Nonnull PlayerRef playerRef,
                                                   @Nonnull Store<EntityStore> store,
                                                   @Nonnull Ref<EntityStore> ref) {
        ISpawnProvider spawnProvider = world.getWorldConfig().getSpawnProvider();
        if (spawnProvider != null) {
            Transform spawn = spawnProvider.getSpawnPoint(world, playerRef.getUuid());
            if (spawn != null) {
                return spawn;
            }
        }

        TransformComponent transformComponent = store.getComponent(ref, TransformComponent.getComponentType());
        return transformComponent != null ? transformComponent.getTransform().clone() : new Transform(0.5, 100.0, 0.5, 0.0f, 0.0f, 0.0f);
    }

    @Nonnull
    private static String partyNameFor(@Nonnull PlayerRef playerRef) {
        return partyNameFor(playerRef.getUsername());
    }

    @Nonnull
    private static String partyNameFor(@Nonnull String leaderName) {
        return leaderName + "'s Party";
    }

    @Nonnull
    private static Message createInviteMessage(@Nonnull PlayerRef inviter, @Nonnull Party party) {
        return partyPrefix()
                .insert(Message.raw(inviter.getUsername() + " invited you to join " + party.name + ". ").color("#dce6ef"))
                .insert(Message.raw("Use /sc accept to join, or /sc party ui to open the party menu.").color("#58a6ff"));
    }

    @Nonnull
    public static Message partyPrefix() {
        return Message.raw("[Shotcave Party] ").color(new Color(202, 153, 76)).bold(true);
    }

    @Nonnull
    public synchronized ActionResult createParty(@Nonnull PlayerRef leaderRef, @Nonnull PartyPrivacy privacy) {
        if (getPartyInternal(leaderRef.getUuid()) != null) {
            return ActionResult.error("You are already in a party.");
        }

        Party party = new Party(UUID.randomUUID(), leaderRef.getUuid(), partyNameFor(leaderRef), privacy);
        party.members.put(leaderRef.getUuid(), leaderRef.getUsername());

        this.parties.put(party.id, party);
        this.partyByMember.put(leaderRef.getUuid(), party.id);
        PartyUiPage.refreshOpenPages();
        return ActionResult.success("Created a " + privacy.name().toLowerCase(Locale.ROOT) + " party.", party);
    }

    @Nonnull
    public synchronized ActionResult joinParty(@Nonnull PlayerRef playerRef, @Nonnull String selector) {
        Party currentParty = getPartyInternal(playerRef.getUuid());
        if (currentParty != null) {
            return ActionResult.error("You are already in a party.");
        }

        Party target = resolveParty(selector);
        if (target == null) {
            return ActionResult.error("Could not find a party for '" + selector + "'.");
        }

        if (target.members.containsKey(playerRef.getUuid())) {
            return ActionResult.error("You are already in that party.");
        }

        boolean invited = consumeInvite(playerRef.getUuid(), target.id);
        if (target.privacy == PartyPrivacy.PRIVATE && !invited) {
            return ActionResult.error("That party is private. You need an invite.");
        }

        target.members.put(playerRef.getUuid(), playerRef.getUsername());
        this.partyByMember.put(playerRef.getUuid(), target.id);
        broadcast(target, partyPrefix().insert(Message.raw(playerRef.getUsername() + " joined the party.").color("#dce6ef")));
        PartyUiPage.refreshOpenPages();
        return ActionResult.success("Joined " + target.name + ".", target);
    }

    @Nonnull
    public synchronized ActionResult acceptInvite(@Nonnull PlayerRef playerRef) {
        if (getPartyInternal(playerRef.getUuid()) != null) {
            return ActionResult.error("You are already in a party.");
        }

        PartyInvite invite = findLatestInvite(playerRef.getUuid());
        if (invite == null) {
            return ActionResult.error("You do not have any active party invites.");
        }

        return joinParty(playerRef, invite.partyId.toString());
    }

    @Nonnull
    public synchronized ActionResult invite(@Nonnull PlayerRef leaderRef, @Nonnull String targetName) {
        Party party = getPartyInternal(leaderRef.getUuid());
        if (party == null) {
            return ActionResult.error("Create a party first.");
        }
        if (!isLeader(party, leaderRef.getUuid())) {
            return ActionResult.error("Only the party leader can invite players.");
        }

        PlayerRef target = resolveOnlinePlayer(targetName);
        if (target == null) {
            return ActionResult.error("No online player matched '" + targetName + "'.");
        }
        if (target.getUuid().equals(leaderRef.getUuid())) {
            return ActionResult.error("You are already in your own party.");
        }
        if (party.members.containsKey(target.getUuid())) {
            return ActionResult.error(target.getUsername() + " is already in your party.");
        }
        if (getPartyInternal(target.getUuid()) != null) {
            return ActionResult.error(target.getUsername() + " is already in another party.");
        }

        this.invitesByPlayer
                .computeIfAbsent(target.getUuid(), ignored -> new HashMap<>())
                .put(party.id, new PartyInvite(party.id, leaderRef.getUuid(), System.currentTimeMillis() + INVITE_LIFETIME.toMillis()));

        target.sendMessage(createInviteMessage(leaderRef, party));
        PartyUiPage.refreshOpenPages();
        return ActionResult.success("Invited " + target.getUsername() + " to the party.", party);
    }

    @Nonnull
    public synchronized ActionResult kick(@Nonnull PlayerRef leaderRef, @Nonnull String targetName) {
        Party party = getPartyInternal(leaderRef.getUuid());
        if (party == null) {
            return ActionResult.error("Create a party first.");
        }
        if (!isLeader(party, leaderRef.getUuid())) {
            return ActionResult.error("Only the party leader can remove members.");
        }

        UUID targetId = findMemberUuid(party, targetName);
        if (targetId == null) {
            return ActionResult.error("No party member matched '" + targetName + "'.");
        }
        if (targetId.equals(leaderRef.getUuid())) {
            return ActionResult.error("Use leave or disband instead of removing yourself.");
        }

        String memberName = party.members.remove(targetId);
        this.plugin.getGameManager().onPlayerLeftParty(party.id, targetId);
        this.partyByMember.remove(targetId);
        removeInvite(targetId, party.id);

        PlayerRef targetRef = Universe.get().getPlayer(targetId);
        if (targetRef != null) {
            targetRef.sendMessage(partyPrefix().insert(Message.raw("You were removed from " + party.name + ".").color("#ffb0b0")));
        }
        broadcast(party, partyPrefix().insert(Message.raw(memberName + " was removed from the party.").color("#dce6ef")));
        PartyUiPage.refreshOpenPages();
        return ActionResult.success("Removed " + memberName + " from the party.", party);
    }

    @Nonnull
    public synchronized ActionResult setPrivacy(@Nonnull PlayerRef leaderRef, @Nonnull PartyPrivacy privacy) {
        Party party = getPartyInternal(leaderRef.getUuid());
        if (party == null) {
            return ActionResult.error("Create a party first.");
        }
        if (!isLeader(party, leaderRef.getUuid())) {
            return ActionResult.error("Only the party leader can change privacy.");
        }

        party.privacy = privacy;
        broadcast(party, partyPrefix().insert(Message.raw("Party privacy set to " + privacy.getDisplayName() + ".").color("#dce6ef")));
        PartyUiPage.refreshOpenPages();
        return ActionResult.success("Party privacy set to " + privacy.getDisplayName().toLowerCase(Locale.ROOT) + ".", party);
    }

    @Nonnull
    public synchronized ActionResult leave(@Nonnull PlayerRef playerRef) {
        Party party = getPartyInternal(playerRef.getUuid());
        if (party == null) {
            return ActionResult.error("You are not in a party.");
        }
        if (isLeader(party, playerRef.getUuid())) {
            return disband(playerRef);
        }

        String name = party.members.remove(playerRef.getUuid());
    this.plugin.getGameManager().onPlayerLeftParty(party.id, playerRef.getUuid());
        this.partyByMember.remove(playerRef.getUuid());
        removeInvite(playerRef.getUuid(), party.id);
        broadcast(party, partyPrefix().insert(Message.raw(name + " left the party.").color("#dce6ef")));
        PartyUiPage.refreshOpenPages();
        return ActionResult.success("You left the party.", party);
    }

    @Nonnull
    public synchronized ActionResult disband(@Nonnull PlayerRef leaderRef) {
        Party party = getPartyInternal(leaderRef.getUuid());
        if (party == null) {
            return ActionResult.error("You are not in a party.");
        }
        if (!isLeader(party, leaderRef.getUuid())) {
            return ActionResult.error("Only the party leader can disband the party.");
        }

        closePartyInternal(party, "The party was disbanded.", true);
        return ActionResult.success("Disbanded the party.");
    }

    public synchronized void closePartyForSystem(@Nonnull UUID partyId, @Nonnull String reason) {
        Party party = this.parties.get(partyId);
        if (party == null) {
            return;
        }
        closePartyInternal(party, reason, false);
    }

    @Nonnull
    public synchronized ActionResult startParty(@Nonnull PlayerRef leaderRef) {
        Party party = getPartyInternal(leaderRef.getUuid());
        if (party == null) {
            return ActionResult.error("Create a party first.");
        }
        if (!isLeader(party, leaderRef.getUuid())) {
            return ActionResult.error("Only the party leader can start the party.");
        }

        // Check if a game is already active for this party
        if (this.plugin.getGameManager().hasActiveGame(party.id)) {
            return ActionResult.error("A dungeon run is already in progress for this party.");
        }

        Ref<EntityStore> leaderEntity = leaderRef.getReference();
        if (leaderEntity == null || !leaderEntity.isValid()) {
            return ActionResult.error("Could not resolve the leader entity.");
        }

        DungeonConfig.LevelConfig levelConfig = this.plugin.getDungeonInstanceService().resolveLevel(null);
        if (levelConfig == null) {
            return ActionResult.error("No procedural dungeon levels are configured.");
        }

        Store<EntityStore> leaderStore = leaderEntity.getStore();
        World leaderWorld = leaderStore.getExternalData().getWorld();
        Transform leaderReturnPoint = DungeonInstanceService.captureReturnPoint(leaderStore, leaderEntity);
        List<PendingTeleport> membersToTeleport = new ArrayList<>();
        List<UUID> memberIds = new ArrayList<>();
        Map<UUID, PlayerRef> memberRefsMap = new java.util.HashMap<>();
        Map<UUID, Ref<EntityStore>> memberEntitiesMap = new java.util.HashMap<>();
        Map<UUID, Store<EntityStore>> memberStoresMap = new java.util.HashMap<>();

        for (UUID memberId : party.members.keySet()) {
            PlayerRef memberRef = Universe.get().getPlayer(memberId);
            if (memberRef == null) {
                continue;
            }

            Ref<EntityStore> memberEntity = memberRef.getReference();
            if (memberEntity == null || !memberEntity.isValid()) {
                continue;
            }

            Store<EntityStore> memberStore = memberEntity.getStore();
            memberIds.add(memberId);
            memberRefsMap.put(memberId, memberRef);
            memberEntitiesMap.put(memberId, memberEntity);
            memberStoresMap.put(memberId, memberStore);

            // Only capture return point if the member is in the same world as the
            // leader — Store.getComponent() must be called from the owning thread.
            // Members in a different world will use the leader's return point instead.
            World memberWorld = memberStore.getExternalData().getWorld();
            Transform memberReturnPoint = (memberWorld == leaderWorld)
                    ? DungeonInstanceService.captureReturnPoint(memberStore, memberEntity)
                    : leaderReturnPoint;

            membersToTeleport.add(new PendingTeleport(
                    memberRef,
                    memberEntity,
                    memberStore,
                    memberReturnPoint
            ));
        }

        if (membersToTeleport.isEmpty()) {
            return ActionResult.error("No online party members are available to start the run.");
        }

        // Start the game via GameManager
        var gameManager = this.plugin.getGameManager();
        var gameFuture = gameManager.startGame(
                party.id, memberIds, memberRefsMap, memberEntitiesMap, memberStoresMap,
                leaderWorld, leaderReturnPoint
        );

        // When the game is ready, teleport all members and start the game loop
        gameFuture.thenAccept(game -> {
            // Transfer the generated Level to the Game if available
            var generatedLevel = this.plugin.getDungeonInstanceService().getLastGeneratedLevel();
            if (generatedLevel != null && game.getLevels().size() == 1) {
                // Replace the placeholder level with the one that has full RoomData graph
                game.getLevels(); // already has one
                // The Level is already added, but we need to merge the room data
                var existingLevel = game.getCurrentLevel();
                if (existingLevel != null && existingLevel.getRooms().isEmpty()) {
                    for (var room : generatedLevel.getRooms()) {
                        existingLevel.addRoom(room);
                    }
                }
            }

            World instanceWorld = game.getInstanceWorld();
            if (instanceWorld == null) return;

            for (PendingTeleport member : membersToTeleport) {
                this.plugin.getCameraService().scheduleEnableOnNextReady(member.playerRef());
                this.plugin.getDungeonInstanceService().sendPlayerToReadyInstance(
                        member.entityRef(),
                        member.store(),
                        java.util.concurrent.CompletableFuture.completedFuture(instanceWorld),
                        member.returnPoint(),
                        status -> {
                            this.plugin.getCameraService().cancelDeferredEnable(member.playerRef());
                            member.playerRef().sendMessage(partyPrefix().insert(Message.raw(status).color("#ffb0b0")));
                        }
                );
            }

            // Start the game after a short delay to let players load in
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(
                    () -> {
                        if (game.getInstanceWorld() != null) {
                            game.getInstanceWorld().execute(() -> gameManager.onGameStart(game));
                        }
                    },
                    3, java.util.concurrent.TimeUnit.SECONDS
            );
        }).exceptionally(throwable -> {
            broadcast(party, partyPrefix().insert(Message.raw("Failed to start dungeon: " + throwable.getMessage()).color("#ffb0b0")));
            return null;
        });

        broadcast(party, partyPrefix().insert(Message.raw("Starting a fresh Shotcave dungeon instance...").color("#dce6ef")));
        return ActionResult.success("Launching a new generated dungeon instance for the party.", party);
    }

    public synchronized void handleDisconnect(@Nonnull PlayerRef playerRef) {
        this.invitesByPlayer.remove(playerRef.getUuid());

        Party party = getPartyInternal(playerRef.getUuid());
        if (party == null) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        boolean wasLeader = isLeader(party, playerId);
        String memberName = party.members.remove(playerId);
        this.partyByMember.remove(playerId);
        removeInvite(playerId, party.id);

        if (party.members.isEmpty()) {
            this.parties.remove(party.id);
            PartyUiPage.refreshOpenPages();
            return;
        }

        if (wasLeader) {
            Map.Entry<UUID, String> nextLeader = party.members.entrySet().iterator().next();
            party.leaderId = nextLeader.getKey();
            party.leaderName = nextLeader.getValue();
            party.name = partyNameFor(nextLeader.getValue());
            broadcast(party, partyPrefix().insert(Message.raw(memberName + " disconnected. " + party.leaderName + " is now the party leader.").color("#dce6ef")));
        } else {
            broadcast(party, partyPrefix().insert(Message.raw(memberName + " disconnected and left the party.").color("#dce6ef")));
        }

        PartyUiPage.refreshOpenPages();
    }

    @Nullable
    public synchronized PartySnapshot getPartySnapshot(@Nonnull UUID playerId) {
        Party party = getPartyInternal(playerId);
        return party == null ? null : snapshot(party);
    }

    @Nonnull
    public synchronized List<PartySnapshot> getPublicPartySnapshots() {
        purgeExpiredInvites();
        List<PartySnapshot> snapshots = new ArrayList<>();
        for (Party party : this.parties.values()) {
            if (party.privacy == PartyPrivacy.PUBLIC) {
                snapshots.add(snapshot(party));
            }
        }
        snapshots.sort(Comparator.comparing(PartySnapshot::leaderName, String.CASE_INSENSITIVE_ORDER));
        return snapshots;
    }

    @Nonnull
    public synchronized List<PartyInviteSnapshot> getInviteSnapshots(@Nonnull UUID playerId) {
        purgeExpiredInvites();
        Map<UUID, PartyInvite> invites = this.invitesByPlayer.get(playerId);
        if (invites == null || invites.isEmpty()) {
            return Collections.emptyList();
        }

        List<PartyInviteSnapshot> snapshots = new ArrayList<>();
        for (PartyInvite invite : invites.values()) {
            Party party = this.parties.get(invite.partyId);
            if (party == null) {
                continue;
            }
            snapshots.add(new PartyInviteSnapshot(
                    party.id.toString(),
                    party.name,
                    party.leaderName,
                    Math.max(0L, invite.expiresAtEpochMs - System.currentTimeMillis())
            ));
        }
        snapshots.sort(Comparator.comparing(PartyInviteSnapshot::leaderName, String.CASE_INSENSITIVE_ORDER));
        return snapshots;
    }

    @Nonnull
    public synchronized List<InviteCandidateSnapshot> getInviteCandidates(@Nonnull UUID playerId) {
        Party party = getPartyInternal(playerId);
        if (party == null || !isLeader(party, playerId)) {
            return Collections.emptyList();
        }

        List<InviteCandidateSnapshot> candidates = new ArrayList<>();
        for (PlayerRef candidate : Universe.get().getPlayers()) {
            UUID candidateId = candidate.getUuid();
            if (candidateId.equals(playerId)) {
                continue;
            }
            if (party.members.containsKey(candidateId)) {
                continue;
            }
            if (getPartyInternal(candidateId) != null) {
                continue;
            }
            if (hasInvite(candidateId, party.id)) {
                continue;
            }

            candidates.add(new InviteCandidateSnapshot(candidateId.toString(), candidate.getUsername()));
        }

        candidates.sort(Comparator.comparing(InviteCandidateSnapshot::name, String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    @Nullable
    public synchronized Party resolvePartyById(@Nonnull UUID partyId) {
        return this.parties.get(partyId);
    }

    private void purgeExpiredInvites() {
        long now = System.currentTimeMillis();
        this.invitesByPlayer.values().removeIf(invites -> {
            invites.values().removeIf(invite -> invite.expiresAtEpochMs < now || !this.parties.containsKey(invite.partyId));
            return invites.isEmpty();
        });
    }

    @Nullable
    private Party getPartyInternal(@Nonnull UUID memberId) {
        purgeExpiredInvites();
        UUID partyId = this.partyByMember.get(memberId);
        return partyId == null ? null : this.parties.get(partyId);
    }

    @Nullable
    private Party resolveParty(@Nonnull String selector) {
        purgeExpiredInvites();
        try {
            Party byId = this.parties.get(UUID.fromString(selector));
            if (byId != null) {
                return byId;
            }
        } catch (IllegalArgumentException ignored) {
        }

        for (Party party : this.parties.values()) {
            if (party.leaderName.equalsIgnoreCase(selector) || party.name.equalsIgnoreCase(selector)) {
                return party;
            }
        }
        return null;
    }

    private boolean consumeInvite(@Nonnull UUID inviteeId, @Nonnull UUID partyId) {
        purgeExpiredInvites();
        Map<UUID, PartyInvite> invites = this.invitesByPlayer.get(inviteeId);
        if (invites == null) {
            return false;
        }
        PartyInvite removed = invites.remove(partyId);
        if (invites.isEmpty()) {
            this.invitesByPlayer.remove(inviteeId);
        }
        return removed != null;
    }

    private void removeInvite(@Nonnull UUID inviteeId, @Nonnull UUID partyId) {
        Map<UUID, PartyInvite> invites = this.invitesByPlayer.get(inviteeId);
        if (invites == null) {
            return;
        }
        invites.remove(partyId);
        if (invites.isEmpty()) {
            this.invitesByPlayer.remove(inviteeId);
        }
    }

    private boolean hasInvite(@Nonnull UUID inviteeId, @Nonnull UUID partyId) {
        purgeExpiredInvites();
        Map<UUID, PartyInvite> invites = this.invitesByPlayer.get(inviteeId);
        return invites != null && invites.containsKey(partyId);
    }

    private void closePartyInternal(@Nonnull Party party, @Nonnull String reason, boolean endActiveGame) {
        this.parties.remove(party.id);
        for (UUID memberId : new ArrayList<>(party.members.keySet())) {
            this.partyByMember.remove(memberId);
            removeInvite(memberId, party.id);
            PlayerRef member = Universe.get().getPlayer(memberId);
            if (member != null) {
                member.sendMessage(partyPrefix().insert(Message.raw(reason).color("#ffb0b0")));
            }
        }
        PartyUiPage.refreshOpenPages();

        if (endActiveGame) {
            this.plugin.getGameManager().onPartyDisband(party.id);
        }
    }

    @Nullable
    private PartyInvite findLatestInvite(@Nonnull UUID inviteeId) {
        purgeExpiredInvites();
        Map<UUID, PartyInvite> invites = this.invitesByPlayer.get(inviteeId);
        if (invites == null || invites.isEmpty()) {
            return null;
        }

        PartyInvite latestInvite = null;
        for (PartyInvite invite : invites.values()) {
            if (latestInvite == null || invite.expiresAtEpochMs > latestInvite.expiresAtEpochMs) {
                latestInvite = invite;
            }
        }
        return latestInvite;
    }

    @Nullable
    private PlayerRef resolveOnlinePlayer(@Nonnull String selector) {
        String normalized = selector.trim();
        if (normalized.isEmpty()) {
            return null;
        }

        try {
            PlayerRef byUuid = Universe.get().getPlayer(UUID.fromString(normalized));
            if (byUuid != null) {
                return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
        }

        PlayerRef startsWithMatch = null;
        for (PlayerRef candidate : Universe.get().getPlayers()) {
            String username = candidate.getUsername();
            if (username.equalsIgnoreCase(normalized)) {
                return candidate;
            }
            if (username.regionMatches(true, 0, normalized, 0, normalized.length())) {
                if (startsWithMatch != null) {
                    return null;
                }
                startsWithMatch = candidate;
            }
        }
        return startsWithMatch;
    }

    @Nullable
    private UUID findMemberUuid(@Nonnull Party party, @Nonnull String targetName) {
        String normalized = targetName.trim().toLowerCase(Locale.ROOT);
        UUID startsWithMatch = null;
        for (Map.Entry<UUID, String> entry : party.members.entrySet()) {
            String memberName = entry.getValue();
            if (memberName == null) {
                continue;
            }
            if (memberName.equalsIgnoreCase(normalized)) {
                return entry.getKey();
            }
            if (startsWithMatch == null && memberName.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                startsWithMatch = entry.getKey();
            }
        }
        return startsWithMatch;
    }

    private boolean isLeader(@Nonnull Party party, @Nonnull UUID playerId) {
        return party.leaderId.equals(playerId);
    }

    @Nonnull
    private PartySnapshot snapshot(@Nonnull Party party) {
        List<PartyMemberSnapshot> members = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : party.members.entrySet()) {
            members.add(new PartyMemberSnapshot(entry.getKey().toString(), entry.getValue(), entry.getKey().equals(party.leaderId)));
        }
        members.sort(Comparator.comparing(PartyMemberSnapshot::name, String.CASE_INSENSITIVE_ORDER));
        return new PartySnapshot(party.id.toString(), party.name, party.leaderId.toString(), party.leaderName, party.privacy, members);
    }

    private void broadcast(@Nonnull Party party, @Nonnull Message message) {
        for (UUID memberId : party.members.keySet()) {
            PlayerRef playerRef = Universe.get().getPlayer(memberId);
            if (playerRef != null) {
                playerRef.sendMessage(message);
            }
        }
    }

    private void sendStatusToPlayers(@Nonnull Collection<PendingTeleport> members,
                                     @Nonnull String text,
                                     @Nonnull String color) {
        Message message = partyPrefix().insert(Message.raw(text).color(color));
        for (PendingTeleport member : members) {
            member.playerRef().sendMessage(message);
        }
    }

    private static final class Party {
        private final UUID id;
        private final LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
        private UUID leaderId;
        private String name;
        private String leaderName;
        private PartyPrivacy privacy;

        private Party(@Nonnull UUID id, @Nonnull UUID leaderId, @Nonnull String name, @Nonnull PartyPrivacy privacy) {
            this.id = id;
            this.leaderId = leaderId;
            this.name = name;
            this.privacy = privacy;
            PlayerRef leader = Universe.get().getPlayer(leaderId);
            this.leaderName = leader != null ? leader.getUsername() : "Leader";
        }
    }

    private record PartyInvite(UUID partyId, UUID inviterId, long expiresAtEpochMs) {
    }

    private record PendingTeleport(PlayerRef playerRef,
                                   Ref<EntityStore> entityRef,
                                   Store<EntityStore> store,
                                   Transform returnPoint) {
    }

    public record PartySnapshot(String id,
                                String name,
                                String leaderId,
                                String leaderName,
                                PartyPrivacy privacy,
                                List<PartyMemberSnapshot> members) {
        public int memberCount() {
            return this.members.size();
        }
    }

    public record PartyMemberSnapshot(String id, String name, boolean leader) {
    }

    public record PartyInviteSnapshot(String partyId, String partyName, String leaderName, long remainingMs) {
    }

    public record InviteCandidateSnapshot(String id, String name) {
    }

    public static final class ActionResult {
        private final boolean success;
        private final String message;
        @Nullable
        private final Party party;

        private ActionResult(boolean success, @Nonnull String message, @Nullable Party party) {
            this.success = success;
            this.message = Objects.requireNonNull(message, "message");
            this.party = party;
        }

        @Nonnull
        public static ActionResult success(@Nonnull String message) {
            return new ActionResult(true, message, null);
        }

        @Nonnull
        public static ActionResult success(@Nonnull String message, @Nonnull Party party) {
            return new ActionResult(true, message, party);
        }

        @Nonnull
        public static ActionResult error(@Nonnull String message) {
            return new ActionResult(false, message, null);
        }

        public boolean success() {
            return this.success;
        }

        @Nonnull
        public String message() {
            return this.message;
        }

        @Nullable
        Party party() {
            return this.party;
        }
    }
}