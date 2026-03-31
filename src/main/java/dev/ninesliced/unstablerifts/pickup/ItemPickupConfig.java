package dev.ninesliced.unstablerifts.pickup;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import dev.ninesliced.unstablerifts.logging.UnstableRiftsLog;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Pickup behavior config driven by item asset tags: "Pickup": ["FKey"] or
 * "Pickup": ["ScoreCollect"].
 */
public final class ItemPickupConfig {

    public static final String ITEM_PICKUP_INTERACTION_ID = "*CratePickup";
    public static final String INTERACTION_HINT = "server.interactionHints.cratePickup";

    // ── Tag values ──────────────────────────────────────────────────────────
    /**
     * Max distance (blocks) for F-key pickup.
     */
    public static final double ITEM_PICKUP_RADIUS = 1.2;
    /**
     * Max distance (blocks) for showing the pickup HUD.
     */
    public static final double HUD_PROXIMITY_RADIUS = ITEM_PICKUP_RADIUS;

    // ── Interaction ─────────────────────────────────────────────────────────
    /**
     * Base coin collect radius (blocks), overridable per-player.
     */
    public static final double DEFAULT_COIN_COLLECT_RADIUS = 1.0;
    private static final HytaleLogger LOGGER = UnstableRiftsLog.forModule("Pickup");

    // ── Proximity ───────────────────────────────────────────────────────────
    private static final String TAG_FKEY_PICKUP = "Pickup=FKey";
    private static final String TAG_SCORE_COLLECT = "Pickup=ScoreCollect";
    private static final ConcurrentHashMap<UUID, Double> PLAYER_COIN_RADIUS = new ConcurrentHashMap<>();

    // ── Per-player coin collect radius ──────────────────────────────────────

    private ItemPickupConfig() {
    }

    public static double getCoinCollectRadius(@Nonnull UUID playerUuid) {
        Double override = PLAYER_COIN_RADIUS.get(playerUuid);
        return override != null ? override : DEFAULT_COIN_COLLECT_RADIUS;
    }

    public static void setCoinCollectRadius(@Nonnull UUID playerUuid, double radius) {
        if (radius <= 0) {
            PLAYER_COIN_RADIUS.remove(playerUuid);
        } else {
            PLAYER_COIN_RADIUS.put(playerUuid, radius);
        }
    }

    public static void resetCoinCollectRadius(@Nonnull UUID playerUuid) {
        PLAYER_COIN_RADIUS.remove(playerUuid);
    }

    public static void clearAllCoinCollectRadii() {
        PLAYER_COIN_RADIUS.clear();
    }

    // ── Tag-based queries ───────────────────────────────────────────────────

    public static boolean isFKeyPickup(@Nonnull String itemId) {
        return hasItemTag(itemId, TAG_FKEY_PICKUP);
    }

    public static boolean isScoreCollect(@Nonnull String itemId) {
        return hasItemTag(itemId, TAG_SCORE_COLLECT);
    }

    public static boolean isTracked(@Nonnull String itemId) {
        return isFKeyPickup(itemId) || isScoreCollect(itemId);
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private static boolean hasItemTag(@Nonnull String itemId, @Nonnull String tagValue) {
        try {
            int tagIndex = AssetRegistry.getTagIndex(tagValue);
            if (tagIndex < 0) {
                return false;
            }
            Set<String> keys = Item.getAssetMap().getKeysForTag(tagIndex);
            return keys != null && keys.contains(itemId);
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).withCause(e).log("[ItemPickupConfig] hasItemTag exception");
            return false;
        }
    }
}
