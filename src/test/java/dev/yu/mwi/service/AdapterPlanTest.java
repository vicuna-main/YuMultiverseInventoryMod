package dev.yu.mwi.service;

import dev.yu.mwi.adapter.vanilla.VanillaAdapters;
import dev.yu.mwi.api.AdapterRegistry;
import dev.yu.mwi.api.SharableAdapter;
import dev.yu.mwi.config.MwiConfig;
import dev.yu.mwi.config.RuntimeConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterPlanTest {
    @Test
    void lastLocationIsDisabledByDefaultAndCanBeExplicitlyEnabled() {
        AdapterRegistry registry = new AdapterRegistry();
        VanillaAdapters.registerAll(registry);
        RuntimeConfig defaults = RuntimeConfig.defaults();

        assertFalse(candidateIds(defaults, registry).contains("last_location"));

        MwiConfig model = defaults.model();
        MwiConfig.ShareHandling handling = model.shareHandling();
        MwiConfig enabledModel = new MwiConfig(
                new MwiConfig.ShareHandling(
                        handling.enableGameModeProfiles(), handling.defaultUngroupedWorlds(),
                        handling.strictUnknownModSlots(), handling.alwaysWriteWorldProfile(), true,
                        handling.defaultGroupName()),
                model.shareAliases(), model.groups(), model.migration());
        RuntimeConfig enabled = new RuntimeConfig(
                defaults.schemaVersion(), enabledModel, defaults.storage(), defaults.mvinvMode(),
                defaults.mvinvDataDirectory());

        assertTrue(candidateIds(enabled, registry).contains("last_location"));
    }

    @Test
    void delegateVanillaExcludesVanillaSharesFromManagedCatalog() {
        AdapterRegistry registry = new AdapterRegistry();
        VanillaAdapters.registerAll(registry);
        registry.register(new StubAdapter());
        RuntimeConfig defaults = RuntimeConfig.defaults();
        RuntimeConfig delegated = new RuntimeConfig(
                defaults.schemaVersion(), defaults.model(), defaults.storage(),
                RuntimeConfig.MvinvMode.DELEGATE_VANILLA, defaults.mvinvDataDirectory());

        assertEquals(
                java.util.List.of("test_slot"),
                candidateIds(delegated, registry));
    }

    private static java.util.List<String> candidateIds(RuntimeConfig config, AdapterRegistry registry) {
        return AdapterPlan.candidates(config, registry).stream().map(adapter -> adapter.id().getPath()).toList();
    }

    private static final class StubAdapter implements SharableAdapter {
        @Override
        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath("yu_multiverse_inventory", "test_slot");
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
