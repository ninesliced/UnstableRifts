package dev.ninesliced.unstablerifts.tooltip;

import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemEntityConfig;
import com.hypixel.hytale.protocol.ItemTranslationProperties;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import dev.ninesliced.unstablerifts.armor.*;
import dev.ninesliced.unstablerifts.guns.WeaponRarity;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and caches virtual armor items with per-instance tooltips and
 * quality glow, mirroring the pattern from {@link WeaponVirtualItems}.
 */
public final class ArmorVirtualItems {

    private static final String SEPARATOR = "__sca_";

    private static final ConcurrentHashMap<String, ItemBase> ITEM_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Map<String, String>> TRANSLATION_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Set<String>> SENT_PER_PLAYER = new ConcurrentHashMap<>();
    /**
     * Maps each UnstableRifts armor set to a fixed Hytale armor tier for visuals.
     * Every piece of a given set shares the same look regardless of rarity.
     */
    private static final Map<String, String> SET_TIER = Map.of(
            "crystal", "Mithril",
            "vine", "Copper",
            "shale", "Iron",
            "bone", "Wood",
            "void", "Adamantite",
            "warden", "Steel"
    );

    private ArmorVirtualItems() {
    }

    /**
     * Returns the Hytale item ID whose model/texture/icon should be used
     * for a given armor set + slot combination.
     * E.g. ("crystal", CHEST) → "Armor_Mithril_Chest"
     */
    @Nonnull
    static String getHytaleArmorId(@Nonnull String setId,
                                   @Nonnull ArmorSlotType slot) {
        String tier = SET_TIER.getOrDefault(setId, "Iron");
        return "Armor_" + tier + "_" + slot.getHytaleSlotName();
    }

    public static boolean isVirtual(@Nonnull String itemId) {
        return itemId.contains(SEPARATOR);
    }

    @Nonnull
    public static String getBaseItemId(@Nonnull String virtualId) {
        int idx = virtualId.indexOf(SEPARATOR);
        return idx >= 0 ? virtualId.substring(0, idx) : virtualId;
    }

    @Nullable
    public static String processArmorSlot(@Nonnull UUID playerUuid,
                                          @Nonnull String baseItemId,
                                          @Nullable String metadataJson,
                                          @Nonnull Map<String, ItemBase> pendingItems,
                                          @Nonnull Map<String, String> pendingTranslations) {
        ArmorDefinition def = ArmorDefinitions.getById(baseItemId);
        if (def == null) return null;

        WeaponRarity rarity = WeaponRarity.BASIC;
        String setId = "";
        List<ArmorModifier> modifiers = Collections.emptyList();

        if (metadataJson != null && !metadataJson.isEmpty()) {
            try {
                BsonDocument doc = BsonDocument.parse(metadataJson);
                if (doc.containsKey(ArmorItemMetadata.RARITY_KEY)) {
                    rarity = WeaponRarity.fromOrdinal(doc.getInt32(ArmorItemMetadata.RARITY_KEY).getValue());
                }
                if (doc.containsKey(ArmorItemMetadata.SET_KEY)) {
                    setId = doc.getString(ArmorItemMetadata.SET_KEY).getValue();
                }
                if (doc.containsKey(ArmorItemMetadata.MODIFIERS_KEY)) {
                    BsonArray arr = doc.getArray(ArmorItemMetadata.MODIFIERS_KEY);
                    List<ArmorModifier> mods = new ArrayList<>(arr.size());
                    for (BsonValue v : arr) {
                        if (v.isDocument()) {
                            mods.add(ArmorModifier.fromBsonDocument(v.asDocument()));
                        }
                    }
                    modifiers = mods;
                }
            } catch (Exception ignored) {
            }
        }

        String hash = computeHash(baseItemId, rarity, setId, modifiers);
        String virtualId = baseItemId + SEPARATOR + hash;

        final WeaponRarity fRarity = rarity;
        final List<ArmorModifier> fMods = modifiers;

        ITEM_CACHE.computeIfAbsent(virtualId,
                k -> createItemBase(baseItemId, k, fRarity));
        TRANSLATION_CACHE.computeIfAbsent(virtualId,
                k -> createTranslations(k, def, fRarity, fMods));

        // If ItemBase creation failed (base item not in asset map), do NOT
        // rewrite the packet to use the virtual ID — the client has no
        // definition for it and would render the item as invalid/unknown.
        ItemBase base = ITEM_CACHE.get(virtualId);
        if (base == null) {
            return null;
        }

        Set<String> sent = SENT_PER_PLAYER.computeIfAbsent(playerUuid,
                k -> ConcurrentHashMap.newKeySet());

        if (sent.add(virtualId)) {
            pendingItems.put(virtualId, base);
            Map<String, String> tr = TRANSLATION_CACHE.get(virtualId);
            if (tr != null) {
                pendingTranslations.putAll(tr);
            }
        }

        return virtualId;
    }

    public static void onPlayerDisconnect(@Nonnull UUID uuid) {
        SENT_PER_PLAYER.remove(uuid);
    }

