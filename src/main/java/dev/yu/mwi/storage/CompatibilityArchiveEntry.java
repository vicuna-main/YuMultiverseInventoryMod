package dev.yu.mwi.storage;

import dev.yu.mwi.domain.ProfileKey;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

public record CompatibilityArchiveEntry(
        String entryId,
        ProfileKey sourceKey,
        long archiveRevision,
        CompoundTag issue,
        CompoundTag document) {
    public CompatibilityArchiveEntry {
        entryId = Objects.requireNonNull(entryId, "entryId");
        sourceKey = Objects.requireNonNull(sourceKey, "sourceKey");
        issue = Objects.requireNonNull(issue, "issue").copy();
        document = Objects.requireNonNull(document, "document").copy();
    }

    @Override
    public CompoundTag issue() {
        return issue.copy();
    }

    @Override
    public CompoundTag document() {
        return document.copy();
    }
}
