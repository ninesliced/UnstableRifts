package dev.ninesliced.unstablerifts.guns;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.protocol.InteractionCooldown;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import dev.ninesliced.unstablerifts.interactions.GunValidationInteraction;
import dev.ninesliced.unstablerifts.interactions.HideAmmoHudInteraction;
import dev.ninesliced.unstablerifts.interactions.ReloadInteraction;
import dev.ninesliced.unstablerifts.interactions.UpdateAmmoHudInteraction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Generic weapon registration. Reads {@code weapon_registry.json} manifest and
 * per-weapon JSON configs, then generates all Root, GunValidate, Reload,
 * Projectile_Hit, and shared interactions programmatically.
 * <p>
 * Contains zero weapon-specific logic — all IDs, cooldowns, SFX, and damage
 * values are defined entirely in JSON.
 */
public final class WeaponRegistry {

    private static final String PACK_KEY = "ninesliced:UnstableRifts";
    private static final String MANIFEST_FILE = "weapon_registry.json";
    private static final Gson GSON = new Gson();
    private static final Map<String, Function<String, Interaction>> SHARED_FACTORIES = Map.of(
            "UpdateAmmoHud", UpdateAmmoHudInteraction::new,
            "HideAmmoHud", HideAmmoHudInteraction::new
    );

    private WeaponRegistry() {
    }

    // ── JSON data classes ──────────────────────────────────────────────

    public static void registerAll() {
        Manifest manifest = loadResource(MANIFEST_FILE, Manifest.class);
        List<WeaponConfig> weaponConfigs = new ArrayList<>();
        Map<String, Integer> reloadMaxAmmoById = new HashMap<>();

        for (String weaponFile : manifest.weapons) {
            WeaponConfig wc = loadResource(weaponFile, WeaponConfig.class);
            weaponConfigs.add(wc);
            if (wc.reload != null && wc.reload.id != null && wc.reload.maxAmmo != null && wc.reload.maxAmmo > 0) {
                reloadMaxAmmoById.putIfAbsent(wc.reload.id, wc.reload.maxAmmo);
            }
        }

        List<Interaction> interactions = new ArrayList<>();
        List<RootInteraction> roots = new ArrayList<>();
        Set<String> registeredIds = new HashSet<>();

        // Shared interactions (HUD singletons etc.)
        if (manifest.sharedInteractions != null) {
            for (SharedInteractionDef def : manifest.sharedInteractions) {
                if (registeredIds.add(def.id)) {
                    Function<String, Interaction> factory = SHARED_FACTORIES.get(def.type);
                    if (factory == null) {
                        throw new IllegalStateException("Unknown shared interaction type: " + def.type);
                    }
                    interactions.add(factory.apply(def.id));
                }
            }
        }

        // Per-weapon configs
        for (WeaponConfig wc : weaponConfigs) {
            registerWeapon(wc, interactions, roots, registeredIds, reloadMaxAmmoById);
            registerDefinition(wc, reloadMaxAmmoById);
        }

        // Register all — interactions first, then roots (roots resolve interaction IDs)
        Interaction.getAssetStore().loadAssets(PACK_KEY, interactions);
        RootInteraction.getAssetStore().loadAssets(PACK_KEY, roots);
    }

