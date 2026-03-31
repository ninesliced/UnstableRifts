package dev.ninesliced.unstablerifts.armor;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.hypixel.hytale.protocol.InteractionCooldown;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import dev.ninesliced.unstablerifts.guns.WeaponRarity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code armor_registry.json} manifest and per-armor JSON configs,
 * registers all {@link ArmorDefinition} objects and the shared
 * Ability1 interaction for armor set abilities.
 */
public final class ArmorRegistry {

    public static final String ABILITY_ROOT_ID = "Root_UnstableRifts_Armor_Ability";
    public static final String ABILITY_INTERACTION_ID = "UnstableRifts_Armor_Ability";
    private static final String PACK_KEY = "ninesliced:UnstableRifts";
    private static final String MANIFEST_FILE = "armor_registry.json";
    private static final float ABILITY_COOLDOWN_SECONDS = 30.0f;
    private static final Gson GSON = new Gson();

    private ArmorRegistry() {
    }

    // ── JSON data classes ──────────────────────────────────────────────

    public static void registerAll() {
        Manifest manifest = loadResource(MANIFEST_FILE, Manifest.class);

        for (String armorFile : manifest.armors) {
            ArmorConfig ac = loadResource(armorFile, ArmorConfig.class);
            registerDefinition(ac);
        }

        // Register the shared armor ability interaction and root
        List<Interaction> interactions = new ArrayList<>(1);
        List<RootInteraction> roots = new ArrayList<>(1);

        interactions.add(new ArmorAbilityInteraction(ABILITY_INTERACTION_ID));
        roots.add(new RootInteraction(ABILITY_ROOT_ID,
                new InteractionCooldown("ArmorAbility", ABILITY_COOLDOWN_SECONDS, false, null, false, false),
                ABILITY_INTERACTION_ID));

        Interaction.getAssetStore().loadAssets(PACK_KEY, interactions);
        RootInteraction.getAssetStore().loadAssets(PACK_KEY, roots);
    }

    private static void registerDefinition(@Nonnull ArmorConfig ac) {
        ArmorSlotType slotType = ArmorSlotType.fromString(ac.slotType);
        ArmorSetAbility setAbility = ArmorSetAbility.fromString(ac.setAbility);
        WeaponRarity minRarity = ac.minRarity != null
                ? WeaponRarity.fromString(ac.minRarity) : WeaponRarity.BASIC;
        WeaponRarity maxRarity = ac.maxRarity != null
                ? WeaponRarity.fromString(ac.maxRarity) : WeaponRarity.UNIQUE;

        float baseProtection = ac.baseStats != null ? ac.baseStats.protection : 0;
        float baseKnockback = ac.baseStats != null ? ac.baseStats.knockback : 0;
        float baseSpeedBoost = ac.baseStats != null ? ac.baseStats.speedBoost : 0;
        float baseCloseDmgReduce = ac.baseStats != null ? ac.baseStats.closeDmgReduce : 0;
        float baseFarDmgReduce = ac.baseStats != null ? ac.baseStats.farDmgReduce : 0;
        float baseSpikeDamage = ac.baseStats != null ? ac.baseStats.spikeDamage : 0;
        float baseLifeBoost = ac.baseStats != null ? ac.baseStats.lifeBoost : 0;

        ArmorDefinition def = new ArmorDefinition(
                ac.itemId, ac.displayName, ac.setId, slotType, setAbility,
                minRarity, maxRarity, ac.spawnWeight,
                baseProtection, baseKnockback, baseSpeedBoost,
                baseCloseDmgReduce, baseFarDmgReduce, baseSpikeDamage, baseLifeBoost
        );
        ArmorDefinitions.register(def);
    }

    @Nonnull
    private static <T> T loadResource(@Nonnull String path, @Nonnull Class<T> type) {
        try (var is = ArmorRegistry.class.getClassLoader().getResourceAsStream(path)) {
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

    // ── Public entry point ─────────────────────────────────────────────

    private static final class Manifest {
        @SerializedName("armors")
        List<String> armors;
    }

    private static final class ArmorConfig {
        @SerializedName("itemId")
        String itemId;
        @SerializedName("displayName")
        String displayName;
        @SerializedName("setId")
        String setId;
        @SerializedName("slotType")
        String slotType;
        @SerializedName("setAbility")
        String setAbility;
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
    }

    private static final class BaseStatsDef {
        @SerializedName("protection")
        float protection;
        @SerializedName("knockback")
        float knockback;
        @SerializedName("closeDmgReduce")
        float closeDmgReduce;
        @SerializedName("farDmgReduce")
        float farDmgReduce;
        @SerializedName("spikeDamage")
        float spikeDamage;
        @SerializedName("lifeBoost")
        float lifeBoost;
        @SerializedName("speedBoost")
        float speedBoost;
    }
}
