package dev.ninesliced.unstablerifts.tooltip;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.protocol.packets.assets.UpdateItems;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.protocol.packets.player.MouseInteraction;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.guns.WeaponDefinitions;
import dev.ninesliced.unstablerifts.network.UnstableRiftsPacketIds;
import org.bson.BsonDocument;

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
 * Each UnstableRifts weapon slot is replaced with a virtual item ID whose
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
    //  OUTBOUND — rewrite weapon items to virtual IDs
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Rewrites {@link EquipmentUpdate#rightHandItemId} to a virtual weapon
     * ID so the recipient's client renders the correct quality glow and
     * tooltip for another player's held weapon.
     */
    private static void virtualizeEquipmentWeapon(
            @Nonnull UUID recipientUuid,
            @Nonnull EquipmentUpdate equipment,
            int networkId,
            @Nonnull EntityStore entityStore,
            @Nonnull Map<String, ItemBase> pendingItems,
            @Nonnull Map<String, String> pendingTranslations) {

        String itemId = equipment.rightHandItemId;
        if (itemId == null || itemId.isEmpty()) return;

        // Shared packet: a previous player's adapter may have already
        // rewritten this field to a virtual ID.
        String baseItemId;
        boolean alreadyVirtual = WeaponVirtualItems.isVirtual(itemId);
        if (alreadyVirtual) {
            baseItemId = WeaponVirtualItems.getBaseItemId(itemId);
        } else {
            baseItemId = itemId;
        }
        if (baseItemId == null) return;
        if (WeaponDefinitions.getById(baseItemId) == null) return;

        // Resolve network entity → Player component → held‑item metadata
        Ref<EntityStore> entityRef = entityStore.getRefFromNetworkId(networkId);
        if (entityRef == null || !entityRef.isValid()) return;

        Store<EntityStore> store = entityRef.getStore();
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player == null) return;

        ItemStack heldItem = player.getInventory().getItemInHand();
        if (heldItem == null) return;
        // Sanity check: make sure the inventory agrees with the packet
        if (!baseItemId.equals(heldItem.getItemId())) return;

        BsonDocument doc = heldItem.getMetadata();
        String metadataJson = doc != null ? doc.toJson() : null;

        String virtualId = WeaponVirtualItems.processWeaponSlot(
                recipientUuid, baseItemId, metadataJson,
                pendingItems, pendingTranslations);
        if (virtualId != null && !alreadyVirtual) {
            equipment.rightHandItemId = virtualId;
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

    // ═══════════════════════════════════════════════════════════════════
    //  OUTBOUND — rewrite dropped‑item and equipment entity updates
    // ═══════════════════════════════════════════════════════════════════

    private static void translateMouseInteraction(@Nonnull MouseInteraction packet) {
        if (packet.itemInHandId != null && WeaponVirtualItems.isVirtual(packet.itemInHandId)) {
            packet.itemInHandId = WeaponVirtualItems.getBaseItemId(packet.itemInHandId);
        }
    }

    @Nullable
    private static String resolveBase(@Nullable String id) {
        if (id != null && WeaponVirtualItems.isVirtual(id)) {
            return WeaponVirtualItems.getBaseItemId(id);
        }
        return id;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  INBOUND — translate virtual IDs back to real IDs
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean test(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        int id = packet.getId();
        if (id == UnstableRiftsPacketIds.UPDATE_INVENTORY) {
            processOutboundInventory(playerRef, (UpdatePlayerInventory) packet);
        } else if (id == UnstableRiftsPacketIds.ENTITY_UPDATES) {
            processEntityUpdates(playerRef, (EntityUpdates) packet);
        } else if (id == UnstableRiftsPacketIds.SYNC_INTERACTION_CHAINS) {
            processInboundChains((SyncInteractionChains) packet);
        } else if (id == UnstableRiftsPacketIds.MOUSE_INTERACTION) {
            translateMouseInteraction((com.hypixel.hytale.protocol.packets.player.MouseInteraction) packet);
        }
        return false; // never block packets
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

    private void processEntityUpdates(@Nonnull PlayerRef playerRef,
                                      @Nonnull EntityUpdates packet) {
        if (packet.updates == null) return;

        UUID uuid = playerRef.getUuid();
        Map<String, ItemBase> pendingItems = new HashMap<>();
        Map<String, String> pendingTranslations = new HashMap<>();

        // Resolve the EntityStore so we can look up remote players for
        // EquipmentUpdate virtualisation.
        EntityStore entityStore = null;
        if (playerRef.isValid() && playerRef.getReference() != null) {
            Store<EntityStore> s = playerRef.getReference().getStore();
            if (s != null) entityStore = s.getExternalData();
        }

        for (EntityUpdate entityUpdate : packet.updates) {
            if (entityUpdate.updates == null) continue;
            for (ComponentUpdate comp : entityUpdate.updates) {
                if (comp instanceof ItemUpdate itemUpdate) {
                    // Dropped item entities — the packet object is shared
                    // across recipients, so a previous player's adapter may
                    // have already rewritten itemId to a virtual ID.
                    String itemId = itemUpdate.item.itemId;
                    if (itemId == null || itemId.isEmpty()) continue;

                    String baseItemId;
                    boolean alreadyVirtual = WeaponVirtualItems.isVirtual(itemId);
                    if (alreadyVirtual) {
                        baseItemId = WeaponVirtualItems.getBaseItemId(itemId);
                    } else {
                        baseItemId = itemId;
                    }
                    if (baseItemId == null) continue;
                    if (WeaponDefinitions.getById(baseItemId) == null) continue;

                    String virtualId = WeaponVirtualItems.processWeaponSlot(
                            uuid, baseItemId, itemUpdate.item.metadata,
                            pendingItems, pendingTranslations);
                    if (virtualId != null && !alreadyVirtual) {
                        itemUpdate.item.itemId = virtualId;
                    }
                } else if (comp instanceof EquipmentUpdate equipUpdate && entityStore != null) {
                    // Player equipment broadcast — virtualise the held
                    // weapon so OTHER players see the correct quality
                    // glow and model instead of an unknown virtual ID.
                    virtualizeEquipmentWeapon(uuid, equipUpdate,
                            entityUpdate.networkId, entityStore,
                            pendingItems, pendingTranslations);
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

    private void processInboundChains(@Nonnull SyncInteractionChains syncPacket) {
        if (syncPacket.updates == null) return;
        for (SyncInteractionChain chain : syncPacket.updates) {
            if (chain != null) translateChainIds(chain);
        }
    }
}
