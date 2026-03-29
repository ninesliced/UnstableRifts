package dev.ninesliced.shotcave.dungeon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Types of marker blocks that can appear inside prefabs.
 * The generator detects these by block name, removes them, and processes the position.
 */
public enum MarkerType {
    /** Exit point / connection to the next room. */
    EXIT("Prefab_Spawner_Block"),
    /** Possible mob spawn position (generation-time). */
    MOB_SPAWN_POINT("Shotcave_Mob_Spawn_Point"),
    /** Runtime mob spawner (spawns mobs while players are nearby). */
    MOB_SPAWNER("Shotcave_Mob_Spawner"),
    /** Portal entry — configurable marker for Next Level or Closest Exit behavior. */
    PORTAL("Shotcave_Portal"),
    /** Portal exit — destination marker used by Closest Exit portals. */
    PORTAL_EXIT("Shotcave_Portal_Exit"),
    /** Unified door marker — mode is saved via DoorData on the block. */
    DOOR("Shotcave_Door"),
    /** Legacy key door marker — kept for backward compatibility. */
    DOOR_KEY("Shotcave_Door_Key"),
    /** Legacy activator door marker — kept for backward compatibility. */
    DOOR_ACTIVATOR("Shotcave_Door_Activator"),
    /** Mob activator — kill a specific mob to complete a challenge. */
    MOB_ACTIVATOR("Shotcave_Mob_Activator"),
    /** Mob clear activator — kill all room mobs to complete a challenge. */
    MOB_CLEAR_ACTIVATOR("Shotcave_Mob_Clear_Activator"),
    /** Activation zone — step into a zone to complete a challenge. */
    ACTIVATION_ZONE("Shotcave_Activation_Zone"),
    /** Possible key spawn location. */
    KEY_SPAWNER("Shotcave_Key_Spawner"),
    /** Lock room indicator — room locks on player entry, unlocks on clear. */
    LOCK_ROOM("Shotcave_Lock_Room"),
    /** Water block marker. */
    WATER("Shotcave_Water"),
    /** Tar block marker. */
    TAR("Shotcave_Tar"),
    /** Poison block marker. */
    POISON("Shotcave_Poison"),
    /** Lava block marker. */
    LAVA("Shotcave_Lava"),
    /** Slime block marker. */
    SLIME("Shotcave_Slime"),
    /** Red Slime block marker. */
    RED_SLIME("Shotcave_Red_Slime");

    private final String blockName;

    MarkerType(@Nonnull String blockName) {
        this.blockName = blockName;
    }

    @Nonnull
    public String getBlockName() {
        return blockName;
    }

    /**
     * Returns the MarkerType for the given block name, or {@code null} if not a marker.
     */
    @Nullable
    public static MarkerType fromBlockName(@Nullable String name) {
        if (name == null || name.isEmpty()) return null;
        for (MarkerType type : values()) {
            if (type.blockName.equals(name)) return type;
        }
        return null;
    }

    /**
     * Whether this marker type should be excluded from overlap detection
     * and replaced with air after pasting.
     */
    public boolean isRemovedAfterPaste() {
        return true; // All markers are removed after paste
    }
}