    @Nullable
    private static ItemBase createItemBase(@Nonnull String baseItemId,
                                           @Nonnull String virtualId,
                                           @Nonnull WeaponRarity rarity) {
        // Resolve the Hytale armor item whose model matches the rarity
        ArmorDefinition def = ArmorDefinitions.getById(baseItemId);
        String modelSourceId = baseItemId;
        if (def != null) {
            modelSourceId = getHytaleArmorId(def.setId(), def.slotType());
        }

        Item modelItem = Item.getAssetMap().getAsset(modelSourceId);
        if (modelItem == null) {
            // Fallback to the UnstableRifts base item
            modelItem = Item.getAssetMap().getAsset(baseItemId);
        }
        if (modelItem == null) return null;

        ItemBase clone = modelItem.toPacket().clone();
        clone.id = virtualId;
        clone.qualityIndex = ItemQuality.getAssetMap()
                .getIndexOrDefault(rarity.getQualityName(), 0);
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
        } else {
            ItemEntityConfig emptyConfig = new ItemEntityConfig();
            emptyConfig.showItemParticles = false;
            clone.itemEntity = emptyConfig;
        }
        return clone;
    }

    @Nonnull
    private static Map<String, String> createTranslations(@Nonnull String virtualId,
                                                          @Nonnull ArmorDefinition def,
                                                          @Nonnull WeaponRarity rarity,
                                                          @Nonnull List<ArmorModifier> modifiers) {
        Map<String, String> map = new HashMap<>(2);
        map.put(virtualId + ".n", buildName(def, rarity));
        map.put(virtualId + ".d", buildDescription(def, rarity, modifiers));
        return map;
    }

    @Nonnull
    static String buildName(@Nonnull ArmorDefinition def, @Nonnull WeaponRarity rarity) {
        StringBuilder sb = new StringBuilder();
        if (rarity != WeaponRarity.BASIC) {
            sb.append('[').append(rarity.name()).append("] ");
        }
        sb.append(def.displayName());
        return sb.toString();
    }

    @Nonnull
    static String buildDescription(@Nonnull ArmorDefinition def,
                                   @Nonnull WeaponRarity rarity,
                                   @Nonnull List<ArmorModifier> modifiers) {
        StringBuilder sb = new StringBuilder();

        // Header: rarity | slot | set
        sb.append(rarity.name());
        sb.append(" | ").append(def.slotType().name());
        sb.append(" | ").append(capitalize(def.setId())).append(" Set");

        // Set ability
        ArmorSetAbility ability = def.setAbility();
        if (ability != ArmorSetAbility.NONE) {
            sb.append("\nSet Ability: ").append(ability.getDisplayName())
                    .append(" (").append(ability.getDurationSeconds()).append("s)");
        }

        // Base stats
        appendStat(sb, "Protection", def.baseProtection(), modBonus(modifiers, ArmorModifierType.PROTECTION));
        if (def.baseCloseDmgReduce() > 0) {
            appendStatPercent(sb, "Close Def", def.baseCloseDmgReduce(), modBonus(modifiers, ArmorModifierType.CLOSE_DMG_REDUCE));
        }
        if (def.baseFarDmgReduce() > 0) {
            appendStatPercent(sb, "Far Def", def.baseFarDmgReduce(), modBonus(modifiers, ArmorModifierType.FAR_DMG_REDUCE));
        }
        if (def.baseKnockback() > 0) {
            appendStat(sb, "Knockback", def.baseKnockback(), modBonus(modifiers, ArmorModifierType.KNOCKBACK_ENEMIES));
        }
        if (def.baseSpikeDamage() > 0) {
            appendStat(sb, "Spike Dmg", def.baseSpikeDamage(), modBonus(modifiers, ArmorModifierType.SPIKE_DAMAGE));
        }
        if (def.baseLifeBoost() > 0) {
            appendStat(sb, "Max HP", def.baseLifeBoost(), modBonus(modifiers, ArmorModifierType.LIFE_BOOST));
        }
        if (def.baseSpeedBoost() > 0) {
            appendStatPercent(sb, "Speed", def.baseSpeedBoost(), modBonus(modifiers, ArmorModifierType.SPEED_BOOST));
        }

        // Modifiers
        if (!modifiers.isEmpty()) {
            sb.append("\n\nModifiers:");
            for (ArmorModifier mod : modifiers) {
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

    private static void appendStatPercent(@Nonnull StringBuilder sb, @Nonnull String label,
                                          double base, double bonus) {
        sb.append('\n').append(label).append(": ").append((int) Math.round(base * 100)).append('%');
        if (bonus > 0.001) {
            int abs = (int) Math.round(bonus * 100);
            if (abs > 0) sb.append(" (+").append(abs).append("%)");
        }
    }

    private static double modBonus(@Nonnull List<ArmorModifier> mods,
                                   @Nonnull ArmorModifierType type) {
        double t = 0;
        for (ArmorModifier m : mods) {
            if (m.type() == type) t += m.rolledValue();
        }
        return t;
    }

    @Nonnull
    private static String computeHash(@Nonnull String baseId,
                                      @Nonnull WeaponRarity rarity,
                                      @Nonnull String setId,
                                      @Nonnull List<ArmorModifier> modifiers) {
        long h = 8923L;
        h = 31L * h + baseId.hashCode();
        h = 31L * h + rarity.ordinal();
        h = 31L * h + setId.hashCode();
        for (ArmorModifier m : modifiers) {
            h = 31L * h + m.type().ordinal();
            h = 31L * h + Double.hashCode(m.rolledValue());
        }
        return Long.toHexString(h & 0x7fffffffffffffffL);
    }

    @Nonnull
    private static String capitalize(@Nonnull String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
