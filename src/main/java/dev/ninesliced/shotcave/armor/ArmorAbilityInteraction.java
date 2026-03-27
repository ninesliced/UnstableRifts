package dev.ninesliced.shotcave.armor;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Interaction triggered when the player activates the Ability1 slot.
 * Validates that the player has a full 4/4 armor set and that the
 * charge is ready, then delegates to {@link ArmorAbilityBuffSystem}.
 */
public final class ArmorAbilityInteraction extends SimpleInstantInteraction {

    @Nonnull
    public static final BuilderCodec<ArmorAbilityInteraction> CODEC = BuilderCodec.builder(
            ArmorAbilityInteraction.class,
            ArmorAbilityInteraction::new,
            SimpleInstantInteraction.CODEC)
            .documentation("Activates the armor set ability when full set is equipped and charge is ready.")
            .build();

    public ArmorAbilityInteraction() {}

    public ArmorAbilityInteraction(@Nonnull String id) {
        super(id);
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type,
                            @Nonnull InteractionContext context,
                            @Nonnull CooldownHandler cooldownHandler) {
        Ref<EntityStore> ref = context.getEntity();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();

        // Check charge is ready
        ArmorChargeComponent charge = commandBuffer.getComponent(ref, ArmorChargeComponent.getComponentType());
        if (charge == null || !charge.isReady()) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        // Validate full 4/4 set
        InventoryComponent.Armor armorComp = commandBuffer.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComp == null) {
            context.getState().state = InteractionState.Failed;
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
            context.getState().state = InteractionState.Failed;
            return;
        }

        // Resolve the set ability
        ArmorDefinition anyPiece = ArmorDefinitions.getBySetId(matchSetId).stream().findFirst().orElse(null);
        if (anyPiece == null || anyPiece.getSetAbility() == ArmorSetAbility.NONE) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        // Activate: consume charge and start the buff
        charge.reset();

        com.hypixel.hytale.server.core.universe.PlayerRef playerRef =
                commandBuffer.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
        ArmorAbilityBuffSystem.activateAbility(ref, commandBuffer, anyPiece.getSetAbility(), playerRef);
    }

    @Nonnull
    @Override
    public String toString() {
        return "ArmorAbilityInteraction{} " + super.toString();
    }
}
