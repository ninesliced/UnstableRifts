package dev.ninesliced.unstablerifts.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ninesliced.unstablerifts.coin.CoinScoreService;
import dev.ninesliced.unstablerifts.logging.UnstableRiftsLog;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Debug commands for viewing and modifying coin scores.
 */
public class CoinCommand extends AbstractCommandCollection {

    public CoinCommand() {
        super("coins", "View or modify coin scores (debug)");
        this.addAliases("coin", "score");
        this.addSubCommand(new Get());
        this.addSubCommand(new Set());
        this.addSubCommand(new Add());
        this.addSubCommand(new Reset());
        this.addSubCommand(new ListAll());
    }

    static class Get extends AbstractPlayerCommand {

        Get() {
            super("get", "Show your current coin score");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world) {

            int score = CoinScoreService.getScore(playerRef.getUuid());
            context.sendMessage(Message.raw("Your coin score: " + score).color(Color.YELLOW));
        }
    }

    static class Set extends AbstractPlayerCommand {

        private static final HytaleLogger LOGGER = UnstableRiftsLog.forModule("Command");

        @Nonnull
        private final RequiredArg<Integer> amountArg = this.withRequiredArg(
                "amount", "The coin score value to set", ArgTypes.INTEGER);

        Set() {
            super("set", "Set your coin score to a specific value");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world) {

            int amount = this.amountArg.get(context);
            int newScore = CoinScoreService.setScore(playerRef.getUuid(), amount);
            context.sendMessage(Message.raw("Coin score set to " + newScore + ".").color(Color.GREEN));
            LOGGER.at(Level.INFO).log("[CoinCommand] %s set their coin score to %d",
                    playerRef.getUsername(), newScore);
        }
    }

    static class Add extends AbstractPlayerCommand {

        private static final HytaleLogger LOGGER = UnstableRiftsLog.forModule("Command");

        @Nonnull
        private final RequiredArg<Integer> amountArg = this.withRequiredArg(
                "amount", "The number of coins to add", ArgTypes.INTEGER);

        Add() {
            super("add", "Add coins to your score");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world) {

            int amount = this.amountArg.get(context);
            if (amount <= 0) {
                context.sendMessage(Message.raw("Amount must be a positive integer.").color(Color.RED));
                return;
            }
            int newTotal = CoinScoreService.addCoins(playerRef.getUuid(), amount);
            context.sendMessage(Message.raw("Added " + amount + " coins. New total: " + newTotal + ".")
                    .color(Color.GREEN));
            LOGGER.at(Level.INFO).log("[CoinCommand] %s added %d coins (total: %d)",
                    playerRef.getUsername(), amount, newTotal);
        }
    }

    static class Reset extends AbstractPlayerCommand {

        private static final HytaleLogger LOGGER = UnstableRiftsLog.forModule("Command");

        Reset() {
            super("reset", "Reset your coin score to 0");
        }

        @Override
        protected void execute(
                @Nonnull CommandContext context,
                @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref,
                @Nonnull PlayerRef playerRef,
                @Nonnull World world) {

            CoinScoreService.reset(playerRef.getUuid());
            context.sendMessage(Message.raw("Coin score reset to 0.").color(Color.YELLOW));
            LOGGER.at(Level.INFO).log("[CoinCommand] %s reset their coin score", playerRef.getUsername());
        }
    }

    static class ListAll extends CommandBase {

        ListAll() {
            super("all", "List all players' coin scores");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            Map<UUID, Integer> allScores = CoinScoreService.getAllScores();
            if (allScores.isEmpty()) {
                context.sendMessage(Message.raw("No coin scores recorded.").color(Color.GRAY));
            } else {
                context.sendMessage(Message.raw("=== All Coin Scores ===").color(Color.ORANGE));
                allScores.forEach((playerUuid, score) -> context
                        .sendMessage(Message.raw("  " + playerUuid + ": " + score).color(Color.WHITE)));
                context.sendMessage(Message.raw("Total players: " + allScores.size()).color(Color.GRAY));
            }
        }
    }
}
