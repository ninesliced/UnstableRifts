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
    /** Portal entry — teleports the player to the linked exit. */
    PORTAL("Shotcave_Portal"),
    /** Portal exit — destination of a portal teleport. */
    PORTAL_EXIT("Shotcave_Portal_Exit"),
    /** Key door marker — placed before a treasure room. */
    KEY_DOOR("Shotcave_Key_Door"),
    /** Locked door marker — opened by an activator. */
    LOCKED_DOOR("Shotcave_Locked_Door"),
    /** Closed door marker — permanently sealed. */
    CLOSED_DOOR("Shotcave_Closed_Door"),
    /** Normal door — player can open/close freely. */
    DOOR("Shotcave_Door"),
    /** Trigger activator (lever, pressure plate, etc.). */
    TRIGGER_ACTIVATOR("Shotcave_Trigger_Activator"),
    /** Mob activator — kill a specific mob to open a door. */
    MOB_ACTIVATOR("Shotcave_Mob_Activator"),
    /** Mob clear activator — kill all room mobs to open a door. */
    MOB_CLEAR_ACTIVATOR("Shotcave_Mob_Clear_Activator"),
    /** Activation zone — step into a zone to open a door. */
    ACTIVATION_ZONE("Shotcave_Activation_Zone"),
    /** Possible key spawn location. */
    KEY_SPAWNER("Shotcave_Key_Spawner"),
    WATER("Shotcave_Water");

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

