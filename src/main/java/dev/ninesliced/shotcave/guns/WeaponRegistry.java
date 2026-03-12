package dev.ninesliced.shotcave.guns;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.protocol.InteractionCooldown;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import dev.ninesliced.shotcave.interactions.GunValidationInteraction;
import dev.ninesliced.shotcave.interactions.HideAmmoHudInteraction;
import dev.ninesliced.shotcave.interactions.ReloadInteraction;
import dev.ninesliced.shotcave.interactions.UpdateAmmoHudInteraction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private WeaponRegistry() {}

    private static final String PACK_KEY = "ninesliced:Shotcave";
    private static final String MANIFEST_FILE = "weapon_registry.json";
    private static final Gson GSON = new Gson();

    private static final Map<String, Function<String, Interaction>> SHARED_FACTORIES = Map.of(
            "UpdateAmmoHud", UpdateAmmoHudInteraction::new,
            "HideAmmoHud", HideAmmoHudInteraction::new
    );

    // ── JSON data classes ──────────────────────────────────────────────

    private static final class Manifest {
        @SerializedName("sharedInteractions") List<SharedInteractionDef> sharedInteractions;
        @SerializedName("weapons") List<String> weapons;
    }

    private static final class SharedInteractionDef {
        @SerializedName("type") String type;
        @SerializedName("id") String id;
    }

    private static final class WeaponConfig {
        @SerializedName("primary") PrimaryDef primary;
        @SerializedName("reload") @Nullable ReloadDef reload;
        @SerializedName("damage") @Nullable DamageDef damage;
        @SerializedName("extraRoots") @Nullable List<String> extraRoots;
    }

    private static final class PrimaryDef {
        @SerializedName("id") String id;
        @SerializedName("cooldown") float cooldown;
        @SerializedName("requireAmmo") boolean requireAmmo;
        @SerializedName("shotId") String shotId;
        @SerializedName("reloadId") String reloadId;
    }

    private static final class ReloadDef {
        @SerializedName("id") String id;
        @SerializedName("cooldown") float cooldown;
        @SerializedName("runTime") float runTime;
        @SerializedName("amount") int amount;
        @SerializedName("nextId") String nextId;
        @SerializedName("worldSfx") String worldSfx;
        @SerializedName("localSfx") String localSfx;
        @SerializedName("animationId") String animationId;
    }

    private static final class DamageDef {
        @SerializedName("id") String id;
        @SerializedName("amount") float amount;
        @SerializedName("type") String type;
        @SerializedName("worldSfx") String worldSfx;
        @SerializedName("localSfx") String localSfx;
        @SerializedName("particleId") String particleId;
        @SerializedName("particleYOffset") float particleYOffset;
    }

    // ── Public entry point ─────────────────────────────────────────────

    public static void registerAll() {
        Manifest manifest = loadResource(MANIFEST_FILE, Manifest.class);

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
        for (String weaponFile : manifest.weapons) {
            WeaponConfig wc = loadResource(weaponFile, WeaponConfig.class);
            registerWeapon(wc, interactions, roots, registeredIds);
        }

        // Register all — interactions first, then roots (roots resolve interaction IDs)
        Interaction.getAssetStore().loadAssets(PACK_KEY, interactions);
        RootInteraction.getAssetStore().loadAssets(PACK_KEY, roots);
    }

    private static void registerWeapon(@Nonnull WeaponConfig wc,
                                       @Nonnull List<Interaction> interactions,
                                       @Nonnull List<RootInteraction> roots,
                                       @Nonnull Set<String> registeredIds) {
        PrimaryDef p = wc.primary;

        // GunValidate interaction + root
        if (registeredIds.add(p.id)) {
            interactions.add(new GunValidationInteraction(p.id, p.requireAmmo, p.shotId, p.reloadId));
            roots.add(new RootInteraction("Root_" + p.id,
                    new InteractionCooldown("Shoot", p.cooldown, false, null, false, false),
                    p.id));
        }

        // Reload interaction + root (deduplicated for shared reloads)
        ReloadDef r = wc.reload;
        if (r != null && registeredIds.add(r.id)) {
            interactions.add(new ReloadInteraction(r.id, r.amount, r.runTime,
                    r.nextId, r.worldSfx, r.localSfx, r.animationId));
            roots.add(new RootInteraction("Root_" + r.id,
                    new InteractionCooldown("Reload", r.cooldown, false, null, false, false),
                    r.id));
        }

        // Damage interaction + root (deduplicated for shared damage)
        DamageDef d = wc.damage;
        if (d != null && registeredIds.add(d.id)) {
            interactions.add(new ShotcaveDamageInteraction(
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
}
