package dev.ninesliced.unstablerifts.guns;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.particle.config.WorldParticle;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.DamageEntityInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageCalculator;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageEffects;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.armor.ArmorAbilityBuffSystem;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Programmatically-constructed damage interaction that replaces the per-weapon
 * Projectile_Hit / Chain_Damage JSON files.
 */
public final class UnstableRiftsDamageInteraction extends DamageEntityInteraction {

    /**
     * Creates a damage interaction with the given parameters.
     *
     * @param id              Interaction ID (e.g. "UnstableRifts_Pistol_Projectile_Hit")
     * @param damage          Base damage amount
     * @param damageType      Damage cause ID (e.g. "Projectile", "Physical")
     * @param worldSfx        Hit sound (world), may be null
     * @param localSfx        Hit sound (local), may be null
     * @param particleId      Hit particle system ID, may be null
     * @param particleYOffset Y offset for the hit particle
     */
    public UnstableRiftsDamageInteraction(
            @Nonnull String id,
            float damage,
            @Nonnull String damageType,
            @Nullable String worldSfx,
            @Nullable String localSfx,
            @Nullable String particleId,
            float particleYOffset
    ) {
        super();
        this.id = id;

        // Build damage calculator (protected fields on DamageCalculator are accessible
        // from UnstableRiftsDamageCalculator inner class)
        this.damageCalculator = new UnstableRiftsDamageCalculator(damage, damageType);

        // Build damage effects using the public constructor
        WorldParticle[] particles = null;
        if (particleId != null) {
            particles = new WorldParticle[]{
                    new WorldParticle(
                            particleId,
                            null,
                            1.0f,
                            particleYOffset != 0.0f
                                    ? new com.hypixel.hytale.protocol.Vector3f(0.0f, particleYOffset, 0.0f)
                                    : null,
                            null
                    )
            };
        }

        this.damageEffects = new DamageEffects(
                null,       // modelParticles
                particles,  // worldParticles
                localSfx,   // localSoundEventId
                worldSfx,   // worldSoundEventId
                75.0,       // viewDistance
                null        // knockback
        );
    }

    /**
     * Reads the WEAPON_DAMAGE modifier from the forked context's held item BSON
     * and sets the ThreadLocal damage multiplier synchronously before super.tick0()
     * calls calculateDamage(). This fixes the async fork issue where the ThreadLocal
     * set in ModularGunShootInteraction was already cleaned up by the time the
     * forked damage chain executed.
     */
    @Override
    protected void tick0(boolean firstRun, float time,
                         @Nonnull InteractionType type,
                         @Nonnull InteractionContext context,
                         @Nonnull CooldownHandler cooldownHandler) {
        ItemStack heldItem = context.getHeldItem();
        double multiplier = 1.0;
        if (heldItem != null) {
            double dmgBonus = GunItemMetadata.getModifierBonus(heldItem, WeaponModifierType.WEAPON_DAMAGE);
            multiplier += dmgBonus;
        }
        // Apply Berserker buff damage bonus
        Ref<EntityStore> entityRef = context.getEntity();
        if (entityRef != null && entityRef.isValid()) {
            multiplier *= ArmorAbilityBuffSystem.getDamageMultiplier(entityRef);
        }
        if (multiplier != 1.0) {
            GunItemMetadata.DAMAGE_MULTIPLIER.set(multiplier);
        }
        try {
            super.tick0(firstRun, time, type, context, cooldownHandler);
        } finally {
            GunItemMetadata.DAMAGE_MULTIPLIER.remove();
        }
    }

    /**
     * Custom DamageCalculator subclass to access protected fields.
     * Defers index resolution to {@link #calculateDamage} time, ensuring
     * DamageCause assets are loaded.
     */
    static final class UnstableRiftsDamageCalculator extends DamageCalculator {
        private boolean resolved = false;

        UnstableRiftsDamageCalculator(float damage, @Nonnull String damageType) {
            this.type = Type.ABSOLUTE;
            this.baseDamageRaw = new Object2FloatOpenHashMap<>();
            this.baseDamageRaw.put(damageType, damage);
            this.baseDamage = new Int2FloatOpenHashMap();
        }

        private void ensureResolved() {
            if (!resolved) {
                resolved = true;
                baseDamage.clear();
                for (var entry : baseDamageRaw.object2FloatEntrySet()) {
                    int index = DamageCause.getAssetMap().getIndex(entry.getKey());
                    baseDamage.put(index, entry.getFloatValue());
                }
            }
        }

        @Override
        public it.unimi.dsi.fastutil.objects.Object2FloatMap<DamageCause> calculateDamage(double runTime) {
            ensureResolved();
            Object2FloatMap<DamageCause> result = super.calculateDamage(runTime);

            double multiplier = GunItemMetadata.DAMAGE_MULTIPLIER.get();
            if (multiplier != 1.0) {
                Object2FloatMap<DamageCause> scaled = new Object2FloatOpenHashMap<>(result.size());
                for (Object2FloatMap.Entry<DamageCause> entry : result.object2FloatEntrySet()) {
                    scaled.put(entry.getKey(), entry.getFloatValue() * (float) multiplier);
                }
                return scaled;
            }

            return result;
        }
    }
}