    private static void registerWeapon(@Nonnull WeaponConfig wc,
                                       @Nonnull List<Interaction> interactions,
                                       @Nonnull List<RootInteraction> roots,
                                       @Nonnull Set<String> registeredIds,
                                       @Nonnull Map<String, Integer> reloadMaxAmmoById) {
        PrimaryDef p = wc.primary;
        boolean isMelee = "MELEE".equalsIgnoreCase(wc.category);

        if (isMelee) {
            // Melee weapons: use vanilla Template_Weapon_Sword interaction chain
            // (Primary combo, Secondary guard, Ability1 vortexstrike).
            // Damage is customised via InteractionVars on the item JSON.
            // No custom Root or swing interaction needed.
        } else {
            // Ranged weapons: GunValidate interaction + root
            if (registeredIds.add(p.id)) {
                interactions.add(new GunValidationInteraction(p.id, p.requireAmmo, p.shotId, p.reloadId));
                roots.add(new RootInteraction("Root_" + p.id,
                        new InteractionCooldown("Shoot", p.cooldown, false, null, false, false),
                        p.id));
            }
        }

        // Reload interaction + root (deduplicated for shared reloads, ranged only)
        if (!isMelee) {
            ReloadDef r = wc.reload;
            if (r != null && registeredIds.add(r.id)) {
                int resolvedMaxAmmo = resolveReloadMaxAmmo(r, reloadMaxAmmoById);
                interactions.add(new ReloadInteraction(r.id, r.amount, r.runTime,
                        r.nextId, r.worldSfx, r.localSfx, r.animationId,
                        resolvedMaxAmmo));
                roots.add(new RootInteraction("Root_" + r.id,
                        new InteractionCooldown("Reload", r.cooldown, false, null, false, false),
                        r.id));
            }
        }

        // Damage interaction + root (deduplicated for shared damage)
        DamageDef d = wc.damage;
        if (d != null && registeredIds.add(d.id)) {
            interactions.add(new UnstableRiftsDamageInteraction(
                    d.id, d.amount, d.type,
                    d.worldSfx, d.localSfx,
                    d.particleId, d.particleYOffset));
            roots.add(new RootInteraction("Root_" + d.id, d.id));
        }

        // Extra pass-through roots (miss roots, chain damage roots, etc.)
        if (wc.extraRoots != null) {
            for (String extraId : wc.extraRoots) {
                if (registeredIds.add("Root_" + extraId)) {
                    roots.add(new RootInteraction("Root_" + extraId, extraId));
                }
            }
        }
    }

    private static void registerDefinition(@Nonnull WeaponConfig wc,
                                           @Nonnull Map<String, Integer> reloadMaxAmmoById) {
        WeaponCategory category = WeaponCategory.fromString(wc.category);
        DamageEffect locked = wc.lockedEffect != null
                ? DamageEffect.fromString(wc.lockedEffect)
                : DamageEffect.NONE;
        boolean effectLocked = wc.lockedEffect != null;
        WeaponRarity minRarity = wc.minRarity != null
                ? WeaponRarity.fromString(wc.minRarity)
                : WeaponRarity.BASIC;
        WeaponRarity maxRarity = wc.maxRarity != null
                ? WeaponRarity.fromString(wc.maxRarity)
                : WeaponRarity.UNIQUE;

        float baseDamage = wc.damage != null ? wc.damage.amount : 0;
        float baseCooldown = wc.primary.cooldown;
        int baseMaxAmmo = wc.reload != null ? resolveReloadMaxAmmo(wc.reload, reloadMaxAmmoById) : 0;
        int baseRange = wc.baseStats != null ? wc.baseStats.range : 0;
        double baseSpread = wc.baseStats != null ? wc.baseStats.spread : 0;
        int basePellets = wc.baseStats != null ? wc.baseStats.pellets : 1;
        float baseKnockback = wc.baseStats != null ? wc.baseStats.knockback : 0;
        int basePrecision = wc.baseStats != null && wc.baseStats.precision != null ? wc.baseStats.precision : -1;
        int baseMobHealth = wc.summoningStats != null ? wc.summoningStats.mobHealth : 0;
        int baseMobDamage = wc.summoningStats != null ? wc.summoningStats.mobDamage : 0;
        int baseMobLifetime = wc.summoningStats != null ? wc.summoningStats.mobLifetime : 0;

        WeaponDefinition def = new WeaponDefinition(
                wc.itemId, wc.displayName, category, locked, effectLocked, minRarity, maxRarity,
                wc.spawnWeight, baseDamage, baseCooldown, baseMaxAmmo,
                baseRange, baseSpread, basePellets, baseKnockback,
                baseMobHealth, baseMobDamage, baseMobLifetime, basePrecision
        );
        WeaponDefinitions.register(def);
    }

