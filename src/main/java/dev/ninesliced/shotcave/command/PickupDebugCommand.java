package dev.ninesliced.shotcave.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.pickup.ItemPickupConfig;
import dev.ninesliced.shotcave.pickup.ItemPickupTracker;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.util.Collection;
import java.util.logging.Logger;

/** Debug commands for inspecting and managing the item pickup tracker. */
public class PickupDebugCommand extends AbstractCommandCollection {

    public PickupDebugCommand() {
        super("pickup", "Inspect item pickup tracker state (debug)");
        this.addAliases("pd", "tracker");
        this.addSubCommand(new Status());
        this.addSubCommand(new ListAll());
        this.addSubCommand(new Nearby());
        this.addSubCommand(new Prune());
        this.addSubCommand(new Clear());
    }

    static class Status extends CommandBase {

        Status() {
            super("status", "Show tracker summary and config values");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            int totalTracked = ItemPickupTracker.size();
            Collection<ItemPickupTracker.TrackedItem> all = ItemPickupTracker.getAll();

            int fKeyCount = 0;
            int scoreCollectCount = 0;
            int invalidCount = 0;

            for (ItemPickupTracker.TrackedItem item : all) {
                if (!item.getRef().isValid()) {
                    invalidCount++;
                    continue;
                }
                if (item.isFKeyPickup()) {
                    fKeyCount++;
                }
                if (item.isScoreCollect()) {
                    scoreCollectCount++;
                }
            }

            context.sendMessage(Message.raw("=== Pickup Tracker Status ===").color(Color.ORANGE));
            context.sendMessage(Message.raw("  Total tracked: " + totalTracked).color(Color.WHITE));
            context.sendMessage(Message.raw("  F-Key items: " + fKeyCount).color(Color.CYAN));
            context.sendMessage(Message.raw("  Score-collect (coins): " + scoreCollectCount).color(Color.YELLOW));
            context.sendMessage(Message.raw("  Stale (invalid ref): " + invalidCount).color(
                    invalidCount > 0 ? Color.RED : Color.GRAY));
            context.sendMessage(Message.raw("=== Config ===").color(Color.ORANGE));
            context.sendMessage(Message.raw("  Item pickup radius: " + ItemPickupConfig.ITEM_PICKUP_RADIUS + " blocks")
                    .color(Color.WHITE));
            context.sendMessage(
                    Message.raw("  HUD proximity radius: " + ItemPickupConfig.HUD_PROXIMITY_RADIUS + " blocks")
                            .color(Color.WHITE));
            context.sendMessage(Message.raw("  Default coin collect radius: "
                    + ItemPickupConfig.DEFAULT_COIN_COLLECT_RADIUS + " blocks").color(Color.WHITE));
        }
    }

    static class ListAll extends AbstractPlayerCommand {

        ListAll() {
            super("list", "List all tracked item entities with positions and distances");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world) {

            listTrackedItems(context, ref, store, false);
        }
    }

    static class Nearby extends AbstractPlayerCommand {

        Nearby() {
            super("nearby", "List only tracked items within the pickup radius");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world) {

            listTrackedItems(context, ref, store, true);
        }
    }

    static class Prune extends CommandBase {

        private static final Logger LOGGER = Logger.getLogger(Prune.class.getName());

        Prune() {
            super("prune", "Force-prune stale (invalid ref) entries from the tracker");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            int sizeBefore = ItemPickupTracker.size();
            ItemPickupTracker.pruneInvalid();
            int sizeAfter = ItemPickupTracker.size();
            int pruned = sizeBefore - sizeAfter;

            if (pruned > 0) {
                context.sendMessage(Message.raw("Pruned " + pruned + " stale entries. Remaining: " + sizeAfter + ".")
                        .color(Color.GREEN));
                LOGGER.info("[PickupDebug] " + context.sender().getDisplayName() + " pruned " + pruned
                        + " stale entries (remaining: " + sizeAfter + ")");
            } else {
                context.sendMessage(Message.raw("No stale entries to prune. Total: " + sizeAfter + ".")
                        .color(Color.GRAY));
            }
        }
    }

