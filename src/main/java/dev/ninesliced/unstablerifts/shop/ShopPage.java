package dev.ninesliced.unstablerifts.shop;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.armor.*;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.guns.*;
import dev.ninesliced.unstablerifts.hud.AmmoHudService;
import dev.ninesliced.unstablerifts.inventory.InventoryLockService;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Interactive shop GUI page that displays all items for sale in a shop room.
 * Shows item details (weapon stats, armor stats) and allows purchasing with team money.
 */
public final class ShopPage extends InteractiveCustomUIPage<ShopPage.ShopEventData> {

    private static final String LAYOUT_PATH = "Pages/UnstableRifts/ShopPage.ui";
    private static final String ITEM_TEMPLATE = "Pages/UnstableRifts/ShopEntry.ui";
    private static final String ITEM_LIST_PATH = "#ShopItemList";

    private final Game game;
    private final ShopService.ShopRoomInventory inventory;
    private boolean confirmingPurchase = false;
    private int confirmIndex = -1;

    public ShopPage(@Nonnull PlayerRef playerRef,
                    @Nonnull Game game,
                    @Nonnull ShopService.ShopRoomInventory inventory) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ShopEventData.CODEC);
        this.game = game;
        this.inventory = inventory;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder ui,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        ui.append(LAYOUT_PATH);

        ui.set("#ShopTitle.TextSpans", Message.raw("SHOP"));
        ui.set("#ShopMoney.TextSpans", Message.raw("Coins: " + game.getMoney()));
        buildRefreshControls(ui, events);

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#ShopCloseBtn",
                new EventData().put(ShopEventData.KEY_ACTION, "CLOSE"),
                false
        );

        if (confirmingPurchase && confirmIndex >= 0 && confirmIndex < inventory.entries().size()) {
            buildConfirmPanel(ui, events);
        } else {
            ui.set("#ShopConfirmPanel.Visible", false);
            buildItemList(ui, events);
        }
    }

    private void buildRefreshControls(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events) {
        boolean enabled = inventory.hasRefreshesConfigured() && inventory.refreshesRemaining() > 0;
        Message buttonText = Message.raw(buildRefreshButtonText());
        ui.set("#ShopRefreshBtn.TextSpans", buttonText);
        ui.set("#ShopRefreshBtnDisabled.TextSpans", buttonText);
        ui.set("#ShopRefreshBtn.Visible", enabled);
        ui.set("#ShopRefreshBtnDisabled.Visible", !enabled);

        if (enabled) {
            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#ShopRefreshBtn",
                    new EventData().put(ShopEventData.KEY_ACTION, "REFRESH"),
                    false
            );
        }
    }

    private void buildItemList(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events) {
        ui.clear(ITEM_LIST_PATH);

        List<SortedShopEntry> sortedEntries = getSortedEntriesForDisplay();
        for (int displayIndex = 0; displayIndex < sortedEntries.size(); displayIndex++) {
            SortedShopEntry sortedEntry = sortedEntries.get(displayIndex);
            ShopEntry entry = sortedEntry.entry();
            int inventoryIndex = sortedEntry.inventoryIndex();
            String itemPath = ITEM_LIST_PATH + "[" + displayIndex + "]";

            ui.append(ITEM_LIST_PATH, ITEM_TEMPLATE);

            if (entry.isSold()) {
                ui.set(itemPath + " #ShopEntryName.TextSpans", Message.raw("SOLD"));
                ui.set(itemPath + " #ShopEntryName.Style.TextColor", "#666666");
                ui.set(itemPath + " #ShopEntryPrice.TextSpans", Message.raw("---"));
                ui.set(itemPath + " #ShopEntryDetails.Visible", false);
                continue;
            }

            ItemStack itemStack = entry.getItemStack();
            String displayName = buildItemDisplayName(entry, itemStack);
            String colorHex = getItemColorHex(entry, itemStack);

            ui.set(itemPath + " #ShopEntryName.TextSpans", Message.raw(displayName));
            ui.set(itemPath + " #ShopEntryName.Style.TextColor", colorHex);
            ui.set(itemPath + " #ShopEntryPrice.TextSpans", Message.raw(entry.getPrice() + " coins"));
            ui.set(itemPath + " #ShopEntryType.TextSpans", Message.raw(entry.getType().getDisplayName()));

            buildItemDetails(ui, itemPath, entry, itemStack);

            events.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    itemPath + " #ShopEntryBuyBtn",
                    new EventData()
                            .put(ShopEventData.KEY_ACTION, "BUY")
                            .put(ShopEventData.KEY_INDEX, String.valueOf(inventoryIndex)),
                    false
            );
        }
    }

    @Nonnull
    private List<SortedShopEntry> getSortedEntriesForDisplay() {
        List<ShopEntry> entries = inventory.entries();
        List<SortedShopEntry> sortedEntries = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            sortedEntries.add(new SortedShopEntry(i, entries.get(i)));
        }

        sortedEntries.sort(
                Comparator.comparingInt((SortedShopEntry entry) -> getTypeSortOrder(entry.entry().getType()))
                        .thenComparingInt(SortedShopEntry::inventoryIndex));
        return sortedEntries;
    }

    private int getTypeSortOrder(@Nonnull ShopItemType type) {
        return switch (type) {
            case WEAPON -> 0;
            case ARMOR -> 1;
            case HEAL -> 2;
            case AMMO -> 3;
        };
    }

    private void buildItemDetails(@Nonnull UICommandBuilder ui, @Nonnull String itemPath,
                                  @Nonnull ShopEntry entry, @Nullable ItemStack itemStack) {
        if (itemStack == null) return;
        String detailsPath = itemPath + " #ShopEntryDetails";

        switch (entry.getType()) {
            case WEAPON -> buildWeaponDetails(ui, detailsPath, itemStack);
            case ARMOR -> buildArmorDetails(ui, detailsPath, itemStack);
            case AMMO -> ui.set(detailsPath + " #ShopDetailText.TextSpans",
                    Message.raw("Refills ammo for held weapon"));
            case HEAL -> ui.set(detailsPath + " #ShopDetailText.TextSpans",
                    Message.raw("Restores health to maximum"));
        }
    }

    private void buildWeaponDetails(@Nonnull UICommandBuilder ui, @Nonnull String detailsPath,
                                    @Nonnull ItemStack itemStack) {
        String itemId = itemStack.getItemId();
        WeaponDefinition def = itemId != null ? WeaponDefinitions.getById(itemId) : null;
        if (def == null) return;

        WeaponRarity rarity = GunItemMetadata.getRarity(itemStack);
        DamageEffect effect = GunItemMetadata.getEffect(itemStack);
        List<WeaponModifier> modifiers = GunItemMetadata.getModifiers(itemStack);

        StringBuilder sb = new StringBuilder();
        sb.append("Rarity: ").append(rarity.name());
        if (effect != DamageEffect.NONE) {
            sb.append(" | Effect: ").append(effect.getDisplayName());
        }
        sb.append("\nDamage: ").append((int) def.baseDamage());
        sb.append(" | Speed: ").append(String.format("%.2fs", def.baseCooldown()));
        sb.append(" | Range: ").append((int) def.baseRange());

        int baseMaxAmmo = def.baseMaxAmmo() > 0
                ? def.baseMaxAmmo()
                : GunItemMetadata.getBaseMaxAmmo(itemStack, -1);
        if (baseMaxAmmo > 0) {
            sb.append("\nMax Ammo: ").append(baseMaxAmmo);
        }

        if (!modifiers.isEmpty()) {
            sb.append("\nModifiers:");
            for (WeaponModifier mod : modifiers) {
                sb.append("\n  ").append(mod.type().getDisplayName()).append(" +");
                if (mod.rolledValue() >= 1.0) {
                    sb.append((int) Math.round(mod.rolledValue()));
                } else {
                    sb.append((int) Math.round(mod.rolledValue() * 100)).append('%');
                }
            }
        }

        ui.set(detailsPath + " #ShopDetailText.TextSpans", Message.raw(sb.toString()));
    }

    private void buildArmorDetails(@Nonnull UICommandBuilder ui, @Nonnull String detailsPath,
                                   @Nonnull ItemStack itemStack) {
        String itemId = itemStack.getItemId();
        ArmorDefinition def = itemId != null ? ArmorDefinitions.getById(itemId) : null;
        if (def == null) return;

        WeaponRarity rarity = ArmorItemMetadata.getRarity(itemStack);
        List<ArmorModifier> modifiers = ArmorItemMetadata.getModifiers(itemStack);

        StringBuilder sb = new StringBuilder();
        sb.append("Rarity: ").append(rarity.name());
        sb.append(" | Slot: ").append(def.slotType().name());
        if (def.setAbility() != ArmorSetAbility.NONE) {
            sb.append(" | Set: ").append(def.setAbility().getDisplayName());
        }
        sb.append("\nProtection: ").append(Math.round(def.baseProtection() * 100)).append('%');
        if (def.baseSpeedBoost() > 0.001f) {
            sb.append(" | Speed: +").append(Math.round(def.baseSpeedBoost() * 100)).append('%');
        }
        if (def.baseLifeBoost() > 0.001f) {
            sb.append(" | HP: +").append(Math.round(def.baseLifeBoost() * 100)).append('%');
        }

        if (!modifiers.isEmpty()) {
            sb.append("\nModifiers:");
            for (ArmorModifier mod : modifiers) {
                sb.append("\n  ").append(mod.type().getDisplayName())
                        .append(" +").append((int) Math.round(mod.rolledValue() * 100)).append('%');
            }
        }

        ui.set(detailsPath + " #ShopDetailText.TextSpans", Message.raw(sb.toString()));
    }

    private void buildConfirmPanel(@Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events) {
        ui.set("#ShopConfirmPanel.Visible", true);

        ShopEntry entry = inventory.entries().get(confirmIndex);
        ItemStack itemStack = entry.getItemStack();
        String displayName = buildItemDisplayName(entry, itemStack);

        ui.set("#ShopConfirmText.TextSpans",
                Message.raw("Buy " + displayName + " for " + entry.getPrice() + " coins?\nTeam balance: " + game.getMoney() + " coins"));

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#ShopConfirmBtn",
                new EventData().put(ShopEventData.KEY_ACTION, "CONFIRM"),
                false
        );

        events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#ShopCancelBtn",
                new EventData().put(ShopEventData.KEY_ACTION, "CANCEL"),
                false
        );
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull ShopEventData data) {
        String action = data.action;
        if (action == null) return;

        switch (action) {
            case "CLOSE" -> {
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) {
                    player.getPageManager().setPage(ref, store, Page.None);
                }
                return;
            }
            case "BUY" -> {
                if (data.index != null) {
                    int idx = parseIntSafe(data.index, -1);
                    if (idx >= 0 && idx < inventory.entries().size()) {
                        ShopEntry entry = inventory.entries().get(idx);
                        if (!entry.isSold()) {
                            confirmingPurchase = true;
                            confirmIndex = idx;
                        }
                    }
                }
            }
            case "REFRESH" -> {
                confirmingPurchase = false;
                confirmIndex = -1;
                executeRefresh(store);
            }
            case "CONFIRM" -> {
                if (confirmingPurchase && confirmIndex >= 0) {
                    executePurchase(ref, store, confirmIndex);
                    confirmingPurchase = false;
                    confirmIndex = -1;
                }
            }
            case "CANCEL" -> {
                confirmingPurchase = false;
                confirmIndex = -1;
            }
        }

        refreshPage(ref, store);
    }

    private void executeRefresh(@Nonnull Store<EntityStore> store) {
        UnstableRifts plugin = UnstableRifts.getInstance();
        if (plugin == null) return;

        ShopService.ShopRefreshResult result = plugin.getShopService().tryRefresh(game, inventory, store);
        String message = switch (result) {
            case SUCCESS -> inventory.refreshesRemaining() > 0
                    ? "Shop refreshed! " + inventory.refreshesRemaining() + " refreshes left."
                    : "Shop refreshed! No refreshes left.";
            case DISABLED -> "This shop cannot be refreshed.";
            case NO_REFRESHES_REMAINING -> "No refreshes remaining!";
            case INSUFFICIENT_FUNDS -> "Not enough coins to refresh!";
        };

        String notificationId = result == ShopService.ShopRefreshResult.SUCCESS ? "shop_refresh" : "shop_refresh_fail";
        try {
            NotificationUtil.sendNotification(
                    this.playerRef.getPacketHandler(),
                    Message.raw(message),
                    null,
                    notificationId);
        } catch (Exception ignored) {
        }
    }

    private void executePurchase(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Store<EntityStore> store,
                                 int entryIndex) {
        UnstableRifts plugin = UnstableRifts.getInstance();
        if (plugin == null) return;

        ShopService shopService = plugin.getShopService();
        ShopService.PurchaseResult purchase = shopService.tryBuy(game, inventory, entryIndex, store);

        if (purchase == null || purchase.itemStack() == null) {
            try {
                NotificationUtil.sendNotification(
                        this.playerRef.getPacketHandler(),
                        Message.raw("Not enough coins!"),
                        null,
                        "shop_fail");
            } catch (Exception ignored) {
            }
            return;
        }

        // Give item to player using the same logic as pickup
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) return;

        ItemStack purchasedItem = purchase.itemStack();
        ShopEntry entry = purchase.entry();
        switch (entry.getType()) {
            case HEAL -> applyHealPurchaseOrDrop(ref, store, purchasedItem);
            case AMMO -> applyAmmoPurchaseOrDrop(ref, store, player, purchasedItem);
            default -> {
                boolean locked = plugin.getInventoryLockService().isLocked(playerRef.getUuid());
                if (locked) {
                    giveItemLocked(ref, store, player, purchasedItem, plugin);
                } else {
                    giveItemNormal(ref, store, player, purchasedItem);
                }
            }
        }

        shopService.removeSoldDisplay(entry, store);

        try {
            String name = buildItemDisplayName(entry, purchasedItem);
            NotificationUtil.sendNotification(
                    this.playerRef.getPacketHandler(),
                    Message.raw("Purchased " + name + "!"),
                    null,
                    "shop_buy");
        } catch (Exception ignored) {
        }
    }

    private void applyHealPurchaseOrDrop(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull ItemStack item) {
        EntityStatMap statMap = store.getComponent(ref, EntityStatMap.getComponentType());
        if (statMap == null) {
            ItemUtils.dropItem(ref, item, store);
            return;
        }

        int healthIdx = DefaultEntityStatTypes.getHealth();
        EntityStatValue healthStat = healthIdx >= 0 ? statMap.get(healthIdx) : null;
        if (healthStat == null || healthStat.get() >= healthStat.getMax()) {
            ItemUtils.dropItem(ref, item, store);
            return;
        }

        statMap.setStatValue(healthIdx, healthStat.getMax());
    }

    private void applyAmmoPurchaseOrDrop(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull Player player,
                                         @Nonnull ItemStack item) {
        ItemStack heldItem = player.getInventory() != null ? player.getInventory().getItemInHand() : null;
        if (heldItem == null || ItemStack.isEmpty(heldItem)) {
            ItemUtils.dropItem(ref, item, store);
            return;
        }

        String weaponId = heldItem.getItemId();
        WeaponDefinition definition = weaponId != null ? WeaponDefinitions.getById(weaponId) : null;

        int baseMaxAmmo = definition != null && definition.baseMaxAmmo() > 0
                ? definition.baseMaxAmmo()
                : GunItemMetadata.getBaseMaxAmmo(heldItem, -1);
        if (baseMaxAmmo <= 0) {
            ItemUtils.dropItem(ref, item, store);
            return;
        }

        double armorAmmoCapacityBonus = 0.0;
        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComp != null) {
            armorAmmoCapacityBonus = ArmorStatResolver.getTotalAmmoCapacityBonus(armorComp.getInventory());
        }
        int effectiveMaxAmmo = GunItemMetadata.getEffectiveMaxAmmo(heldItem, baseMaxAmmo, armorAmmoCapacityBonus);
        int currentAmmo = GunItemMetadata.getInt(heldItem, GunItemMetadata.AMMO_KEY, effectiveMaxAmmo);

        if (currentAmmo >= effectiveMaxAmmo) {
            ItemUtils.dropItem(ref, item, store);
            return;
        }

        ItemStack updated = GunItemMetadata.setInt(heldItem, GunItemMetadata.AMMO_KEY, effectiveMaxAmmo);
        updated = GunItemMetadata.ensureAmmo(updated, baseMaxAmmo, effectiveMaxAmmo);

        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp == null) {
            ItemUtils.dropItem(ref, item, store);
            return;
        }

        byte activeSlot = hotbarComp.getActiveSlot();
        hotbarComp.getInventory().replaceItemStackInSlot(activeSlot, heldItem, updated);
        syncInventory(ref, store);
        AmmoHudService.clear(playerRef);
        AmmoHudService.updateForHeldItem(player, playerRef, updated, false, ref);
    }

    private void giveItemNormal(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull Player player,
                                @Nonnull ItemStack item) {
        ItemStackTransaction transaction = player.giveItem(item, ref, store);
        ItemStack remainder = transaction.getRemainder();

        if (!ItemStack.isEmpty(remainder)) {
            // Inventory full — drop on the ground
            ItemUtils.dropItem(ref, remainder, store);
        }
    }

    private void giveItemLocked(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull Player player,
                                @Nonnull ItemStack item,
                                @Nonnull UnstableRifts plugin) {
        // Check if armor
        ArmorDefinition armorDef = ArmorDefinitions.getById(item.getItemId());
        if (armorDef != null) {
            giveArmorLocked(ref, store, item, armorDef, plugin);
            return;
        }

        // Weapon — use 3-slot swap logic
        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        if (hotbarComp == null) {
            ItemUtils.dropItem(ref, item, store);
            return;
        }

        ItemContainer hotbar = hotbarComp.getInventory();
        short emptySlot = InventoryLockService.findEmptyWeaponSlot(hotbar);

        if (emptySlot >= 0) {
            hotbar.setItemStackForSlot(emptySlot, item);
        } else {
            byte activeSlot = hotbarComp.getActiveSlot();
            if (activeSlot < 0 || activeSlot >= InventoryLockService.MAX_WEAPON_SLOTS) {
                activeSlot = 0;
            }
            ItemStack oldWeapon = hotbar.getItemStack(activeSlot);
            hotbar.setItemStackForSlot(activeSlot, item);
            if (!ItemStack.isEmpty(oldWeapon)) {
                ItemUtils.dropItem(ref, oldWeapon, store);
            }
        }

        syncInventory(ref, store);
    }

    private void giveArmorLocked(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull ItemStack armorItem,
                                 @Nonnull ArmorDefinition armorDef,
                                 @Nonnull UnstableRifts plugin) {
        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComp == null) {
            ItemUtils.dropItem(ref, armorItem, store);
            return;
        }

        ItemContainer armorInv = armorComp.getInventory();
        short targetSlot = (short) armorDef.slotType().getSlotIndex();
        ItemStack oldArmor = armorInv.getItemStack(targetSlot);
        armorInv.setItemStackForSlot(targetSlot, armorItem, false);

        if (!ItemStack.isEmpty(oldArmor)) {
            ItemUtils.dropItem(ref, oldArmor, store);
        }

        ArmorSetTracker tracker = plugin.getArmorSetTracker();
        if (tracker != null) {
            tracker.onArmorChanged(playerRef.getUuid(), armorInv);
        }

        syncInventory(ref, store);
    }

    private void syncInventory(@Nonnull Ref<EntityStore> ref,
                               @Nonnull Store<EntityStore> store) {
        InventoryComponent.Storage storageComp = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Armor armorComp = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        InventoryComponent.Hotbar hotbarComp = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Utility utilityComp = store.getComponent(ref, InventoryComponent.Utility.getComponentType());
        InventoryComponent.Tool toolComp = store.getComponent(ref, InventoryComponent.Tool.getComponentType());
        InventoryComponent.Backpack backpackComp = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());

        playerRef.getPacketHandler().write(new UpdatePlayerInventory(
                storageComp != null ? storageComp.getInventory().toPacket() : null,
                armorComp != null ? armorComp.getInventory().toPacket() : null,
                hotbarComp != null ? hotbarComp.getInventory().toPacket() : null,
                utilityComp != null ? utilityComp.getInventory().toPacket() : null,
                toolComp != null ? toolComp.getInventory().toPacket() : null,
                backpackComp != null ? backpackComp.getInventory().toPacket() : null
        ));
    }

    private void refreshPage(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        ui.set("#ShopMoney.TextSpans", Message.raw("Coins: " + game.getMoney()));
        buildRefreshControls(ui, events);

        if (confirmingPurchase && confirmIndex >= 0 && confirmIndex < inventory.entries().size()) {
            buildConfirmPanel(ui, events);
            ui.clear(ITEM_LIST_PATH);
        } else {
            ui.set("#ShopConfirmPanel.Visible", false);
            buildItemList(ui, events);
        }

        sendUpdate(ui, events, false);
    }

    @Nonnull
    private String buildItemDisplayName(@Nonnull ShopEntry entry, @Nullable ItemStack itemStack) {
        if (itemStack == null) return entry.getType().getDisplayName();

        String itemId = itemStack.getItemId();
        if (itemId == null) return entry.getType().getDisplayName();

        WeaponDefinition weaponDef = WeaponDefinitions.getById(itemId);
        if (weaponDef != null) {
            WeaponRarity rarity = GunItemMetadata.getRarity(itemStack);
            String name = weaponDef.displayName() != null ? weaponDef.displayName() : itemId;
            return rarity != WeaponRarity.BASIC ? rarity.name() + " " + name : name;
        }

        ArmorDefinition armorDef = ArmorDefinitions.getById(itemId);
        if (armorDef != null) {
            WeaponRarity rarity = ArmorItemMetadata.getRarity(itemStack);
            String name = armorDef.displayName() != null ? armorDef.displayName() : itemId;
            return rarity != WeaponRarity.BASIC ? rarity.name() + " " + name : name;
        }

        return switch (entry.getType()) {
            case AMMO -> "Ammo Pack";
            case HEAL -> "Health Kit";
            default -> entry.getType().getDisplayName();
        };
    }

    @Nonnull
    private String getItemColorHex(@Nonnull ShopEntry entry, @Nullable ItemStack itemStack) {
        if (itemStack == null) return "#FFFFFF";

        String itemId = itemStack.getItemId();
        if (itemId == null) return "#FFFFFF";

        WeaponDefinition weaponDef = WeaponDefinitions.getById(itemId);
        if (weaponDef != null) {
            return GunItemMetadata.getRarity(itemStack).getColorHex();
        }

        ArmorDefinition armorDef = ArmorDefinitions.getById(itemId);
        if (armorDef != null) {
            return ArmorItemMetadata.getRarity(itemStack).getColorHex();
        }

        return "#FFFFFF";
    }

    @Nonnull
    private String buildRefreshButtonText() {
        String costText = inventory.refreshCost() > 0
                ? inventory.refreshCost() + "c"
                : "free";
        return "REFRESH " + costText.toUpperCase() + " x" + inventory.refreshesRemaining();
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static final class ShopEventData {
        static final String KEY_ACTION = "Action";
        static final String KEY_INDEX = "Index";

        static final BuilderCodec<ShopEventData> CODEC = BuilderCodec.builder(ShopEventData.class, ShopEventData::new)
                .append(new KeyedCodec<>(KEY_ACTION, Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
                .append(new KeyedCodec<>(KEY_INDEX, Codec.STRING), (d, v) -> d.index = v, d -> d.index).add()
                .build();

        @Nullable
        private String action;
        @Nullable
        private String index;
    }

    private record SortedShopEntry(int inventoryIndex, @Nonnull ShopEntry entry) {
    }
}
