package dev.yu.mwi.storage;

import net.minecraft.nbt.CompoundTag;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Delivery metadata is independent of item entitlement and can never reopen a source. */
public final class RecoveryNoticeStore {
    public record Notice(long epoch, long sentAt, long openedAt, long viewedAt) {}
    private final Path root;
    public RecoveryNoticeStore(Path world) { root = world.resolve("data/mwi/recovery_notices"); }
    public Notice read(UUID owner, UUID source, long epoch) throws IOException {
        Path path = path(owner, source);
        if (!Files.exists(path)) return new Notice(epoch, 0, 0, 0);
        CompoundTag data = AtomicNbtFile.read(path);
        String checksum = data.getString("checksum"); data.remove("checksum");
        if (!data.hasUUID("owner") || !owner.equals(data.getUUID("owner"))
                || !data.hasUUID("source") || !source.equals(data.getUUID("source"))
                || !checksum.equals(NbtChecksums.canonicalSha256(data))) throw new IOException("通知记录校验失败");
        if (data.getLong("epoch") != epoch) return new Notice(epoch, 0, 0, 0);
        return new Notice(epoch, data.getLong("sent_at"), data.getLong("opened_at"), data.getLong("viewed_at"));
    }
    public void write(UUID owner, UUID source, Notice notice) throws IOException {
        CompoundTag data = new CompoundTag(); data.putUUID("owner", owner); data.putUUID("source", source);
        data.putLong("epoch", notice.epoch()); data.putLong("sent_at", notice.sentAt());
        data.putLong("opened_at", notice.openedAt()); data.putLong("viewed_at", notice.viewedAt());
        data.putString("checksum", NbtChecksums.canonicalSha256(data));
        AtomicNbtFile.write(path(owner, source), data);
    }
    private Path path(UUID owner, UUID source) { return root.resolve(owner.toString()).resolve(source + ".dat"); }
}
