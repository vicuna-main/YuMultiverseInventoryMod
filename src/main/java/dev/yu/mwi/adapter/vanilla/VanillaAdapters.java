package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.AdapterRegistry;
import dev.yu.mwi.api.SharableAdapter;
import java.util.List;
import java.util.Objects;

/** Factory and registration entry point for all built-in NeoForge adapters. */
public final class VanillaAdapters {
    private VanillaAdapters() {
    }

    public static List<SharableAdapter> createAll() {
        return List.of(
                new VanillaInventoryAdapter(),
                new ArmorAdapter(),
                new OffhandAdapter(),
                new EnderChestAdapter(),
                new ExperiencePointsAdapter(),
                new ExperienceLevelAdapter(),
                // Effects precede health so Health Boost is active before a high health value is restored.
                new PotionEffectsAdapter(),
                new HealthAdapter(),
                new FoodAdapter(),
                new RespawnLocationAdapter(),
                new LastLocationAdapter(),
                new RecipeBookAdapter(),
                new AdvancementAdapter(),
                new StatisticsAdapter());
    }

    public static void registerAll(AdapterRegistry registry) {
        Objects.requireNonNull(registry, "registry").registerAll(createAll());
    }
}
