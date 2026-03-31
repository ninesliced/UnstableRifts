package dev.ninesliced.unstablerifts.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.camera.TopCameraService;
import dev.ninesliced.unstablerifts.logging.UnstableRiftsLog;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class TopCameraCommand extends AbstractCommand {

    private static final HytaleLogger LOGGER = UnstableRiftsLog.forModule("Command");

    private final UnstableRifts plugin;

    public TopCameraCommand(@Nonnull UnstableRifts plugin) {
        super("topcamera", "Toggle the top-down camera");
        this.addAliases("tcam");
        this.plugin = plugin;
    }

    @Override
    protected String generatePermissionNode() {
        return "";
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Nonnull
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("This command must be run by a player.").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            return CompletableFuture.completedFuture(null);
        }

        Store<EntityStore> store = ref.getStore();
        return CompletableFuture.runAsync(() -> {
            try {
                if (!ref.isValid()) {
                    return;
                }

                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null) {
                    context.sendMessage(Message.raw("Could not resolve player reference.").color(Color.RED));
                    return;
                }

                TopCameraService service = plugin.getCameraService();
                boolean enabled = service.toggle(playerRef);

                if (enabled) {
                    context.sendMessage(Message.raw("Top camera enabled.").color(Color.GREEN));
                } else {
                    context.sendMessage(Message.raw("Top camera disabled and reset to default.").color(Color.YELLOW));
                }
            } catch (Exception e) {
                LOGGER.at(Level.SEVERE).withCause(e).log("Failed to execute /unstablerifts topcamera");
                context.sendMessage(Message.raw("Top camera failed: " + e.getMessage()).color(Color.RED));
            }
        }, store.getExternalData().getWorld());
    }
}
