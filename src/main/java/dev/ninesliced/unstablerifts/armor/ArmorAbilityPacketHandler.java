package dev.ninesliced.unstablerifts.armor;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionChainData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChain;
import com.hypixel.hytale.protocol.packets.interaction.SyncInteractionChains;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.network.UnstableRiftsPacketIds;

import javax.annotation.Nonnull;

/**
 * Watches for F-key (Use) presses that are NOT targeting an entity or block,
 * and activates the armor set ability if the charge is ready and a full 4/4
 * set is equipped.
 */
public final class ArmorAbilityPacketHandler implements PlayerPacketWatcher {

    public ArmorAbilityPacketHandler() {
    }

    private static void tryActivateAbility(@Nonnull PlayerRef playerRef,
                                           @Nonnull Ref<EntityStore> ref,
                                           @Nonnull ComponentAccessor<EntityStore> accessor) {
        if (!ref.isValid()) return;

        ArmorChargeComponent charge = accessor.getComponent(ref, ArmorChargeComponent.getComponentType());
        if (charge == null || !charge.isReady()) {
            return;
        }

        if (ArmorAbilityActivationGuard.hasBlockingUseInteraction(playerRef, ref, accessor)) {
            return;
        }

        InventoryComponent.Armor armorComp = accessor.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComp == null) {
            return;
        }

        ItemContainer armorInv = armorComp.getInventory();
        String matchSetId = null;
        int matchCount = 0;

        for (int slot = 0; slot < 4; slot++) {
            ItemStack stack = armorInv.getItemStack((short) slot);
            if (ItemStack.isEmpty(stack)) continue;

            String setId = ArmorItemMetadata.getSetId(stack);
            if (setId == null) continue;

            if (matchSetId == null) {
                matchSetId = setId;
                matchCount = 1;
            } else if (matchSetId.equals(setId)) {
                matchCount++;
            }
        }

        if (matchCount < 4 || matchSetId == null) {
            return;
        }

        ArmorDefinition anyPiece = ArmorDefinitions.getBySetId(matchSetId).stream().findFirst().orElse(null);
        if (anyPiece == null || anyPiece.setAbility() == ArmorSetAbility.NONE) {
            return;
        }

        charge.reset();
        ArmorAbilityBuffSystem.activateAbility(ref, accessor, anyPiece.setAbility(), playerRef);
    }

    @Override
    public void accept(@Nonnull PlayerRef playerRef, @Nonnull Packet packet) {
        if (packet.getId() != UnstableRiftsPacketIds.SYNC_INTERACTION_CHAINS) {
            return;
        }

        SyncInteractionChains interactionChains = (SyncInteractionChains) packet;
        SyncInteractionChain[] updates = interactionChains.updates;
        if (updates == null || updates.length == 0) {
            return;
        }

        boolean hasUntargetedUse = false;
        for (SyncInteractionChain chain : updates) {
            if (chain.interactionType != InteractionType.Use) {
                continue;
            }
            InteractionChainData data = chain.data;
            if (data != null && (data.entityId >= 0 || data.blockPosition != null)) {
                continue;
            }
            hasUntargetedUse = true;
            break;
        }

        if (!hasUntargetedUse) {
            return;
        }

        if (ArmorAbilityActivationGuard.hasVisiblePrompt(playerRef.getUuid())) {
            return;
        }

        if (!playerRef.isValid()) {
            return;
        }

        Ref<EntityStore> playerEntityRef = playerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        World world;
        try {
            world = store.getExternalData().getWorld();
        } catch (Exception e) {
            return;
        }
        if (world == null) {
            return;
        }

        world.execute(() -> tryActivateAbility(playerRef, playerEntityRef, store));
    }
}
