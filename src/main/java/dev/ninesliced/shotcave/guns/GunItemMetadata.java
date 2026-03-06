package dev.ninesliced.shotcave.guns;

import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class GunItemMetadata {
    public static final String AMMO_KEY = "Shotcave_Ammo";
    public static final String MAX_AMMO_KEY = "Shotcave_MaxAmmo";

    private GunItemMetadata() {
    }

    @Nonnull
    public static ItemStack ensureAmmo(@Nonnull ItemStack stack, int maxAmmo) {
        ItemStack out = stack;

        if (!hasInt(out, MAX_AMMO_KEY)) {
            out = out.withMetadata(MAX_AMMO_KEY, new BsonInt32(Math.max(1, maxAmmo)));
        }
        if (!hasInt(out, AMMO_KEY)) {
            out = out.withMetadata(AMMO_KEY, new BsonInt32(getInt(out, MAX_AMMO_KEY, maxAmmo)));
        }

        return out;
    }

    public static boolean hasInt(@Nonnull ItemStack stack, @Nonnull String key) {
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null) {
            return false;
        }
        BsonValue value = metadata.get(key);
        return value != null && value.isNumber();
    }

    public static int getInt(@Nonnull ItemStack stack, @Nonnull String key, int fallback) {
        BsonDocument metadata = stack.getMetadata();
        if (metadata == null) {
            return fallback;
        }

        BsonValue value = metadata.get(key);
        if (value == null || !value.isNumber()) {
            return fallback;
        }

        return value.asNumber().intValue();
    }

    @Nonnull
    public static ItemStack setInt(@Nonnull ItemStack stack, @Nonnull String key, int value) {
        return stack.withMetadata(key, new BsonInt32(value));
    }


    @Nonnull
    public static ItemStack applyHeldItem(@Nonnull InteractionContext context, @Nonnull ItemStack updatedStack) {
        ItemStack current = context.getHeldItem();
        if (current == null) {
            return updatedStack;
        }

        ItemContainer container = context.getHeldItemContainer();
        if (container != null) {
            container.replaceItemStackInSlot((short) context.getHeldItemSlot(), current, updatedStack);
        }
        context.setHeldItem(updatedStack);
        return updatedStack;
    }
}
