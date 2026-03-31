package dev.ninesliced.unstablerifts.player;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.util.*;

/**
 * Collects live player references without depending on Universe#getPlayers().
 */
public final class OnlinePlayers {

    private OnlinePlayers() {
    }

    @Nonnull
    public static List<PlayerRef> snapshot() {
        Universe universe = Universe.get();
        if (universe == null) {
            return Collections.emptyList();
        }

        Map<UUID, PlayerRef> playersById = new LinkedHashMap<>();
        for (World world : universe.getWorlds().values()) {
            for (PlayerRef playerRef : world.getPlayerRefs()) {
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }
                playersById.putIfAbsent(playerRef.getUuid(), playerRef);
            }
        }

        return new ArrayList<>(playersById.values());
    }
}
