package dev.yu.mwi.storage;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryNoticeStoreTest {
    @TempDir Path world;
    @Test void deliveryProgressSurvivesRestartButANewReviewStartsANewNotice() throws Exception {
        UUID owner = UUID.randomUUID(), source = UUID.randomUUID();
        var notice = new RecoveryNoticeStore.Notice(100, 101, 102, 103);
        new RecoveryNoticeStore(world).write(owner, source, notice);
        var restarted = new RecoveryNoticeStore(world);
        assertEquals(notice, restarted.read(owner, source, 100));
        assertEquals(new RecoveryNoticeStore.Notice(200, 0, 0, 0), restarted.read(owner, source, 200));
        assertEquals(new RecoveryNoticeStore.Notice(100, 0, 0, 0), restarted.read(owner, UUID.randomUUID(), 100));
    }
    @Test void alteredViewedReceiptIsNotTrusted() throws Exception {
        UUID owner = UUID.randomUUID(), source = UUID.randomUUID();
        var store = new RecoveryNoticeStore(world); store.write(owner, source, new RecoveryNoticeStore.Notice(100, 101, 0, 0));
        Path file = world.resolve("data/mwi/recovery_notices").resolve(owner.toString()).resolve(source + ".dat");
        CompoundTag corrupted = AtomicNbtFile.read(file); corrupted.putLong("viewed_at", 1000); AtomicNbtFile.write(file, corrupted);
        assertThrows(IOException.class, () -> store.read(owner, source, 100));
    }
}
