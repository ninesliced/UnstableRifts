package dev.ninesliced.shotcave.tooltip;

import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemEntityConfig;
import com.hypixel.hytale.protocol.ItemTranslationProperties;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import dev.ninesliced.shotcave.guns.DamageEffect;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.guns.WeaponCategory;
import dev.ninesliced.shotcave.guns.WeaponDefinition;
import dev.ninesliced.shotcave.guns.WeaponDefinitions;
import dev.ninesliced.shotcave.guns.WeaponModifier;
import dev.ninesliced.shotcave.guns.WeaponModifierType;
import dev.ninesliced.shotcave.guns.WeaponRarity;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and caches virtual weapon items with per-instance tooltips and
 * quality glow. Each unique combination of weapon + rarity + effect +
 * modifiers produces a distinct virtual item ID that the client renders
 * with a custom name, description, and quality colour.
 * <p>
 * The approach mirrors the technique used by DynamicTooltipsLib: clone the
 * base {@link ItemBase} from the real weapon, assign a unique virtual ID,
 * override {@code qualityIndex} and {@code translationProperties}, then
 * send the definition to the client via {@code UpdateItems} before the
 * inventory packet that references it.
 */
public final class WeaponVirtualItems {

    private static final String SEPARATOR = "__sc_";

    /** virtualId → cloned ItemBase */
    private static final ConcurrentHashMap<String, ItemBase> ITEM_CACHE = new ConcurrentHashMap<>();
    /** virtualId → {translationKey → translationValue} */
    private static final ConcurrentHashMap<String, Map<String, String>> TRANSLATION_CACHE = new ConcurrentHashMap<>();
    /** playerUuid → set of virtual IDs already sent to that player */
    private static final ConcurrentHashMap<UUID, Set<String>> SENT_PER_PLAYER = new ConcurrentHashMap<>();

    private WeaponVirtualItems() {}

    public static boolean isVirtual(@Nonnull String itemId) {
        return itemId.contains(SEPARATOR);
    }

    @Nonnull
    public static String getBaseItemId(@Nonnull String virtualId) {
        int idx = virtualId.indexOf(SEPARATOR);
        return idx >= 0 ? virtualId.substring(0, idx) : virtualId;
    }


    /**
     * Inspects a weapon slot from an outbound inventory packet.  If the
     * item is a recognised Shotcave weapon (has BSON metadata with rarity
     * etc.) a virtual item is created/cached and any items or translations
     * that the player has not yet received are collected into the pending
     * maps so the caller can batch‑send them.
     *
     * @return the virtual item ID, or {@code null} if the item is not a
     *         Shotcave weapon
     */
    @Nullable
    public static String processWeaponSlot(@Nonnull UUID playerUuid,
                                            @Nonnull String baseItemId,
                                            @Nullable String metadataJson,
                                            @Nonnull Map<String, ItemBase> pendingItems,
                                            @Nonnull Map<String, String> pendingTranslations) {

        WeaponDefinition def = WeaponDefinitions.getById(baseItemId);
        if (def == null) return null;

        WeaponRarity rarity = WeaponRarity.BASIC;
        DamageEffect effect = DamageEffect.NONE;
        List<WeaponModifier> modifiers = Collections.emptyList();

        if (metadataJson != null && !metadataJson.isEmpty()) {
            try {
                BsonDocument doc = BsonDocument.parse(metadataJson);
                if (doc.containsKey(GunItemMetadata.RARITY_KEY)) {
                    rarity = WeaponRarity.fromOrdinal(doc.getInt32(GunItemMetadata.RARITY_KEY).getValue());
                }
                if (doc.containsKey(GunItemMetadata.EFFECT_KEY)) {
                    effect = DamageEffect.fromOrdinal(doc.getInt32(GunItemMetadata.EFFECT_KEY).getValue());
                }
                if (doc.containsKey(GunItemMetadata.MODIFIERS_KEY)) {
                    BsonArray arr = doc.getArray(GunItemMetadata.MODIFIERS_KEY);
                    List<WeaponModifier> mods = new ArrayList<>(arr.size());
                    for (BsonValue v : arr) {
                        if (v.isDocument()) {
                            mods.add(WeaponModifier.fromBsonDocument(v.asDocument()));
                        }
                    }
                    modifiers = mods;
                }
            } catch (Exception ignored) {
                // Metadata not parseable — fall through with defaults
            }
        }


        String hash = computeHash(baseItemId, rarity, effect, modifiers);
        String virtualId = baseItemId + SEPARATOR + hash;


        final WeaponRarity fRarity = rarity;
        final DamageEffect fEffect = effect;
        final List<WeaponModifier> fMods = modifiers;

        ITEM_CACHE.computeIfAbsent(virtualId,
                k -> createItemBase(baseItemId, k, fRarity));
        TRANSLATION_CACHE.computeIfAbsent(virtualId,
                k -> createTranslations(k, def, fRarity, fEffect, fMods));


        Set<String> sent = SENT_PER_PLAYER.computeIfAbsent(playerUuid,
                k -> ConcurrentHashMap.newKeySet());

        if (sent.add(virtualId)) {
            ItemBase base = ITEM_CACHE.get(virtualId);
            if (base != null) {
                pendingItems.put(virtualId, base);
            }
            Map<String, String> tr = TRANSLATION_CACHE.get(virtualId);
            if (tr != null) {
                pendingTranslations.putAll(tr);
            }
        }

        return virtualId;
    }

