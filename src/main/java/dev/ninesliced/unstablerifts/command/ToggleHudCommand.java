package dev.ninesliced.unstablerifts.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.hud.HudVisibilityService;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

public class ToggleHudCommand extends AbstractCommand {

    public ToggleHudCommand() {
        super("togglehud", "Toggle all HUD elements on or off");
        this.addAliases("hud");
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
            if (!ref.isValid()) {
                return;
            }

            Player player = store.getComponent(ref, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || playerRef == null) {
                context.sendMessage(Message.raw("Could not resolve player reference.").color(Color.RED));
                return;
            }

            boolean nowVisible = HudVisibilityService.toggle(player, playerRef);
            if (nowVisible) {
                context.sendMessage(Message.raw("HUD enabled.").color(Color.GREEN));
            } else {
                context.sendMessage(Message.raw("HUD hidden.").color(Color.YELLOW));
            }
        }, store.getExternalData().getWorld());
    }
}
