package dev.ninesliced.shotcave.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.shotcave.Shotcave;
import dev.ninesliced.shotcave.dungeon.DungeonConfig;
import dev.ninesliced.shotcave.dungeon.DungeonInstanceService;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class DungeonCommand extends AbstractCommand {

    private final Shotcave plugin;
    private final RequiredArg<String> dungeonArg;

    public DungeonCommand(@Nonnull Shotcave plugin) {
        super("dungeon", "Create and enter a Shotcave dungeon instance");
        this.addAliases("dg");
        this.plugin = plugin;
        this.dungeonArg = this.withRequiredArg("dungeon", "Dungeon selector from dungeon.json", ArgTypes.STRING)
                .suggest((sender, entered, numParametersTyped, result) -> {
                    String prefix = currentToken(entered).toLowerCase(Locale.ROOT);
                    for (String selector : plugin.loadDungeonConfig().getLevelSelectors()) {
                        if (prefix.isBlank() || selector.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                            result.suggest(selector);
                        }
                    }
                });
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

        String dungeonSelection = this.dungeonArg.get(context);
        DungeonConfig config = plugin.loadDungeonConfig();
        if (config.getLevels().isEmpty()) {
            context.sendMessage(Message.raw("No dungeons defined in " + plugin.getDungeonConfigPath().toAbsolutePath() + ".").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        DungeonConfig.LevelConfig levelConfig = config.findLevel(dungeonSelection);
        if (levelConfig == null) {
            List<String> selectors = config.getLevelSelectors();
            String options = selectors.isEmpty() ? "(none)" : String.join(", ", selectors);
            context.sendMessage(Message.raw("Unknown dungeon '" + dungeonSelection + "'. Available: " + options).color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            context.sendMessage(Message.raw("Could not resolve player reference.").color(Color.RED));
            return CompletableFuture.completedFuture(null);
        }

        Store<EntityStore> store = ref.getStore();
        World currentWorld = store.getExternalData().getWorld();

        return CompletableFuture.runAsync(() -> {
            try {
                if (!ref.isValid()) {
                    return;
                }

                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null) {
                    context.sendMessage(Message.raw("Could not resolve player.").color(Color.RED));
                    return;
                }

                DungeonInstanceService instanceService = this.plugin.getDungeonInstanceService();
                var returnPoint = DungeonInstanceService.captureReturnPoint(store, ref);

                context.sendMessage(Message.raw("Creating dungeon instance: " + levelConfig.getName()).color(Color.YELLOW));
                CompletableFuture<World> readyFuture = instanceService.spawnGeneratedInstance(
                        currentWorld,
                        returnPoint,
                        levelConfig,
                        status -> context.sendMessage(Message.raw(status).color(status.startsWith("Dungeon ready") ? Color.GREEN : Color.YELLOW))
                );

                instanceService.sendPlayerToLoadingInstance(ref, store, readyFuture, returnPoint);

            } catch (Exception e) {
                context.sendMessage(Message.raw("Dungeon creation failed: " + e.getMessage()).color(Color.RED));
            }
        }, currentWorld);
    }

    @Nonnull
    private static String currentToken(@Nonnull String entered) {
        if (entered.isBlank() || Character.isWhitespace(entered.charAt(entered.length() - 1))) {
            return "";
        }
        int split = entered.lastIndexOf(' ');
        return split == -1 ? entered : entered.substring(split + 1);
    }
}
