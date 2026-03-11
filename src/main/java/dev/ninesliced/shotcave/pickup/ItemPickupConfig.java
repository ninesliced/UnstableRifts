package dev.ninesliced.shotcave.pickup;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Pickup behavior config driven by item asset tags: "Pickup": ["FKey"] or
 * "Pickup": ["ScoreCollect"].
 */
public final class ItemPickupConfig {

    private static final Logger LOGGER = Logger.getLogger(ItemPickupConfig.class.getName());

    private ItemPickupConfig() {
    }

    // ── Tag values ──────────────────────────────────────────────────────────

    private static final String TAG_FKEY_PICKUP = "Pickup=FKey";
    private static final String TAG_SCORE_COLLECT = "Pickup=ScoreCollect";

    // ── Interaction ─────────────────────────────────────────────────────────

    public static final String ITEM_PICKUP_INTERACTION_ID = "*CratePickup";
    public static final String INTERACTION_HINT = "server.interactionHints.cratePickup";

    // ── Proximity ───────────────────────────────────────────────────────────

    /** Max distance (blocks) for F-key pickup. */
    public static final double ITEM_PICKUP_RADIUS = 1.2;

    /** Max distance (blocks) for showing the pickup HUD. */
    public static final double HUD_PROXIMITY_RADIUS = ITEM_PICKUP_RADIUS;

    /** Base coin collect radius (blocks), overridable per-player. */
    public static final double DEFAULT_COIN_COLLECT_RADIUS = 1.0;

    // ── Per-player coin collect radius ──────────────────────────────────────

    private static final ConcurrentHashMap<UUID, Double> PLAYER_COIN_RADIUS = new ConcurrentHashMap<>();

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
            LOGGER.info("[ItemPickupConfig] hasItemTag('" + itemId + "', '" + tagValue + "') tagIndex=" + tagIndex);
            if (tagIndex < 0) {
                LOGGER.info("[ItemPickupConfig]   tagIndex < 0 → false");
                return false;
            }
            Set<String> keys = Item.getAssetMap().getKeysForTag(tagIndex);
            boolean found = keys != null && keys.contains(itemId);
            LOGGER.info("[ItemPickupConfig]   keysForTag=" + (keys != null ? keys : "null") + " → " + found);
            return found;
        } catch (Exception e) {
            LOGGER.warning("[ItemPickupConfig] hasItemTag exception: " + e);
            return false;
        }
    }
}