    static class Clear extends CommandBase {

        private static final Logger LOGGER = Logger.getLogger(Clear.class.getName());

        Clear() {
            super("clear", "Clear all tracked entries");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            int sizeBefore = ItemPickupTracker.size();
            ItemPickupTracker.clear();
            context.sendMessage(Message.raw("Cleared all " + sizeBefore + " tracked entries.").color(Color.YELLOW));
            LOGGER.info("[PickupDebug] " + context.sender().getDisplayName() + " cleared all " + sizeBefore
                    + " tracked entries");
        }
    }

    /**
     * Lists tracked items with position/distance info, optionally filtered to
     * nearby only.
     * Truncates output at 25 entries.
     */
    private static void listTrackedItems(
            @Nonnull CommandContext context,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Store<EntityStore> store,
            boolean nearbyOnly) {

        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        Vector3d playerPos = (playerTransform != null) ? playerTransform.getPosition() : null;

        Collection<ItemPickupTracker.TrackedItem> all = ItemPickupTracker.getAll();
        if (all.isEmpty()) {
            context.sendMessage(Message.raw("No tracked items.").color(Color.GRAY));
            return;
        }

        String header = nearbyOnly ? "=== Nearby Tracked Items ===" : "=== All Tracked Items ===";
        context.sendMessage(Message.raw(header).color(Color.ORANGE));

        int displayed = 0;
        int maxDisplay = 25;

        for (ItemPickupTracker.TrackedItem tracked : all) {
            if (displayed >= maxDisplay) {
                int remaining = all.size() - displayed;
                if (remaining > 0) {
                    context.sendMessage(Message.raw("  ... and " + remaining + " more (truncated)")
                            .color(Color.GRAY));
                }
                break;
            }

            boolean valid = tracked.getRef().isValid();
            Vector3d itemPos = valid ? tracked.getPosition(store) : null;

            double distance = -1;
            if (playerPos != null && itemPos != null) {
                double dx = playerPos.x - itemPos.x;
                double dy = playerPos.y - itemPos.y;
                double dz = playerPos.z - itemPos.z;
                distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }

            if (nearbyOnly && (distance < 0 || distance > ItemPickupConfig.ITEM_PICKUP_RADIUS)) {
                continue;
            }

            StringBuilder sb = new StringBuilder("  ");
            sb.append(tracked.getItemId());

            if (tracked.isFKeyPickup()) {
                sb.append(" [FKey]");
            }
            if (tracked.isScoreCollect()) {
                sb.append(" [Score]");
            }
            if (!valid) {
                sb.append(" [INVALID]");
            }
            if (itemPos != null) {
                sb.append(String.format(" pos=(%.1f, %.1f, %.1f)", itemPos.x, itemPos.y, itemPos.z));
            }
            if (distance >= 0) {
                sb.append(String.format(" dist=%.2f", distance));
                if (distance <= ItemPickupConfig.ITEM_PICKUP_RADIUS) {
                    sb.append(" [IN RANGE]");
                }
            }

            Color lineColor;
            if (!valid) {
                lineColor = Color.RED;
            } else if (distance >= 0 && distance <= ItemPickupConfig.ITEM_PICKUP_RADIUS) {
                lineColor = Color.GREEN;
            } else {
                lineColor = Color.WHITE;
            }

            context.sendMessage(Message.raw(sb.toString()).color(lineColor));
            displayed++;
        }

        if (displayed == 0 && nearbyOnly) {
            context.sendMessage(Message.raw("  No tracked items within pickup radius ("
                    + ItemPickupConfig.ITEM_PICKUP_RADIUS + " blocks).").color(Color.GRAY));
        }

        context.sendMessage(Message.raw("Showing " + displayed + " / " + all.size() + " tracked items.")
                .color(Color.GRAY));
    }
}
