package dev.yu.mwi.storage;

import net.minecraft.nbt.CompoundTag;

import java.time.Instant;

public record StoredProfile(CompoundTag payload, long revision, Instant savedAt, String checksum) {
    public StoredProfile {
        payload = payload.copy();
    }

    @Override
    public CompoundTag payload() {
        return payload.copy();
    }
}
