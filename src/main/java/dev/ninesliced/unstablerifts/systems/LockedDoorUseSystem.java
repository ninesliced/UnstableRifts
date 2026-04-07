package dev.ninesliced.unstablerifts.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import dev.ninesliced.unstablerifts.UnstableRifts;
import dev.ninesliced.unstablerifts.dungeon.DoorService;
import dev.ninesliced.unstablerifts.dungeon.Game;
import dev.ninesliced.unstablerifts.dungeon.Level;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles right-click interaction on KEY-mode locked door blocks.
 */
public final class LockedDoorUseSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {

    public LockedDoorUseSystem() {
        super(UseBlockEvent.Pre.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull UseBlockEvent.Pre event) {
        Vector3i targetBlock = event.getTargetBlock();
        if (targetBlock == null) {
            return;
        }

        Ref<EntityStore> playerEntityRef = archetypeChunk.getReferenceTo(index);
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        PlayerRef playerRef = store.getComponent(playerEntityRef, PlayerRef.getComponentType());
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        DeathComponent death = store.getComponent(playerEntityRef, DeathComponent.getComponentType());
        if (death != null && death.isDead()) {
            return;
        }

        UnstableRifts plugin = UnstableRifts.getInstance();
        if (plugin == null) {
            return;
        }

        Game game = plugin.getGameManager().findGameForPlayer(playerRef.getUuid());
        if (game == null) {
            return;
        }

        World world = game.getInstanceWorld();
        if (world == null) {
            return;
        }

        Level level = game.getCurrentLevel();
        if (level == null) {
            return;
        }

        DoorService.KeyDoorTarget keyDoorTarget = plugin.getDoorService().findKeyDoorTarget(level, targetBlock);
        if (keyDoorTarget == null) {
            return;
        }

        event.setCancelled(true);

        if (!game.useKey()) {
            sendNotification(playerRef,
                    "This door is locked. Search for the key inside the dungeon.",
                    "door_locked");
            return;
        }

        plugin.getDoorService().unlockKeyDoor(level, world, keyDoorTarget);

        int remainingKeys = game.getTeamKeys();
        sendNotification(playerRef,
                "Door unlocked! (" + remainingKeys + " key" + (remainingKeys != 1 ? "s" : "") + " left)",
                "door_unlocked");
    }

    private void sendNotification(@Nonnull PlayerRef playerRef,
                                  @Nonnull String text,
                                  @Nonnull String notificationId) {
        try {
            NotificationUtil.sendNotification(
                    playerRef.getPacketHandler(),
                    Message.raw(text),
                    null,
                    notificationId);
        } catch (Exception ignored) {
            // Best-effort feedback only.
        }
    }
}
