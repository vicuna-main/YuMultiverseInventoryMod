package dev.yu.mwi.service;

import dev.yu.mwi.domain.WorldIdentity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.LinkedHashSet;
import java.util.Set;

public final class WorldIdentities {
    private WorldIdentities() {
    }

    public static WorldIdentity from(ServerLevel level) {
        return from(level.dimension());
    }

    public static WorldIdentity from(ResourceKey<Level> dimension) {
        ResourceLocation location = dimension.location();
        String canonical = location.toString();
        LinkedHashSet<String> aliases = aliases(location);
        aliases.remove(canonical);
        return WorldIdentity.of(canonical, Set.copyOf(aliases));
    }

    /** Reconstructs stable aliases for a persisted canonical dimension id that is not currently loaded. */
    public static WorldIdentity fromName(String canonicalName) {
        ResourceLocation location = ResourceLocation.tryParse(canonicalName);
        if (location == null) {
            return new WorldIdentity(canonicalName);
        }
        LinkedHashSet<String> aliases = aliases(location);
        aliases.remove(canonicalName);
        return WorldIdentity.of(canonicalName, aliases);
    }

    private static LinkedHashSet<String> aliases(ResourceLocation location) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add(location.getPath());
        int slash = location.getPath().lastIndexOf('/');
        if (slash >= 0 && slash + 1 < location.getPath().length()) {
            aliases.add(location.getPath().substring(slash + 1));
        }
        aliases.add(location.getNamespace() + "_" + location.getPath().replace('/', '_'));
        if (Level.OVERWORLD.location().equals(location)) {
            aliases.add("world");
        } else if (Level.NETHER.location().equals(location)) {
            aliases.add("world_nether");
            aliases.add("DIM-1");
        } else if (Level.END.location().equals(location)) {
            aliases.add("world_the_end");
            aliases.add("DIM1");
        }
        return aliases;
    }
}