    /** Clean up tracking state when a player disconnects. */
    public static void onPlayerDisconnect(@Nonnull UUID uuid) {
        SENT_PER_PLAYER.remove(uuid);
    }


    @Nullable
    private static ItemBase createItemBase(@Nonnull String baseItemId,
                                            @Nonnull String virtualId,
                                            @Nonnull WeaponRarity rarity) {
        Item baseItem = Item.getAssetMap().getAsset(baseItemId);
        if (baseItem == null) return null;

        ItemBase clone = baseItem.toPacket().clone();
        clone.id = virtualId;
        clone.qualityIndex = ItemQuality.getAssetMap()
                .getIndexOrDefault(rarity.getQualityName(), 0);
        // Prevent the virtual item from appearing in the creative inventory
        clone.variant = true;
        clone.translationProperties = new ItemTranslationProperties(
                virtualId + ".n",
                virtualId + ".d"
        );
        String particleId = rarity.getGlowEffectId();
        if (particleId != null) {
            ItemEntityConfig entityConfig = new ItemEntityConfig();
            entityConfig.particleSystemId = particleId;
            entityConfig.showItemParticles = true;
            clone.itemEntity = entityConfig;
        }
        return clone;
    }


    @Nonnull
    private static Map<String, String> createTranslations(@Nonnull String virtualId,
                                                           @Nonnull WeaponDefinition def,
                                                           @Nonnull WeaponRarity rarity,
                                                           @Nonnull DamageEffect effect,
                                                           @Nonnull List<WeaponModifier> modifiers) {
        Map<String, String> map = new HashMap<>(2);
        map.put(virtualId + ".n", buildName(def, rarity, effect));
        map.put(virtualId + ".d", buildDescription(def, rarity, effect, modifiers));
        return map;
    }


    @Nonnull
    static String buildName(@Nonnull WeaponDefinition def,
                             @Nonnull WeaponRarity rarity,
                             @Nonnull DamageEffect effect) {
        StringBuilder sb = new StringBuilder();
        if (rarity != WeaponRarity.BASIC) {
            sb.append('[').append(rarity.name()).append("] ");
        }
        if (effect != DamageEffect.NONE) {
            sb.append(effect.getDisplayName()).append(' ');
        }
        sb.append(def.getDisplayName());
        return sb.toString();
    }

