package dev.yu.mwi.adapter.vanilla;

import dev.yu.mwi.api.SharableAdapter;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

abstract class AbstractVanillaAdapter implements SharableAdapter {
    private final ResourceLocation id;

    protected AbstractVanillaAdapter(ResourceLocation id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public final ResourceLocation id() {
        return id;
    }
}
