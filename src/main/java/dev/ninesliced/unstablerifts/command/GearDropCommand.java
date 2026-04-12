package dev.ninesliced.unstablerifts.command;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.armor.ArmorDefinition;
import dev.ninesliced.unstablerifts.armor.ArmorDefinitions;
import dev.ninesliced.unstablerifts.armor.ArmorItemMetadata;
import dev.ninesliced.unstablerifts.armor.ArmorLootRoller;
import dev.ninesliced.unstablerifts.armor.ArmorSlotType;
import dev.ninesliced.unstablerifts.guns.DamageEffect;
import dev.ninesliced.unstablerifts.guns.GunItemMetadata;
import dev.ninesliced.unstablerifts.guns.WeaponDefinition;
import dev.ninesliced.unstablerifts.guns.WeaponDefinitions;
import dev.ninesliced.unstablerifts.guns.WeaponLootRoller;
import dev.ninesliced.unstablerifts.guns.WeaponRarity;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

final class GearDropCommand {

    private static final SelectorArgumentType WEAPON_NAME_ARGUMENT_TYPE = new SelectorArgumentType(
            "weapon_name", "weapon name", GearDropCommand::weaponSuggestions, "cracked_staff");
    private static final SelectorArgumentType ARMOR_NAME_ARGUMENT_TYPE = new SelectorArgumentType(
            "armor_name", "armor name", GearDropCommand::armorSuggestions, "golem_helm");
    private static final SelectorArgumentType ARMOR_SET_NAME_ARGUMENT_TYPE = new SelectorArgumentType(
            "armor_set_name", "armor set name", GearDropCommand::armorSetSuggestions, "golem");
    private static final RarityArgumentType RARITY_ARGUMENT_TYPE = new RarityArgumentType();
    private static final List<String> RARITY_SUGGESTIONS = List.of(
            "common", "uncommon", "rare", "epic", "legendary", "unique");
    private static final Map<String, WeaponRarity> RARITY_ALIASES = Map.ofEntries(
            Map.entry("basic", WeaponRarity.BASIC),
            Map.entry("common", WeaponRarity.BASIC),
            Map.entry("uncommon", WeaponRarity.UNCOMMON),
            Map.entry("rare", WeaponRarity.RARE),
            Map.entry("epic", WeaponRarity.EPIC),
            Map.entry("legendary", WeaponRarity.LEGENDARY),
            Map.entry("unique", WeaponRarity.UNIQUE),
            Map.entry("developer", WeaponRarity.UNIQUE)
    );

    private GearDropCommand() {
    }

    @Nonnull
    static AbstractPlayerCommand weapon() {
        return new Weapon();
    }

    @Nonnull
    static AbstractPlayerCommand armor() {
        return new Armor();
    }

    @Nonnull
    static AbstractPlayerCommand armorSet() {
        return new ArmorSet();
    }

    private abstract static class GearCommand extends AbstractPlayerCommand {
        @Nonnull
        private final RequiredArg<String> nameArg;
        @Nullable
        private final RequiredArg<WeaponRarity> rarityArg;

        private GearCommand(@Nullable String name,
                            @Nonnull String description,
                            @Nonnull String nameDescription,
                            @Nonnull SingleArgumentType<String> nameArgumentType,
                            boolean withRarityArg) {
            super(name, description);
            this.nameArg = this.withRequiredArg("name", nameDescription, nameArgumentType);
            this.rarityArg = withRarityArg
                    ? this.withRequiredArg(
                    "rarity", "Rarity (common/uncommon/rare/epic/legendary/unique)", RARITY_ARGUMENT_TYPE)
                    : null;
        }

        @Nonnull
        protected String name(@Nonnull CommandContext context) {
            return this.nameArg.get(context);
        }

        @Nullable
        protected WeaponRarity rarity(@Nonnull CommandContext context) {
            return this.rarityArg == null ? null : this.rarityArg.get(context);
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected String generatePermissionNode() {
            return "";
        }
    }

    private abstract static class WeaponCommand extends GearCommand {
        private WeaponCommand(@Nullable String name, boolean withRarityArg) {
            super(name, "Spawn a specific weapon drop at your feet",
                    "Weapon name", WEAPON_NAME_ARGUMENT_TYPE, withRarityArg);
        }

