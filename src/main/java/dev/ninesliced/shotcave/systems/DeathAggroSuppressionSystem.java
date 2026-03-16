package dev.ninesliced.shotcave.systems;

import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import dev.ninesliced.shotcave.Shotcave;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Keeps custom-dead players out of NPC aggro systems that only understand the engine death marker.
 */
public final class DeathAggroSuppressionSystem extends EntityTickingSystem<EntityStore> {

    private static final double AGGRO_OVERRIDE_SECONDS = 0.5d;

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void tick(float dt, int index,
                     @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Shotcave shotcave = Shotcave.getInstance();
        if (shotcave == null) {
            return;
        }

        List<Ref<EntityStore>> deadPlayerRefs = shotcave.getGameManager().getDeadPlayerRefsInStore(store);
        if (deadPlayerRefs.isEmpty()) {
            return;
        }

        NPCEntity npcEntity = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
        if (npcEntity == null) {
            return;
        }

        Role role = npcEntity.getRole();
        if (role == null) {
            return;
        }

        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        if (!npcRef.isValid()) {
            return;
        }

        TargetMemory targetMemory = store.getComponent(npcRef, TargetMemory.getComponentType());

        for (Ref<EntityStore> deadPlayerRef : deadPlayerRefs) {
            if (deadPlayerRef == null || !deadPlayerRef.isValid() || deadPlayerRef.equals(npcRef)) {
                continue;
            }

            role.getWorldSupport().overrideAttitude(deadPlayerRef, Attitude.IGNORE, AGGRO_OVERRIDE_SECONDS);
            clearTargetMemory(targetMemory, deadPlayerRef);
            clearMarkedTargets(role, deadPlayerRef);
        }
    }

    private void clearTargetMemory(TargetMemory targetMemory, @Nonnull Ref<EntityStore> deadPlayerRef) {
        if (targetMemory == null) {
            return;
        }

        targetMemory.getKnownHostiles().remove(deadPlayerRef.getIndex());
        targetMemory.getKnownHostilesList().remove(deadPlayerRef);
        targetMemory.getKnownFriendlies().remove(deadPlayerRef.getIndex());
        targetMemory.getKnownFriendliesList().remove(deadPlayerRef);

        if (deadPlayerRef.equals(targetMemory.getClosestHostile())) {
            targetMemory.setClosestHostile(null);
        }
    }

    private void clearMarkedTargets(@Nonnull Role role, @Nonnull Ref<EntityStore> deadPlayerRef) {
        boolean clearedAny = false;
        int slotCount = role.getMarkedEntitySupport().getMarkedEntitySlotCount();
        for (int slot = 0; slot < slotCount; slot++) {
            if (deadPlayerRef.equals(role.getMarkedEntitySupport().getMarkedEntityRef(slot))) {
                role.getMarkedEntitySupport().clearMarkedEntity(slot);
                clearedAny = true;
            }
        }

        if (clearedAny) {
            role.getEntitySupport().clearTargetPlayerActiveTasks();
        }
    }
}