    private static int resolveReloadMaxAmmo(@Nonnull ReloadDef reload,
                                            @Nonnull Map<String, Integer> reloadMaxAmmoById) {
        if (reload.maxAmmo != null && reload.maxAmmo > 0) {
            return reload.maxAmmo;
        }

        Integer sharedMaxAmmo = reloadMaxAmmoById.get(reload.id);
        return sharedMaxAmmo != null ? sharedMaxAmmo : 0;
    }

    @Nonnull
    private static <T> T loadResource(@Nonnull String path, @Nonnull Class<T> type) {
        try (var is = WeaponRegistry.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Missing resource: " + path);
            }
            try (var reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                T result = GSON.fromJson(reader, type);
                if (result == null) {
                    throw new IllegalStateException("Failed to parse: " + path);
                }
                return result;
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to load " + path, e);
        }
    }

    private static final class Manifest {
        @SerializedName("sharedInteractions")
        List<SharedInteractionDef> sharedInteractions;
        @SerializedName("weapons")
        List<String> weapons;
    }

    private static final class SharedInteractionDef {
        @SerializedName("type")
        String type;
        @SerializedName("id")
        String id;
    }

    private static final class WeaponConfig {
        @SerializedName("itemId")
        String itemId;
        @SerializedName("displayName")
        String displayName;
        @SerializedName("category")
        String category;
        @SerializedName("lockedEffect")
        @Nullable
        String lockedEffect;
        @SerializedName("minRarity")
        @Nullable
        String minRarity;
        @SerializedName("maxRarity")
        @Nullable
        String maxRarity;
        @SerializedName("spawnWeight")
        int spawnWeight;
        @SerializedName("baseStats")
        @Nullable
        BaseStatsDef baseStats;
        @SerializedName("summoningStats")
        @Nullable
        SummoningStatsDef summoningStats;
        @SerializedName("primary")
        PrimaryDef primary;
        @SerializedName("reload")
        @Nullable
        ReloadDef reload;
        @SerializedName("damage")
        @Nullable
        DamageDef damage;
        @SerializedName("extraRoots")
        @Nullable
        List<String> extraRoots;
    }

    // ── Public entry point ─────────────────────────────────────────────

    private static final class BaseStatsDef {
        @SerializedName("range")
        int range;
        @SerializedName("spread")
        double spread;
        @SerializedName("pellets")
        int pellets;
        @SerializedName("knockback")
        float knockback;
        @SerializedName("precision")
        @Nullable
        Integer precision;
    }

    private static final class SummoningStatsDef {
        @SerializedName("mobHealth")
        int mobHealth;
        @SerializedName("mobDamage")
        int mobDamage;
        @SerializedName("mobLifetime")
        int mobLifetime;
    }

    private static final class PrimaryDef {
        @SerializedName("id")
        String id;
        @SerializedName("cooldown")
        float cooldown;
        @SerializedName("requireAmmo")
        boolean requireAmmo;
        @SerializedName("shotId")
        String shotId;
        @SerializedName("reloadId")
        String reloadId;
    }

    private static final class ReloadDef {
        @SerializedName("id")
        String id;
        @SerializedName("cooldown")
        float cooldown;
        @SerializedName("runTime")
        float runTime;
        @SerializedName("amount")
        int amount;
        @SerializedName("nextId")
        String nextId;
        @SerializedName("worldSfx")
        String worldSfx;
        @SerializedName("localSfx")
        String localSfx;
        @SerializedName("animationId")
        String animationId;
        @SerializedName("maxAmmo")
        @Nullable
        Integer maxAmmo;
    }

    private static final class DamageDef {
        @SerializedName("id")
        String id;
        @SerializedName("amount")
        float amount;
        @SerializedName("type")
        String type;
        @SerializedName("worldSfx")
        String worldSfx;
        @SerializedName("localSfx")
        String localSfx;
        @SerializedName("particleId")
        String particleId;
        @SerializedName("particleYOffset")
        float particleYOffset;
    }
}