        @Override
        protected final void execute(@Nonnull CommandContext context,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull Ref<EntityStore> ref,
                                     @Nonnull PlayerRef playerRef,
                                     @Nonnull World world) {
            WeaponDefinition def = resolveWeapon(name(context));
            if (def == null) {
                sendUnknown(context, "weapon", name(context), weaponSuggestions());
                return;
            }

            ItemStack stack = WeaponLootRoller.rollFor(def, rarity(context));
            DamageEffect effect = GunItemMetadata.getEffect(stack);
            WeaponRarity actualRarity = GunItemMetadata.getRarity(stack);
            int modifierCount = GunItemMetadata.getModifiers(stack).size();
            String message = String.format("Dropped %s %s with %d modifier(s).",
                    formatRarity(actualRarity), formatWeaponName(def, effect), modifierCount);

            dropItems(context, store, ref, world, List.of(stack), () ->
                    context.sendMessage(Message.raw(message).color(Color.GREEN)));
        }
    }

    private static final class Weapon extends WeaponCommand {
        private Weapon() {
            super("weapon", false);
            this.addUsageVariant(new WeaponWithRarity());
        }
    }

    private static final class WeaponWithRarity extends WeaponCommand {
        private WeaponWithRarity() {
            super(null, true);
        }
    }

    private abstract static class ArmorCommand extends GearCommand {
        private ArmorCommand(@Nullable String name, boolean withRarityArg) {
            super(name, "Spawn a specific armor drop at your feet",
                    "Armor name", ARMOR_NAME_ARGUMENT_TYPE, withRarityArg);
        }

        @Override
        protected final void execute(@Nonnull CommandContext context,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull Ref<EntityStore> ref,
                                     @Nonnull PlayerRef playerRef,
                                     @Nonnull World world) {
            ArmorDefinition def = resolveArmor(name(context));
            if (def == null) {
                sendUnknown(context, "armor", name(context), armorSuggestions());
                return;
            }

            ItemStack stack = ArmorLootRoller.rollFor(def, rarity(context));
            WeaponRarity actualRarity = ArmorItemMetadata.getRarity(stack);
            int modifierCount = ArmorItemMetadata.getModifiers(stack).size();
            String message = String.format("Dropped %s %s with %d modifier(s).",
                    formatRarity(actualRarity), def.displayName(), modifierCount);

            dropItems(context, store, ref, world, List.of(stack), () ->
                    context.sendMessage(Message.raw(message).color(Color.GREEN)));
        }
    }

    private static final class Armor extends ArmorCommand {
        private Armor() {
            super("armor", false);
            this.addUsageVariant(new ArmorWithRarity());
        }
    }

    private static final class ArmorWithRarity extends ArmorCommand {
        private ArmorWithRarity() {
            super(null, true);
        }
    }

    private abstract static class ArmorSetCommand extends GearCommand {
        private ArmorSetCommand(@Nullable String name, boolean withRarityArg) {
            super(name, "Spawn a full armor set at your feet",
                    "Armor set name", ARMOR_SET_NAME_ARGUMENT_TYPE, withRarityArg);
        }

        @Override
        protected final void execute(@Nonnull CommandContext context,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull Ref<EntityStore> ref,
                                     @Nonnull PlayerRef playerRef,
                                     @Nonnull World world) {
            String setId = resolveArmorSet(name(context));
            if (setId == null) {
                sendUnknown(context, "armor set", name(context), armorSetSuggestions());
                return;
            }

            List<ArmorDefinition> pieces = ArmorDefinitions.getBySetId(setId);
            if (pieces.isEmpty()) {
                sendUnknown(context, "armor set", name(context), armorSetSuggestions());
                return;
            }

            WeaponRarity specifiedRarity = rarity(context);
            WeaponRarity setRarity = specifiedRarity != null
                    ? specifiedRarity
                    : rollArmorSetRarity(pieces);
            List<ItemStack> stacks = new ArrayList<>(pieces.size());
            for (ArmorDefinition piece : pieces) {
                stacks.add(ArmorLootRoller.rollFor(piece, setRarity));
            }

            String message = String.format("Dropped %s %s armor set (%d pieces).",
                    formatArmorSetRarity(stacks), formatSetName(setId), stacks.size());
            dropItems(context, store, ref, world, stacks, () ->
                    context.sendMessage(Message.raw(message).color(Color.GREEN)));
        }
    }

