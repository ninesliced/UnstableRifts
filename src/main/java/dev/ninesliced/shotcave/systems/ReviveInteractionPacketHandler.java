package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.shotcave.ShotcavePacketIds;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReviveInteractionPacketHandler implements PlayerPacketWatcher {

    private static final long ACTIVE_TIMEOUT_MS = 750L;

    private static final Map<UUID, Long> activeReviveInputs = new ConcurrentHashMap<>();

    @Override
    public void accept(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (packet.getId() != ShotcavePacketIds.SYNC_INTERACTION_CHAINS) {
            return;
        }

        SyncInteractionChains interactionChains = (SyncInteractionChains) packet;
        SyncInteractionChain[] updates = interactionChains.updates;
        if (updates == null || updates.length == 0) {
            return;
        }

        boolean sawUseInteraction = false;

        for (SyncInteractionChain chain : updates) {
            if (chain.interactionType != InteractionType.Use) {
                continue;
            }

            sawUseInteraction = true;
        }

        if (!sawUseInteraction) {
            return;
        }

        activeReviveInputs.put(playerRef.getUuid(), System.currentTimeMillis());
    }

    public static boolean isInteractionActive(@Nonnull UUID playerId, long now) {
        Long lastInput = activeReviveInputs.get(playerId);
        if (lastInput == null) {
            return false;
        }

        if ((now - lastInput) > ACTIVE_TIMEOUT_MS) {
            activeReviveInputs.remove(playerId, lastInput);
            return false;
        }

        return true;
    }

    public static void clearInteraction(@Nonnull UUID playerId) {
        activeReviveInputs.remove(playerId);
    }
}