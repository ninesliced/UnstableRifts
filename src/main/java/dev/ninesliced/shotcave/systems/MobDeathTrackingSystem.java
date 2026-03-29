package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.ninesliced.shotcave.Shotcave;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks NPC entity removals to drive the dungeon mob kill counter.
 *
 * <p>When an NPC is added to the world, its UUID is cached. When it is removed
 * with {@link RemoveReason#REMOVE} (actual death/despawn — not a chunk unload),
 * the cached UUID is forwarded to {@link dev.ninesliced.shotcave.dungeon.GameManager#onDungeonMobKilled}
 * which increments the owning room's kill counter.</p>
 *
 * <p>{@link RemoveReason#UNLOAD} is intentionally ignored: refs temporarily
 * invalidated by chunk unloading do not count as kills.</p>
 */
public final class MobDeathTrackingSystem extends RefSystem<EntityStore> {

    /**
     * Maps each live NPC ref to its UUID so we can look up the UUID at removal time
     * without relying on component access (which may be unavailable during removal).
     * Entries are added on entity-add and removed on entity-remove (either reason).
     */
    private final ConcurrentHashMap<Ref<EntityStore>, UUID> refToUuid = new ConcurrentHashMap<>();

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComp == null) return;
        refToUuid.put(ref, uuidComp.getUuid());
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        UUID uuid = refToUuid.remove(ref);
        if (uuid == null || reason != RemoveReason.REMOVE) return;

        Shotcave shotcave = Shotcave.getInstance();
        if (shotcave == null) return;
        shotcave.getGameManager().onDungeonMobKilled(uuid);
    }
}