    private static final class ArmorSet extends ArmorSetCommand {
        private ArmorSet() {
            super("armorset", false);
            this.addUsageVariant(new ArmorSetWithRarity());
        }
    }

    private static final class ArmorSetWithRarity extends ArmorSetCommand {
        private ArmorSetWithRarity() {
            super(null, true);
        }
    }

    private static final class SelectorArgumentType extends SingleArgumentType<String> {
        @Nonnull
        private final Supplier<Collection<String>> suggestions;

        private SelectorArgumentType(@Nonnull String name,
                                     @Nonnull String usage,
                                     @Nonnull Supplier<Collection<String>> suggestions,
                                     @Nonnull String... examples) {
            super("unstablerifts.command.arg." + name, Message.raw(usage), examples);
            this.suggestions = suggestions;
        }

        @Override
        @Nullable
        public String parse(@Nonnull String input, @Nonnull ParseResult parseResult) {
            if (toSelector(input).isBlank()) {
                parseResult.fail(Message.raw("Missing gear name.").color(Color.RED));
                return null;
            }
            return input;
        }

        @Override
        public void suggest(@Nonnull CommandSender sender,
                            @Nonnull String textAlreadyEntered,
                            int numParametersTyped,
                            @Nonnull SuggestionResult result) {
            suggestMatching(this.suggestions.get(), textAlreadyEntered, result);
        }

        @Override
        public int getSuggestionValueCount() {
            return this.suggestions.get().size();
        }
    }

    private static final class RarityArgumentType extends SingleArgumentType<WeaponRarity> {
        private RarityArgumentType() {
            super("unstablerifts.command.arg.rarity", Message.raw("common, uncommon, rare, epic, legendary, unique"),
                    "common", "rare", "legendary");
        }

        @Override
        @Nullable
        public WeaponRarity parse(@Nonnull String input, @Nonnull ParseResult parseResult) {
            WeaponRarity rarity = RARITY_ALIASES.get(toSelector(input));
            if (rarity == null) {
                parseResult.fail(Message.raw("Unknown rarity '" + input + "'. Use: "
                        + String.join(", ", RARITY_SUGGESTIONS) + ".").color(Color.RED));
            }
            return rarity;
        }

        @Override
        public void suggest(@Nonnull CommandSender sender,
                            @Nonnull String textAlreadyEntered,
                            int numParametersTyped,
                            @Nonnull SuggestionResult result) {
            suggestMatching(RARITY_SUGGESTIONS, textAlreadyEntered, result);
        }

        @Override
        public int getSuggestionValueCount() {
            return RARITY_SUGGESTIONS.size();
        }
    }

    private static void dropItems(@Nonnull CommandContext context,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull Ref<EntityStore> ref,
                                  @Nonnull World world,
                                  @Nonnull List<ItemStack> items,
                                  @Nonnull Runnable afterDrop) {
        world.execute(() -> {
            if (!ref.isValid()) {
                context.sendMessage(Message.raw("Could not resolve player reference.").color(Color.RED));
                return;
            }

            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                context.sendMessage(Message.raw("Could not resolve player position.").color(Color.RED));
                return;
            }

            Vector3d dropPosition = new Vector3d(transform.getPosition());
            Store<EntityStore> entityStore = world.getEntityStore().getStore();
            Holder<EntityStore>[] holders = ItemComponent.generateItemDrops(
                    entityStore, items, dropPosition, Rotation3f.ZERO);
            if (holders.length == 0) {
                context.sendMessage(Message.raw("No item drops were generated.").color(Color.RED));
                return;
            }

            entityStore.addEntities(holders, AddReason.SPAWN);
            afterDrop.run();
        });
    }

    @Nonnull
    private static List<String> weaponSuggestions() {
        Map<String, Integer> displayCounts = weaponDisplayCounts();
        List<String> suggestions = new ArrayList<>();
        for (WeaponDefinition def : WeaponDefinitions.getAll()) {
            suggestions.add(primaryWeaponSelector(def, displayCounts));
        }
        return suggestions;
    }

