package dev.ninesliced.shotcave.tooltip;

import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.EntityUpdate;
import com.hypixel.hytale.protocol.InventorySection;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemUpdate;
import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateItems;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.ninesliced.shotcave.ShotcavePacketIds;
import dev.ninesliced.shotcave.armor.ArmorDefinitions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bidirectional packet adapter that rewrites armor items with per-instance
 * virtual IDs carrying custom tooltips and quality glow.
 * Mirrors the pattern from {@link WeaponTooltipAdapter}.
 */
public final class ArmorTooltipAdapter implements PlayerPacketFilter {

    @Override
    public boolean test(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        int id = packet.getId();
        if (id == ShotcavePacketIds.UPDATE_INVENTORY) {
            processOutboundInventory(playerRef, (UpdatePlayerInventory) packet);
        } else if (id == ShotcavePacketIds.ENTITY_UPDATES) {
            processEntityUpdates(playerRef, (EntityUpdates) packet);
        } else if (id == ShotcavePacketIds.SYNC_INTERACTION_CHAINS) {
            processInboundChains((SyncInteractionChains) packet);
        }
        return false;
    }

    private void processOutboundInventory(@Nonnull PlayerRef playerRef,
                                           @Nonnull UpdatePlayerInventory packet) {
        UUID uuid = playerRef.getUuid();
        Map<String, ItemBase> pendingItems = new HashMap<>();
        Map<String, String> pendingTranslations = new HashMap<>();

        rewriteSection(uuid, packet.hotbar, pendingItems, pendingTranslations);
        rewriteSection(uuid, packet.storage, pendingItems, pendingTranslations);
        rewriteSection(uuid, packet.armor, pendingItems, pendingTranslations);
        rewriteSection(uuid, packet.utility, pendingItems, pendingTranslations);
        rewriteSection(uuid, packet.tools, pendingItems, pendingTranslations);
        rewriteSection(uuid, packet.backpack, pendingItems, pendingTranslations);

        if (!pendingItems.isEmpty()) {
            UpdateItems updateItems = new UpdateItems();
            updateItems.type = UpdateType.AddOrUpdate;
            updateItems.items = pendingItems;
            updateItems.updateModels = true;
            updateItems.updateIcons = false;
            playerRef.getPacketHandler().writeNoCache(updateItems);
        }
        if (!pendingTranslations.isEmpty()) {
            playerRef.getPacketHandler().writeNoCache(
                    new UpdateTranslations(UpdateType.AddOrUpdate, pendingTranslations));
        }
    }

    private static void rewriteSection(@Nonnull UUID playerUuid,
                                         @Nullable InventorySection section,
                                         @Nonnull Map<String, ItemBase> pendingItems,
                                         @Nonnull Map<String, String> pendingTranslations) {
        if (section == null || section.items == null) return;

        for (Map.Entry<Integer, ItemWithAllMetadata> entry : section.items.entrySet()) {
            ItemWithAllMetadata item = entry.getValue();
            if (item == null || item.itemId == null || item.itemId.isEmpty()) continue;
            if (ArmorVirtualItems.isVirtual(item.itemId)) continue;
            if (WeaponVirtualItems.isVirtual(item.itemId)) continue;
            if (ArmorDefinitions.getById(item.itemId) == null) continue;

            String virtualId = ArmorVirtualItems.processArmorSlot(
                    playerUuid, item.itemId, item.metadata,
                    pendingItems, pendingTranslations);
            if (virtualId != null) {
                item.itemId = virtualId;
            }
        }
    }

    private void processEntityUpdates(@Nonnull PlayerRef playerRef,
                                       @Nonnull EntityUpdates packet) {
        if (packet.updates == null) return;

        UUID uuid = playerRef.getUuid();
        Map<String, ItemBase> pendingItems = new HashMap<>();
        Map<String, String> pendingTranslations = new HashMap<>();

        for (EntityUpdate entityUpdate : packet.updates) {
            if (entityUpdate.updates == null) continue;
            for (ComponentUpdate comp : entityUpdate.updates) {
                if (!(comp instanceof ItemUpdate itemUpdate)) continue;

                String itemId = itemUpdate.item.itemId;
                if (itemId == null || itemId.isEmpty()) continue;
                if (ArmorVirtualItems.isVirtual(itemId)) continue;
                if (WeaponVirtualItems.isVirtual(itemId)) continue;
                if (ArmorDefinitions.getById(itemId) == null) continue;

                String virtualId = ArmorVirtualItems.processArmorSlot(
                        uuid, itemId, itemUpdate.item.metadata,
                        pendingItems, pendingTranslations);
                if (virtualId != null) {
                    itemUpdate.item.itemId = virtualId;
                }
            }
        }

        if (!pendingItems.isEmpty()) {
            UpdateItems updateItems = new UpdateItems();
            updateItems.type = UpdateType.AddOrUpdate;
            updateItems.items = pendingItems;
            updateItems.updateModels = true;
            updateItems.updateIcons = false;
            playerRef.getPacketHandler().writeNoCache(updateItems);
        }
        if (!pendingTranslations.isEmpty()) {
            playerRef.getPacketHandler().writeNoCache(
                    new UpdateTranslations(UpdateType.AddOrUpdate, pendingTranslations));
        }
    }

    private void processInboundChains(@Nonnull SyncInteractionChains syncPacket) {
        if (syncPacket.updates == null) return;
        for (SyncInteractionChain chain : syncPacket.updates) {
            if (chain != null) translateChainIds(chain);
        }
    }

    private static void translateChainIds(@Nonnull SyncInteractionChain chain) {
        chain.itemInHandId = resolveBase(chain.itemInHandId);
        chain.utilityItemId = resolveBase(chain.utilityItemId);
        chain.toolsItemId = resolveBase(chain.toolsItemId);

        if (chain.newForks != null) {
            for (SyncInteractionChain fork : chain.newForks) {
                if (fork != null) translateChainIds(fork);
            }
        }
    }

    @Nullable
    private static String resolveBase(@Nullable String id) {
        if (id != null && ArmorVirtualItems.isVirtual(id)) {
            return ArmorVirtualItems.getBaseItemId(id);
        }
        return id;
    }
}