    @Nonnull
    static String buildDescription(@Nonnull WeaponDefinition def,
                                    @Nonnull WeaponRarity rarity,
                                    @Nonnull DamageEffect effect,
                                    @Nonnull List<WeaponModifier> modifiers) {
        StringBuilder sb = new StringBuilder();

        sb.append(rarity.name());
        if (effect != DamageEffect.NONE) {
            sb.append(" | ").append(effect.getDisplayName());
        }
        sb.append(" | ").append(def.getCategory().name());

        WeaponCategory cat = def.getCategory();
        if (cat == WeaponCategory.SUMMONING) {
            appendStat(sb, "Mob HP", def.getBaseMobHealth(),
                    modBonus(modifiers, WeaponModifierType.MOB_HEALTH));
            appendStat(sb, "Mob Dmg", def.getBaseMobDamage(),
                    modBonus(modifiers, WeaponModifierType.MOB_DAMAGE));
            appendStat(sb, "Mob Life", def.getBaseMobLifetime(),
                    modBonus(modifiers, WeaponModifierType.MOB_LIFETIME));
        } else if (cat == WeaponCategory.MELEE) {
            appendStat(sb, "Damage", def.getBaseDamage(),
                    modBonus(modifiers, WeaponModifierType.WEAPON_DAMAGE));
            appendStatTime(sb, "Speed", def.getBaseCooldown(),
                    modBonus(modifiers, WeaponModifierType.ATTACK_SPEED));
            appendStat(sb, "Knockback", def.getBaseKnockback(),
                    modBonus(modifiers, WeaponModifierType.KNOCKBACK));
        } else {
            appendStat(sb, "Damage", def.getBaseDamage(),
                    modBonus(modifiers, WeaponModifierType.WEAPON_DAMAGE));
            appendStatTime(sb, "Speed", def.getBaseCooldown(),
                    modBonus(modifiers, WeaponModifierType.ATTACK_SPEED));
            appendStat(sb, "Range", def.getBaseRange(),
                    modBonus(modifiers, WeaponModifierType.MAX_RANGE));

            // Precision (inverse of spread, or explicit override from JSON)
            double baseSpread = def.getBaseSpread();
            double basePrecision = def.getBasePrecision() >= 0
                    ? def.getBasePrecision()
                    : Math.max(0, 100.0 - baseSpread * 10.0);
            double precBonus = modBonus(modifiers, WeaponModifierType.PRECISION);
            int bonusPts = (int) Math.round(baseSpread * precBonus * 10.0);
            sb.append("\nPrecision: ").append(String.format("%.0f%%", basePrecision));
            if (bonusPts > 0) sb.append(" (+").append(bonusPts).append(')');

            appendStat(sb, "Knockback", def.getBaseKnockback(),
                    modBonus(modifiers, WeaponModifierType.KNOCKBACK));

            if (def.getBasePellets() > 1) {
                double pelletBonus = modBonus(modifiers, WeaponModifierType.ADDITIONAL_BULLETS);
                sb.append("\nPellets: ").append(def.getBasePellets());
                if (pelletBonus >= 0.5) {
                    sb.append(" (+").append((int) Math.round(pelletBonus)).append(')');
                }
            }
        }

        if (cat != WeaponCategory.MELEE) {
            int baseMax = def.getBaseMaxAmmo();
            double bulletBonus = modBonus(modifiers, WeaponModifierType.MAX_BULLETS);
            int effectiveMax = Math.max(1, (int) Math.round(baseMax * (1.0 + bulletBonus)));
            int ammoBonusVal = effectiveMax - baseMax;
            sb.append("\nMax Ammo: ").append(baseMax);
            if (ammoBonusVal > 0) sb.append(" (+").append(ammoBonusVal).append(')');
        }
        if (!modifiers.isEmpty()) {
            sb.append("\n\nModifiers:");
            for (WeaponModifier mod : modifiers) {
                sb.append("\n  ").append(mod.type().getDisplayName()).append(' ');
                if (mod.rolledValue() >= 1.0) {
                    sb.append('+').append((int) Math.round(mod.rolledValue()));
                } else {
                    sb.append('+').append((int) Math.round(mod.rolledValue() * 100)).append('%');
                }
            }
        }

        return sb.toString();
    }


    private static void appendStat(@Nonnull StringBuilder sb, @Nonnull String label,
                                    double base, double bonus) {
        sb.append('\n').append(label).append(": ");
        sb.append(base == (int) base
                ? Integer.toString((int) base)
                : String.format("%.1f", base));
        if (bonus > 0.001) {
            int abs = (int) Math.round(base * bonus);
            if (abs > 0) sb.append(" (+").append(abs).append(')');
        }
    }

    private static void appendStatTime(@Nonnull StringBuilder sb, @Nonnull String label,
                                        double base, double bonus) {
        sb.append('\n').append(label).append(": ").append(String.format("%.2fs", base));
        if (bonus > 0.001) {
            double abs = base * bonus;
            if (abs > 0.005) {
                // Speed modifier reduces cooldown — show as negative
                sb.append(" (-").append(String.format("%.2fs", abs)).append(')');
            }
        }
    }

    private static double modBonus(@Nonnull List<WeaponModifier> mods,
                                    @Nonnull WeaponModifierType type) {
        double t = 0;
        for (WeaponModifier m : mods) {
            if (m.type() == type) t += m.rolledValue();
        }
        return t;
    }


    @Nonnull
    private static String computeHash(@Nonnull String baseId,
                                       @Nonnull WeaponRarity rarity,
                                       @Nonnull DamageEffect effect,
                                       @Nonnull List<WeaponModifier> modifiers) {
        long h = 7919L;
        h = 31L * h + baseId.hashCode();
        h = 31L * h + rarity.ordinal();
        h = 31L * h + effect.ordinal();
        for (WeaponModifier m : modifiers) {
            h = 31L * h + m.type().ordinal();
            h = 31L * h + Double.hashCode(m.rolledValue());
        }
        // Ensure positive
        return Long.toHexString(h & 0x7fffffffffffffffL);
    }
}
