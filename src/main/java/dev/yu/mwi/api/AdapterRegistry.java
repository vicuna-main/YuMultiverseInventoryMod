package dev.yu.mwi.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Thread-safe, insertion-ordered registry of canonical adapter ids and aliases. */
public final class AdapterRegistry {
    private final Map<ResourceLocation, SharableAdapter> canonical = new LinkedHashMap<>();
    private final Map<ResourceLocation, SharableAdapter> lookup = new LinkedHashMap<>();

    public synchronized void register(SharableAdapter adapter) {
        registerAll(List.of(Objects.requireNonNull(adapter, "adapter")));
    }

    public synchronized void registerAll(Collection<? extends SharableAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        Map<ResourceLocation, SharableAdapter> newCanonical = new LinkedHashMap<>(canonical);
        Map<ResourceLocation, SharableAdapter> newLookup = new LinkedHashMap<>(lookup);
        for (SharableAdapter adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter");
            ResourceLocation id = Objects.requireNonNull(adapter.id(), "adapter.id()");
            Set<ResourceLocation> keys = validatedKeys(adapter, id);
            for (ResourceLocation key : keys) {
                SharableAdapter existing = newLookup.get(key);
                if (existing != null) {
                    throw new IllegalStateException(
                            "adapter key " + key + " is already registered by " + existing.id());
                }
            }
            newCanonical.put(id, adapter);
            for (ResourceLocation key : keys) {
                newLookup.put(key, adapter);
            }
        }
        canonical.clear();
        canonical.putAll(newCanonical);
        lookup.clear();
        lookup.putAll(newLookup);
    }

    public synchronized Optional<SharableAdapter> find(ResourceLocation idOrAlias) {
        Objects.requireNonNull(idOrAlias, "idOrAlias");
        return Optional.ofNullable(lookup.get(idOrAlias));
    }

    public synchronized SharableAdapter require(ResourceLocation idOrAlias) {
        return find(idOrAlias).orElseThrow(
                () -> new NoSuchElementException("no sharable adapter registered for " + idOrAlias));
    }

    public synchronized boolean contains(ResourceLocation idOrAlias) {
        Objects.requireNonNull(idOrAlias, "idOrAlias");
        return lookup.containsKey(idOrAlias);
    }

    public synchronized Collection<SharableAdapter> adapters() {
        return Collections.unmodifiableList(new ArrayList<>(canonical.values()));
    }

    public synchronized int size() {
        return canonical.size();
    }

    private static Set<ResourceLocation> validatedKeys(SharableAdapter adapter, ResourceLocation id) {
        Set<ResourceLocation> keys = new LinkedHashSet<>();
        keys.add(id);
        Set<ResourceLocation> aliases = Objects.requireNonNull(adapter.aliases(), "adapter.aliases()");
        for (ResourceLocation alias : aliases) {
            Objects.requireNonNull(alias, "adapter alias");
            if (!keys.add(alias)) {
                throw new IllegalArgumentException("duplicate id or alias on adapter " + id + ": " + alias);
            }
        }
        return keys;
    }
}
