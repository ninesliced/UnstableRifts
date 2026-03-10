package dev.ninesliced.shotcave.pickup;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe tracker for item entities with custom pickup behavior (F-key or
 * score-collect).
 */
public final class ItemPickupTracker {

    public static final class TrackedItem {
        private final Ref<EntityStore> ref;
        private final String itemId;
        private final String displayName;
        private final String iconPath;
        private final boolean fKeyPickup;
        private final boolean scoreCollect;

        public TrackedItem(@Nonnull Ref<EntityStore> ref,
                @Nonnull String itemId,
                @Nullable String displayName,
                @Nullable String iconPath,
                boolean fKeyPickup,
                boolean scoreCollect) {
            this.ref = ref;
            this.itemId = itemId;
            this.displayName = displayName;
            this.iconPath = iconPath;
            this.fKeyPickup = fKeyPickup;
            this.scoreCollect = scoreCollect;
        }

        @Nonnull
        public Ref<EntityStore> getRef() {
            return ref;
        }

        @Nonnull
        public String getItemId() {
            return itemId;
        }

        @Nullable
        public String getDisplayName() {
            return displayName;
        }

        @Nullable
        public String getIconPath() {
            return iconPath;
        }

        public boolean isFKeyPickup() {
            return fKeyPickup;
        }

        public boolean isScoreCollect() {
            return scoreCollect;
        }

        @Nullable
        public Vector3d getPosition(@Nonnull com.hypixel.hytale.component.ComponentAccessor<EntityStore> accessor) {
            if (!ref.isValid()) {
                return null;
            }
            TransformComponent transform = accessor.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                return null;
            }
            return transform.getPosition();
        }

        @Nullable
        public ItemStack getItemStack(@Nonnull com.hypixel.hytale.component.ComponentAccessor<EntityStore> accessor) {
            if (!ref.isValid()) {
                return null;
            }
            ItemComponent itemComponent = accessor.getComponent(ref, ItemComponent.getComponentType());
            if (itemComponent == null) {
                return null;
            }
            return itemComponent.getItemStack();
        }
    }

    private static final ConcurrentHashMap<Ref<EntityStore>, TrackedItem> TRACKED = new ConcurrentHashMap<>();

    private ItemPickupTracker() {
    }

    public static void track(@Nonnull TrackedItem item) {
        TRACKED.put(item.getRef(), item);
    }

    public static void untrack(@Nonnull Ref<EntityStore> ref) {
        TRACKED.remove(ref);
    }

    /** Removes entries whose entity ref is no longer valid. */
    public static void pruneInvalid() {
        TRACKED.entrySet().removeIf(entry -> !entry.getKey().isValid());
    }

    public static void clear() {
        TRACKED.clear();
    }

    @Nonnull
    public static Collection<TrackedItem> getAll() {
        return TRACKED.values();
    }

    @Nullable
    public static TrackedItem get(@Nonnull Ref<EntityStore> ref) {
        return TRACKED.get(ref);
    }

    public static int size() {
        return TRACKED.size();
    }

    @Nonnull
    public static Map<Ref<EntityStore>, TrackedItem> entries() {
        return TRACKED;
    }
}
