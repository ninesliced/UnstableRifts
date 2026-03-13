package dev.ninesliced.shotcave.guns;

import com.hypixel.hytale.server.core.asset.type.particle.config.WorldParticle;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.DamageEntityInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageCalculator;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageEffects;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Programmatically-constructed damage interaction that replaces the per-weapon
 * Projectile_Hit / Chain_Damage JSON files.
 */
public final class ShotcaveDamageInteraction extends DamageEntityInteraction {

    /**
     * Creates a damage interaction with the given parameters.
     *
     * @param id             Interaction ID (e.g. "Shotcave_Pistol_Projectile_Hit")
     * @param damage         Base damage amount
     * @param damageType     Damage cause ID (e.g. "Projectile", "Physical")
     * @param worldSfx       Hit sound (world), may be null
     * @param localSfx       Hit sound (local), may be null
     * @param particleId     Hit particle system ID, may be null
     * @param particleYOffset Y offset for the hit particle
     */
    public ShotcaveDamageInteraction(
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
        // from ShotcaveDamageCalculator inner class)
        this.damageCalculator = new ShotcaveDamageCalculator(damage, damageType);

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
     * Custom DamageCalculator subclass to access protected fields.
     * Defers index resolution to {@link #calculateDamage} time, ensuring
     * DamageCause assets are loaded.
     */
    static final class ShotcaveDamageCalculator extends DamageCalculator {
        private boolean resolved = false;

        ShotcaveDamageCalculator(float damage, @Nonnull String damageType) {
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
            return super.calculateDamage(runTime);
        }
    }
}
