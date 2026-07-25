package dev.yu.mwi.service;

import dev.yu.mwi.api.AdapterIds;
import dev.yu.mwi.api.AdapterRegistry;
import dev.yu.mwi.api.SharableAdapter;
import dev.yu.mwi.config.RuntimeConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable adapter selection for one configuration generation. */
final class AdapterPlan {
    private static final Set<String> VANILLA_SHARES = Set.of(
            "inventory_contents", "armor_contents", "off_hand", "ender_chest", "xp", "lvl",
            "health", "hunger", "potion_effects", "bed_spawn", "last_location", "recipes",
            "advancements", "statistics");

    private AdapterPlan() {
    }

    static List<SharableAdapter> candidates(RuntimeConfig config, AdapterRegistry registry) {
        return registry.adapters().stream()
                .filter(adapter -> config.mvinvMode() != RuntimeConfig.MvinvMode.DELEGATE_VANILLA
                        || !VANILLA_SHARES.contains(adapter.id().getPath()))
                .filter(adapter -> config.model().shareHandling().restoreLastLocation()
                        || !adapter.id().equals(AdapterIds.LAST_LOCATION))
                .toList();
    }

    static Map<String, SharableAdapter> active(ServerPlayer player, List<SharableAdapter> candidates) {
        LinkedHashMap<String, SharableAdapter> result = new LinkedHashMap<>();
        candidates.forEach(adapter -> {
            if (adapter.available(player)) {
                result.put(adapter.id().getPath(), adapter);
            }
        });
        return Collections.unmodifiableMap(result);
    }
}
