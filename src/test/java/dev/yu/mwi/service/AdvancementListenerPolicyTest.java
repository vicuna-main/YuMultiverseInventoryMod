package dev.yu.mwi.service;

import dev.yu.mwi.adapter.vanilla.VanillaAdapters;
import dev.yu.mwi.api.AdapterIds;
import dev.yu.mwi.api.SharableAdapter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementListenerPolicyTest {
    @Test
    void unknownAdaptersRemainConservativelySuspended() {
        SharableAdapter unknown = new TestAdapter(AdapterIds.id("third_party"));

        assertTrue(unknown.requiresAdvancementListenerSuspension());
        assertTrue(LiveStateCoordinator.requiresAdvancementListenerSuspension(List.of(unknown)));
    }

    @Test
    void auditedDirectStateAdaptersDoNotRequireSuspension() {
        Map<ResourceLocation, SharableAdapter> adapters = adaptersById();
        Set<ResourceLocation> directStateAdapters = Set.of(
                AdapterIds.XP,
                AdapterIds.LVL,
                AdapterIds.HEALTH,
                AdapterIds.HUNGER,
                AdapterIds.POTION_EFFECTS,
                AdapterIds.BED_SPAWN,
                AdapterIds.RECIPES,
                AdapterIds.STATISTICS);

        for (ResourceLocation id : directStateAdapters) {
            assertFalse(adapters.get(id).requiresAdvancementListenerSuspension(), id.toString());
        }
        assertFalse(LiveStateCoordinator.requiresAdvancementListenerSuspension(
                directStateAdapters.stream().map(adapters::get).toList()));
    }

    @Test
    void equipmentTeleportAndAdvancementPathsRemainSuspended() {
        Map<ResourceLocation, SharableAdapter> adapters = adaptersById();
        Set<ResourceLocation> eventCapableAdapters = Set.of(
                AdapterIds.INVENTORY_CONTENTS,
                AdapterIds.ARMOR_CONTENTS,
                AdapterIds.OFF_HAND,
                AdapterIds.ENDER_CHEST,
                AdapterIds.LAST_LOCATION,
                AdapterIds.ADVANCEMENTS);

        for (ResourceLocation id : eventCapableAdapters) {
            assertTrue(adapters.get(id).requiresAdvancementListenerSuspension(), id.toString());
        }
        assertTrue(LiveStateCoordinator.requiresAdvancementListenerSuspension(
                eventCapableAdapters.stream().map(adapters::get).toList()));
    }

    @Test
    void oneConservativeAdapterProtectsTheWholeMutation() {
        Map<ResourceLocation, SharableAdapter> adapters = adaptersById();

        assertTrue(LiveStateCoordinator.requiresAdvancementListenerSuspension(List.of(
                adapters.get(AdapterIds.POTION_EFFECTS),
                adapters.get(AdapterIds.LAST_LOCATION))));
    }

    private static Map<ResourceLocation, SharableAdapter> adaptersById() {
        return VanillaAdapters.createAll().stream()
                .collect(Collectors.toUnmodifiableMap(SharableAdapter::id, Function.identity()));
    }

    private static final class TestAdapter implements SharableAdapter {
        private final ResourceLocation id;

        private TestAdapter(ResourceLocation id) {
            this.id = id;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public CompoundTag capture(ServerPlayer player, HolderLookup.Provider lookup) {
            return new CompoundTag();
        }

        @Override
        public void clear(ServerPlayer player) {
        }

        @Override
        public void apply(ServerPlayer player, CompoundTag data, HolderLookup.Provider lookup) {
        }

        @Override
        public void sync(ServerPlayer player) {
        }
    }
}
