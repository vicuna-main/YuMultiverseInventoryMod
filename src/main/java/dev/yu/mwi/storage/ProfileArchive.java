package dev.yu.mwi.storage;

import dev.yu.mwi.domain.ProfileKey;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

/** Immutable request to archive the current canonical profile in the same durable batch. */
public record ProfileArchive(ProfileKey key, String category, String reason, CompoundTag details) {
    public ProfileArchive {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(reason, "reason");
        details = Objects.requireNonNull(details, "details").copy();
    }

    @Override
    public CompoundTag details() {
        return details.copy();
    }
}
