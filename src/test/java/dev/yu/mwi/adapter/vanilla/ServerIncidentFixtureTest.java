package dev.yu.mwi.adapter.vanilla;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Private incident payloads are supplied outside Git; the test never writes them. */
class ServerIncidentFixtureTest {
    @Test void realCureOrderIncidentsStayStrictAndPreserveOriginalBytes() throws Exception {
        String configured = System.getProperty("mwi.serverIncidentFixture");
        Assumptions.assumeTrue(configured != null, "Private server-incident bundle not supplied");
        int checked = 0;
        try (var files = Files.walk(Path.of(configured))) {
            for (Path expectedPath : files.filter(path -> path.getFileName().toString().matches("(effects|potion)-expected\\.nbt")).toList()) {
                byte[] original = Files.readAllBytes(expectedPath);
                Path actualPath = expectedPath.resolveSibling(expectedPath.getFileName().toString().replace("expected", "actual"));
                CompoundTag expected = NbtIo.readCompressed(expectedPath, NbtAccounter.create(1024 * 1024));
                CompoundTag actual = NbtIo.readCompressed(actualPath, NbtAccounter.create(1024 * 1024));
                assertTrue(PotionEffectPayloads.verify(expected, actual).accepted(), "Private cure fixture is not equivalent");
                assertEquals(PotionEffectPayloads.normalize(expected), PotionEffectPayloads.normalize(actual));
                CompoundTag wrong = actual.copy();
                wrong.getList("effects", Tag.TAG_COMPOUND).getCompound(0).putInt("duration", 123456789);
                assertFalse(PotionEffectPayloads.verify(expected, wrong).accepted());
                assertArrayEquals(original, Files.readAllBytes(expectedPath));
                checked++;
            }
        }
        assertTrue(checked >= 4, "Expected the incident bundle's four independent cure round trips");
    }
}
