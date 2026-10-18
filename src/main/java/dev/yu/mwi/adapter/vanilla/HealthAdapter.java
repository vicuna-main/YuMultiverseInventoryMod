package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterIds;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps absorption's persistent capacity with its health scope, without changing max-health attributes. */
public final class HealthAdapter extends AbstractVanillaAdapter {
    private static final String HEALTH = "health";
    private static final String ABSORPTION = "absorption";
    private static final int MAX_DEFERRED_TICKS = 5;
    private final Map<UUID, PendingHealth> pending = new HashMap<>();
    private final Map<UUID, ValidatedHealth> applying = new HashMap<>();
    private final java.util.Set<UUID> legacyCarry = new java.util.HashSet<>();

    public HealthAdapter() {
        super(AdapterIds.HEALTH);
    }

    @Override
    public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
        CompoundTag data = new CompoundTag();
        data.putFloat(HEALTH, player.getHealth());
        data.putFloat(ABSORPTION, player.getAbsorptionAmount());
        data.putInt("schema", 2);
        data.put(AbsorptionCapacity.KEY, AbsorptionCapacity.capture(player.getAttribute(Attributes.MAX_ABSORPTION)));
        validatePayload(data, id().toString());
        return data;
    }

    @Override
    public void clear(ServerPlayer player) {
        pending.remove(player.getUUID());
        applying.remove(player.getUUID());
        legacyCarry.remove(player.getUUID());
        AbsorptionCapacity.clear(player.getAttribute(Attributes.MAX_ABSORPTION));
        player.setAbsorptionAmount(0.0F);
        player.setHealth(player.getMaxHealth());
    }

    @Override
    public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        ValidatedHealth values = validatePayload(data, id().toString());
        var attribute = player.getAttribute(Attributes.MAX_ABSORPTION);
        if (data.contains(AbsorptionCapacity.KEY)) {
            CompoundTag capacity = data.getCompound(AbsorptionCapacity.KEY);
            AbsorptionCapacity.apply(attribute, capacity);
            if (capacity.getBoolean("legacy_carry")) legacyCarry.add(player.getUUID());
            else legacyCarry.remove(player.getUUID());
        } else {
            AbsorptionCapacity.clear(attribute);
            legacyCarry.add(player.getUUID());
        }
        applying.put(player.getUUID(), values);
        applyAmounts(player, values);
    }

    @Override
    public void dependenciesApplied(ServerPlayer player) {
        ValidatedHealth values = applying.remove(player.getUUID());
        if (values == null) return;
        if (legacyCarry.remove(player.getUUID()))
            AbsorptionCapacity.preserveExistingShield(player.getAttribute(Attributes.MAX_ABSORPTION), values.absorption());
        applyAmounts(player, values);
        if (player.getAbsorptionAmount() != values.absorption())
            throw new IllegalStateException("health.absorption cannot be restored: max_absorption source dependency is missing");
    }

    private void applyAmounts(ServerPlayer player, ValidatedHealth values) {
        float health = values.health();
        float absorption = values.absorption();
        player.setAbsorptionAmount(absorption);
        if (health <= player.getMaxHealth()) {
            pending.remove(player.getUUID());
            player.setHealth(health);
        } else {
            player.setHealth(player.getMaxHealth());
            pending.put(player.getUUID(), new PendingHealth(health, absorption, 0));
        }
    }

    static ValidatedHealth validatePayload(CompoundTag data, String adapterId) {
        NbtPayload.requireData(data, adapterId);
        float health = NbtPayload.requireFloat(data, HEALTH, adapterId);
        float absorption = NbtPayload.requireFloat(data, ABSORPTION, adapterId);
        if (data.contains(AbsorptionCapacity.KEY) || data.contains("schema")) {
            if (!data.contains("schema", Tag.TAG_INT) || data.getInt("schema") != 2
                    || !data.contains(AbsorptionCapacity.KEY, Tag.TAG_COMPOUND))
                throw NbtPayload.invalid(adapterId, "invalid health dependency schema");
            AbsorptionCapacity.validate(data.getCompound(AbsorptionCapacity.KEY));
        }
        // Zero health is a legitimate live state during the death/respawn lifecycle. Rejecting it
        // after capture made MWI capable of persisting a profile that it could not restore.
        if (health < 0.0F || health > 1_000_000.0F) {
            throw NbtPayload.invalid(adapterId, "health must be finite and in [0, 1000000]");
        }
        if (absorption < 0.0F || absorption > 1_000_000.0F) {
            throw NbtPayload.invalid(adapterId, "absorption must be finite and in [0, 1000000]");
        }
        return new ValidatedHealth(health, absorption);
    }

    record ValidatedHealth(float health, float absorption) {
    }

    @Override
    public dev.yu.mwi.api.RestoreVerification verifyRestored(ServerPlayer player, CompoundTag expected,
            CompoundTag actual, HolderLookup.Provider lookup) {
        try {
            validatePayload(expected, id().toString()); validatePayload(actual, id().toString());
            CompoundTag comparison = actual.copy();
            boolean legacy = !expected.contains(AbsorptionCapacity.KEY);
            if (legacy) { comparison.remove("schema"); comparison.remove(AbsorptionCapacity.KEY); }
            if (!expected.equals(comparison)) return new dev.yu.mwi.api.RestoreVerification(
                    dev.yu.mwi.api.RestoreVerification.Status.DIFFERENT, "health-absorption-capacity-v2",
                    dev.yu.mwi.api.NbtDifference.first(expected, comparison), null);
            return new dev.yu.mwi.api.RestoreVerification(legacy && !expected.equals(actual)
                    ? dev.yu.mwi.api.RestoreVerification.Status.SEMANTIC_EQUIVALENT
                    : dev.yu.mwi.api.RestoreVerification.Status.EXACT,
                    "health-absorption-capacity-v2", "", actual);
        } catch (IllegalArgumentException invalid) {
            return new dev.yu.mwi.api.RestoreVerification(dev.yu.mwi.api.RestoreVerification.Status.UNSUPPORTED,
                    "health-absorption-capacity-v2", invalid.getMessage(), null);
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
        var capacity = player.getAttribute(Attributes.MAX_ABSORPTION);
        if (AbsorptionCapacity.hasCarry(capacity)) AbsorptionCapacity.preserveExistingShield(capacity, player.getAbsorptionAmount());
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
        applying.remove(player.getUUID());
        legacyCarry.remove(player.getUUID());
    }

    private record PendingHealth(float health, float absorption, int ticks) {
    }

    enum DeferredAction {
        WAIT,
        RESTORE,
        CLAMP
    }
}
