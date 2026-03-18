package dev.ninesliced.shotcave.command;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.guns.GunItemMetadata;
import dev.ninesliced.shotcave.guns.WeaponLootRoller;
import dev.ninesliced.shotcave.guns.WeaponRarity;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * /sc loot [rarity] — Spawns a random rolled weapon on the ground at the player's feet.
 * Optional rarity: common, uncommon, rare, epic, legendary, unique
 */
public class LootCommand extends AbstractCommand {

    @Nonnull
    private final OptionalArg<String> rarityArg = this.withOptionalArg(
            "rarity", "Weapon rarity (common/uncommon/rare/epic/legendary/unique)", ArgTypes.STRING);

    public LootCommand() {
        super("loot", "Spawn a random weapon drop at your feet");
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected String generatePermissionNode() {
        return "";
    }

    @Nonnull
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("Must be run by a player.").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            return CompletableFuture.completedFuture(null);
        }

        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();

        // Parse optional rarity argument
        WeaponRarity forcedRarity = null;
        String rarityInput = this.rarityArg.get(context);
        if (rarityInput != null && !rarityInput.isBlank()) {
            forcedRarity = WeaponRarity.fromString(rarityInput);
        }

        ItemStack rolled = WeaponLootRoller.rollRandom(forcedRarity);

        String rarity = GunItemMetadata.getRarity(rolled).name();
        String effect = GunItemMetadata.getEffect(rolled).getDisplayName();
        int modCount = GunItemMetadata.getModifiers(rolled).size();

        world.execute(() -> {
            if (!ref.isValid()) return;

            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) return;

            Vector3d dropPosition = transform.getPosition().clone();
            Store<EntityStore> entityStore = world.getEntityStore().getStore();
            Holder<EntityStore>[] holders = ItemComponent.generateItemDrops(
                    entityStore, List.of(rolled), dropPosition, Vector3f.ZERO);
            if (holders.length > 0) {
                entityStore.addEntities(holders, AddReason.SPAWN);
            }
        });

        String msg = String.format("Dropped [%s] %s%s with %d modifier(s)",
                rarity,
                effect.isEmpty() ? "" : effect + " ",
                rolled.getItemId(),
                modCount);
        context.sendMessage(Message.raw(msg).color(Color.GREEN));

        return CompletableFuture.completedFuture(null);
    }
}
