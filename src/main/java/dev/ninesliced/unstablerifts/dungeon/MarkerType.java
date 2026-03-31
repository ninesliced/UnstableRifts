package dev.ninesliced.unstablerifts.dungeon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Types of marker blocks that can appear inside prefabs.
 * The generator detects these by block name, removes them, and processes the position.
 */
public enum MarkerType {
    /**
     * Exit point / connection to the next room.
     */
    EXIT("Prefab_Spawner_Block"),
    /**
     * Possible mob spawn position (generation-time).
     */
    MOB_SPAWN_POINT("UnstableRifts_Mob_Spawn_Point"),
    /**
     * Runtime mob spawner (spawns mobs while players are nearby).
     */
    MOB_SPAWNER("UnstableRifts_Mob_Spawner"),
    /**
     * Portal entry — configurable marker for Next Level or Closest Exit behavior.
     */
    PORTAL("UnstableRifts_Portal"),
    /**
     * Portal exit — destination marker used by Closest Exit portals.
     */
    PORTAL_EXIT("UnstableRifts_Portal_Exit"),
    /**
     * Unified door marker — mode is saved via DoorData on the block.
     */
    DOOR("UnstableRifts_Door"),
    /**
     * Legacy key door marker — kept for backward compatibility.
     */
    DOOR_KEY("UnstableRifts_Door_Key"),
    /**
     * Legacy activator door marker — kept for backward compatibility.
     */
    DOOR_ACTIVATOR("UnstableRifts_Door_Activator"),
    /**
     * Mob activator — kill a specific mob to complete a challenge.
     */
    MOB_ACTIVATOR("UnstableRifts_Mob_Activator"),
    /**
     * Mob clear activator — kill all room mobs to complete a challenge.
     */
    MOB_CLEAR_ACTIVATOR("UnstableRifts_Mob_Clear_Activator"),
    /**
     * Activation zone — step into a zone to complete a challenge.
     */
    ACTIVATION_ZONE("UnstableRifts_Activation_Zone"),
    /**
     * Possible key spawn location.
     */
    KEY_SPAWNER("UnstableRifts_Key_Spawner"),
    /**
     * Lock room indicator — room locks on player entry, unlocks on clear.
     */
    LOCK_ROOM("UnstableRifts_Lock_Room"),
    /**
     * Water block marker.
     */
    WATER("UnstableRifts_Water"),
    /**
     * Tar block marker.
     */
    TAR("UnstableRifts_Tar"),
    /**
     * Poison block marker.
     */
    POISON("UnstableRifts_Poison"),
    /**
     * Lava block marker.
     */
    LAVA("UnstableRifts_Lava"),
    /**
     * Slime block marker.
     */
    SLIME("UnstableRifts_Slime"),
    /**
     * Red Slime block marker.
     */
    RED_SLIME("UnstableRifts_Red_Slime");

    private static final Map<String, MarkerType> BY_BLOCK_NAME = createLookup();

    private final String blockName;

    MarkerType(@Nonnull String blockName) {
        this.blockName = blockName;
    }

    /**
     * Returns the MarkerType for the given block name, or {@code null} if not a marker.
     */
    @Nullable
    public static MarkerType fromBlockName(@Nullable String name) {
        return name == null || name.isEmpty() ? null : BY_BLOCK_NAME.get(name);
    }

    @Nonnull
    private static Map<String, MarkerType> createLookup() {
        Map<String, MarkerType> lookup = new HashMap<>();
        for (MarkerType type : values()) {
            lookup.put(type.blockName, type);
        }
        return lookup;
    }

    @Nonnull
    public String getBlockName() {
        return blockName;
    }
}
