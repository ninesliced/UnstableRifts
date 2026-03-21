package dev.ninesliced.shotcave;

/**
 * Shared packet ID constants used across multiple packet handlers and adapters.
 */
public final class ShotcavePacketIds {

    public static final int UPDATE_INVENTORY    = 170;
    public static final int ENTITY_UPDATES      = 161;
    public static final int SYNC_INTERACTION_CHAINS = 290;

    private ShotcavePacketIds() {
    }
}
