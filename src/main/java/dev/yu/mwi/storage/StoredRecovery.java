package dev.yu.mwi.storage;

import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** Recovery evidence and terminal receipts are retained independently of revision pruning. */
public record StoredRecovery(UUID sourceId, long version, UUID operationId, long profileRevision,
                             CompoundTag payload, String checksum) {
    public StoredRecovery { payload = payload.copy(); }
    @Override public CompoundTag payload() { return payload.copy(); }
}