    @Nullable
    private static WeaponDefinition resolveWeapon(@Nonnull String input) {
        return weaponSelectorMap().get(toSelector(input));
    }

    @Nonnull
    private static Map<String, WeaponDefinition> weaponSelectorMap() {
        Map<String, Integer> displayCounts = weaponDisplayCounts();
        Map<String, WeaponDefinition> selectors = new LinkedHashMap<>();
        for (WeaponDefinition def : WeaponDefinitions.getAll()) {
            putSelector(selectors, primaryWeaponSelector(def, displayCounts), def);
            putSelector(selectors, def.itemId(), def);
            if (displayCounts.getOrDefault(def.displayName(), 0) > 1
                    && def.lockedEffect() == DamageEffect.ELECTRICITY) {
                putSelector(selectors, toSelector(def.displayName()) + "_electricity", def);
            }
        }
        return selectors;
    }

    @Nonnull
    private static String primaryWeaponSelector(@Nonnull WeaponDefinition def,
                                                @Nonnull Map<String, Integer> displayCounts) {
        String base = toSelector(def.displayName());
        if (displayCounts.getOrDefault(def.displayName(), 0) <= 1) {
            return base;
        }
        return base + "_" + effectSelector(def.lockedEffect());
    }

    @Nonnull
    private static Map<String, Integer> weaponDisplayCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WeaponDefinition def : WeaponDefinitions.getAll()) {
            counts.put(def.displayName(), counts.getOrDefault(def.displayName(), 0) + 1);
        }
        return counts;
    }

    @Nonnull
    private static List<String> armorSuggestions() {
        List<String> suggestions = new ArrayList<>();
        for (ArmorDefinition def : ArmorDefinitions.getAll()) {
            suggestions.add(primaryArmorSelector(def));
        }
        return suggestions;
    }

    @Nullable
    private static ArmorDefinition resolveArmor(@Nonnull String input) {
        return armorSelectorMap().get(toSelector(input));
    }

    @Nonnull
    private static Map<String, ArmorDefinition> armorSelectorMap() {
        Map<String, ArmorDefinition> selectors = new LinkedHashMap<>();
        for (ArmorDefinition def : ArmorDefinitions.getAll()) {
            putSelector(selectors, primaryArmorSelector(def), def);
            putSelector(selectors, def.itemId(), def);
            putSelector(selectors, def.setId() + "_" + slotSelector(def.slotType()), def);
            if (def.slotType() == ArmorSlotType.ARMS) {
                putSelector(selectors, def.setId() + "_hands", def);
            } else if (def.slotType() == ArmorSlotType.LEGS) {
                putSelector(selectors, def.setId() + "_boots", def);
            }
        }
        return selectors;
    }

    @Nonnull
    private static String primaryArmorSelector(@Nonnull ArmorDefinition def) {
        return toSelector(def.displayName());
    }

    @Nonnull
    private static List<String> armorSetSuggestions() {
        return ArmorDefinitions.getDistinctSetIds();
    }

    @Nullable
    private static String resolveArmorSet(@Nonnull String input) {
        String selector = toSelector(input);
        for (String setId : ArmorDefinitions.getDistinctSetIds()) {
            if (toSelector(setId).equals(selector) || toSelector(setId + "_set").equals(selector)) {
                return setId;
            }
        }
        return null;
    }

    private static <T> void putSelector(@Nonnull Map<String, T> selectors,
                                        @Nonnull String selector,
                                        @Nonnull T value) {
        selectors.putIfAbsent(toSelector(selector), value);
    }

    private static void suggestMatching(@Nonnull Collection<String> values,
                                        @Nonnull String entered,
                                        @Nonnull SuggestionResult result) {
        String prefix = toSelector(entered);
        for (String value : values) {
            if (prefix.isBlank() || value.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.suggest(value);
            }
        }
    }

    @Nonnull
    private static String toSelector(@Nonnull String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean previousUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
                previousUnderscore = false;
            } else if (!previousUnderscore) {
                out.append('_');
                previousUnderscore = true;
            }
        }

        int start = 0;
        int end = out.length();
        while (start < end && out.charAt(start) == '_') {
            start++;
        }
        while (end > start && out.charAt(end - 1) == '_') {
            end--;
        }
        return out.substring(start, end);
    }

    @Nonnull
    private static String effectSelector(@Nonnull DamageEffect effect) {
        return switch (effect) {
            case ELECTRICITY -> "lightning";
            case NONE -> "none";
            default -> effect.name().toLowerCase(Locale.ROOT);
        };
    }

    @Nonnull
    private static String slotSelector(@Nonnull ArmorSlotType slotType) {
        return switch (slotType) {
            case HEAD -> "head";
            case CHEST -> "chest";
            case ARMS -> "arms";
            case LEGS -> "legs";
        };
    }

    @Nonnull
    private static String formatWeaponName(@Nonnull WeaponDefinition def, @Nonnull DamageEffect effect) {
        if (effect == DamageEffect.NONE && !hasDuplicateWeaponDisplayName(def.displayName())) {
            return def.displayName();
        }
        return def.displayName() + " (" + formatEffect(effect) + ")";
    }

    private static boolean hasDuplicateWeaponDisplayName(@Nonnull String displayName) {
        return weaponDisplayCounts().getOrDefault(displayName, 0) > 1;
    }

    @Nonnull
    private static String formatEffect(@Nonnull DamageEffect effect) {
        return switch (effect) {
            case NONE -> "None";
            case ACID -> "Acid";
            case FIRE -> "Fire";
            case ICE -> "Ice";
            case ELECTRICITY -> "Lightning";
            case VOID -> "Void";
        };
    }

    @Nonnull
    private static String formatRarity(@Nonnull WeaponRarity rarity) {
        return switch (rarity) {
            case BASIC -> "Common";
            case UNCOMMON -> "Uncommon";
            case RARE -> "Rare";
            case EPIC -> "Epic";
            case LEGENDARY -> "Legendary";
            case UNIQUE -> "Unique";
        };
    }

    @Nonnull
    private static String formatArmorSetRarity(@Nonnull List<ItemStack> stacks) {
        Set<WeaponRarity> rarities = new LinkedHashSet<>();
        for (ItemStack stack : stacks) {
            rarities.add(ArmorItemMetadata.getRarity(stack));
        }
        if (rarities.size() == 1) {
            return formatRarity(rarities.iterator().next());
        }
        return "Mixed rarity";
    }

    @Nonnull
    private static WeaponRarity rollArmorSetRarity(@Nonnull List<ArmorDefinition> pieces) {
        WeaponRarity minimum = WeaponRarity.BASIC;
        WeaponRarity maximum = WeaponRarity.UNIQUE;
        for (ArmorDefinition piece : pieces) {
            if (piece.minRarity().ordinal() > minimum.ordinal()) {
                minimum = piece.minRarity();
            }
            if (piece.maxRarity().ordinal() < maximum.ordinal()) {
                maximum = piece.maxRarity();
            }
        }

        WeaponRarity rarity = WeaponRarity.roll(minimum);
        if (rarity.ordinal() > maximum.ordinal()) {
            return maximum;
        }
        return rarity;
    }

    @Nonnull
    private static String formatSetName(@Nonnull String setId) {
        if (setId.isBlank()) {
            return setId;
        }
        return setId.substring(0, 1).toUpperCase(Locale.ROOT) + setId.substring(1).toLowerCase(Locale.ROOT);
    }

    private static void sendUnknown(@Nonnull CommandContext context,
                                    @Nonnull String type,
                                    @Nonnull String input,
                                    @Nonnull Collection<String> suggestions) {
        List<String> matching = new ArrayList<>();
        String prefix = toSelector(input);
        for (String suggestion : suggestions) {
            if (matching.size() >= 8) {
                break;
            }
            if (prefix.isBlank() || suggestion.startsWith(prefix)) {
                matching.add(suggestion);
            }
        }

        String hint = matching.isEmpty()
                ? "Use autocomplete to see available " + type + " names."
                : "Try: " + String.join(", ", matching) + ".";
        context.sendMessage(Message.raw("Unknown " + type + " '" + input + "'. " + hint).color(Color.RED));
    }
}
