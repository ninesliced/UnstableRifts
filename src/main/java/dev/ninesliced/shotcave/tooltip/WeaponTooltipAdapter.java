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
import dev.ninesliced.shotcave.guns.WeaponDefinitions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bidirectional packet adapter that rewrites weapon items with per‑instance
 * virtual IDs carrying custom tooltips and quality glow.
 * <p>
 * <b>Outbound</b> ({@code UpdatePlayerInventory}, packet 170):
 * Each Shotcave weapon slot is replaced with a virtual item ID whose
 * {@link ItemBase} clone has the correct {@code qualityIndex} (rarity
 * glow in inventory) and {@code translationProperties} (custom name and
 * description visible on hover).  The virtual definitions and their
 * translations are sent via {@code UpdateItems}/{@code UpdateTranslations}
 * <i>before</i> the inventory packet so the client already knows about
 * them when it processes the inventory.
 * <p>
 * <b>Outbound</b> ({@code EntityUpdates}, packet 161):
 * Dropped weapon entities carry an {@link ItemUpdate} component whose
 * item ID is rewritten to the virtual ID so the client renders the
 * correct quality particle system on the ground.
 * <p>
 * <b>Inbound</b> ({@code SyncInteractionChains}, packet 290):
 * The client may echo the virtual item ID back in interaction packets.
 * We translate those back to the real base item ID so the server's
 * interaction‑chain validation passes normally.
 * <p>
 * Register on <b>both</b> inbound and outbound via
 * {@link com.hypixel.hytale.server.core.io.adapter.PacketAdapters}.
 */
public final class WeaponTooltipAdapter implements PlayerPacketFilter {

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
        return false; // never block packets
    }

    // ═══════════════════════════════════════════════════════════════════
    //  OUTBOUND — rewrite weapon items to virtual IDs
    // ═══════════════════════════════════════════════════════════════════

    private void processOutboundInventory(@Nonnull PlayerRef playerRef,
                                           @Nonnull UpdatePlayerInventory packet) {
        UUID uuid = playerRef.getUuid();
        Map<String, ItemBase> pendingItems = new HashMap<>();
        Map<String, String>   pendingTranslations = new HashMap<>();

        rewriteSection(uuid, packet.hotbar,   pendingItems, pendingTranslations);
        rewriteSection(uuid, packet.storage,  pendingItems, pendingTranslations);
        rewriteSection(uuid, packet.armor,    pendingItems, pendingTranslations);
        rewriteSection(uuid, packet.utility,  pendingItems, pendingTranslations);
        rewriteSection(uuid, packet.tools,    pendingItems, pendingTranslations);
        rewriteSection(uuid, packet.backpack, pendingItems, pendingTranslations);

        // Send virtual item definitions BEFORE the inventory packet.
        // writeNoCache writes directly, bypassing the adapter chain.
        if (!pendingItems.isEmpty()) {
            UpdateItems updateItems = new UpdateItems();
            updateItems.type = UpdateType.AddOrUpdate;
            updateItems.items = pendingItems;
            updateItems.updateModels = false;
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
            if (item == null) continue;
            if (item.itemId == null || item.itemId.isEmpty()) continue;
            if (WeaponVirtualItems.isVirtual(item.itemId)) continue;
            if (WeaponDefinitions.getById(item.itemId) == null) continue;

            String virtualId = WeaponVirtualItems.processWeaponSlot(
                    playerUuid, item.itemId, item.metadata,
                    pendingItems, pendingTranslations);
            if (virtualId != null) {
                item.itemId = virtualId;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  OUTBOUND — rewrite dropped‑item entity updates to virtual IDs
    // ═══════════════════════════════════════════════════════════════════

    private void processEntityUpdates(@Nonnull PlayerRef playerRef,
                                       @Nonnull EntityUpdates packet) {
        if (packet.updates == null) return;

        UUID uuid = playerRef.getUuid();
        Map<String, ItemBase> pendingItems = new HashMap<>();
        Map<String, String>   pendingTranslations = new HashMap<>();

        for (EntityUpdate entityUpdate : packet.updates) {
            if (entityUpdate.updates == null) continue;
            for (ComponentUpdate comp : entityUpdate.updates) {
                if (!(comp instanceof ItemUpdate itemUpdate)) continue;

                String itemId = itemUpdate.item.itemId;
                if (itemId == null || itemId.isEmpty()) continue;
                if (WeaponVirtualItems.isVirtual(itemId)) continue;
                if (WeaponDefinitions.getById(itemId) == null) continue;

                String virtualId = WeaponVirtualItems.processWeaponSlot(
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
            updateItems.updateModels = false;
            updateItems.updateIcons = false;
            playerRef.getPacketHandler().writeNoCache(updateItems);
        }
        if (!pendingTranslations.isEmpty()) {
            playerRef.getPacketHandler().writeNoCache(
                    new UpdateTranslations(UpdateType.AddOrUpdate, pendingTranslations));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  INBOUND — translate virtual IDs back to real IDs
    // ═══════════════════════════════════════════════════════════════════

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
        if (id != null && WeaponVirtualItems.isVirtual(id)) {
            return WeaponVirtualItems.getBaseItemId(id);
        }
        return id;
    }
}
