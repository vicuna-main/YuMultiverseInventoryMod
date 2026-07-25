package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Captures current health and absorption without changing max-health attributes. */
public final class HealthAdapter extends AbstractVanillaAdapter {
    private static final String HEALTH = "health";
    private static final String ABSORPTION = "absorption";
    private static final int MAX_DEFERRED_TICKS = 5;
    private final Map<UUID, PendingHealth> pending = new HashMap<>();

    public HealthAdapter() {
        super(AdapterIds.HEALTH);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        PendingHealth deferred = pending.get(player.getUUID());
        CompoundTag data = new CompoundTag();
        data.putFloat(HEALTH, deferred == null ? player.getHealth() : deferred.health());
        data.putFloat(ABSORPTION, deferred == null ? player.getAbsorptionAmount() : deferred.absorption());
        return data;
    }

    @Override
    public void clear(ServerPlayer player) {
        pending.remove(player.getUUID());
        player.setAbsorptionAmount(0.0F);
        player.setHealth(player.getMaxHealth());
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        NbtPayload.requireData(data, id().toString());
        float health = NbtPayload.requireFloat(data, HEALTH, id().toString());
        float absorption = NbtPayload.requireFloat(data, ABSORPTION, id().toString());
        if (!Float.isFinite(health) || health <= 0.0F || health > 1_000_000.0F) {
            throw NbtPayload.invalid(id().toString(), "health must be finite and in (0, 1000000]");
        }
        if (!Float.isFinite(absorption) || absorption < 0.0F || absorption > 1_000_000.0F) {
            throw NbtPayload.invalid(id().toString(), "absorption must be finite and in [0, 1000000]");
        }
        player.setAbsorptionAmount(absorption);
        if (health <= player.getMaxHealth()) {
            pending.remove(player.getUUID());
            player.setHealth(health);
        } else {
            player.setHealth(player.getMaxHealth());
            pending.put(player.getUUID(), new PendingHealth(health, absorption, 0));
        }
    }

    @Override
    public void sync(ServerPlayer player) {
        PlayerSync.healthAndFood(player);
    }

    @Override
    public boolean requiresAdvancementListenerSuspension() {
        return false;
    }

    @Override
    public void serverTick(ServerPlayer player) {
        PendingHealth deferred = pending.get(player.getUUID());
        if (deferred == null) {
            return;
        }
        DeferredAction action = deferredAction(deferred.health(), player.getMaxHealth(), deferred.ticks());
        if (action == DeferredAction.RESTORE) {
            player.setAbsorptionAmount(deferred.absorption());
            player.setHealth(deferred.health());
            pending.remove(player.getUUID());
            PlayerSync.healthAndFood(player);
            return;
        }
        if (action == DeferredAction.CLAMP) {
            // A health profile can legitimately outlive max-health granted by equipment or another
            // world. Vanilla semantics cap current health; this is not profile corruption and must
            // never quarantine an otherwise valid player session.
            player.setAbsorptionAmount(deferred.absorption());
            player.setHealth(player.getMaxHealth());
            pending.remove(player.getUUID());
            PlayerSync.healthAndFood(player);
            return;
        }
        pending.put(player.getUUID(), new PendingHealth(
                deferred.health(), deferred.absorption(), deferred.ticks() + 1));
    }

    static DeferredAction deferredAction(float health, float maxHealth, int elapsedTicks) {
        if (health <= maxHealth) {
            return DeferredAction.RESTORE;
        }
        return elapsedTicks + 1 >= MAX_DEFERRED_TICKS ? DeferredAction.CLAMP : DeferredAction.WAIT;
    }

    @Override
    public void sessionClosed(ServerPlayer player) {
        pending.remove(player.getUUID());
    }

    private record PendingHealth(float health, float absorption, int ticks) {
    }

    enum DeferredAction {
        WAIT,
        RESTORE,
        CLAMP
    }
}
