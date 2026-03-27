package dev.ninesliced.shotcave.armor;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS component tracking the passive 30-second charge timer for the armor
 * set ability. The charge fills from 0→600 ticks (30s at 20 TPS).
 * When full, the ability can be activated via Ability1.
 * Also tracks the active buff state (ability, remaining duration).
 */
public final class ArmorChargeComponent implements Component<EntityStore> {

    private static final int CHARGE_TICKS_MAX = 600; // 30 seconds at 20 TPS

    private static ComponentType<EntityStore, ArmorChargeComponent> componentType;

    private int chargeTicks;
    private boolean ready;

    // Active buff tracking
    private int activeAbilityOrdinal; // ArmorSetAbility ordinal, 0 = NONE
    private int buffRemainingTicks;

    public ArmorChargeComponent() {}

    public static void setComponentType(@Nonnull ComponentType<EntityStore, ArmorChargeComponent> type) {
        componentType = type;
    }

    @Nonnull
    public static ComponentType<EntityStore, ArmorChargeComponent> getComponentType() {
        if (componentType == null) {
            throw new IllegalStateException("ArmorChargeComponent has not been registered yet");
        }
        return componentType;
    }

    // ── Charge ─────────────────────────────────────────────────────────

    public int getChargeTicks() {
        return chargeTicks;
    }

    public int getChargeTicksMax() {
        return CHARGE_TICKS_MAX;
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * Advances charge by one tick. Returns true if charge just became ready.
     */
    public boolean advanceCharge() {
        if (ready) return false;
        chargeTicks++;
        if (chargeTicks >= CHARGE_TICKS_MAX) {
            chargeTicks = CHARGE_TICKS_MAX;
            ready = true;
            return true;
        }
        return false;
    }

    /**
     * Resets charge after ability activation.
     */
    public void reset() {
        chargeTicks = 0;
        ready = false;
    }

    /**
     * Returns charge progress as 0.0–1.0.
     */
    public float getChargeProgress() {
        return (float) chargeTicks / CHARGE_TICKS_MAX;
    }

    // ── Active buff ────────────────────────────────────────────────────

    @Nonnull
    public ArmorSetAbility getActiveAbility() {
        return ArmorSetAbility.fromOrdinal(activeAbilityOrdinal);
    }

    public boolean hasActiveBuff() {
        return activeAbilityOrdinal != 0 && buffRemainingTicks > 0;
    }

    public int getBuffRemainingTicks() {
        return buffRemainingTicks;
    }

    public void startBuff(@Nonnull ArmorSetAbility ability) {
        this.activeAbilityOrdinal = ability.ordinal();
        this.buffRemainingTicks = ability.getDurationTicks();
    }

    /**
     * Decrements buff timer by one tick. Returns true if buff just expired.
     */
    public boolean tickBuff() {
        if (buffRemainingTicks <= 0) return false;
        buffRemainingTicks--;
        return buffRemainingTicks <= 0;
    }

    public void clearBuff() {
        activeAbilityOrdinal = 0;
        buffRemainingTicks = 0;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        ArmorChargeComponent copy = new ArmorChargeComponent();
        copy.chargeTicks = this.chargeTicks;
        copy.ready = this.ready;
        copy.activeAbilityOrdinal = this.activeAbilityOrdinal;
        copy.buffRemainingTicks = this.buffRemainingTicks;
        return copy;
    }
}
