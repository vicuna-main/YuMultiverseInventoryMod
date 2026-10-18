package dev.yu.mwi.service;
import dev.yu.mwi.storage.RestorePoint;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RestorePointSelectorTest {
    @Test void ambiguousSecondsAndDatesRequireExactTimestamp() {
        var a=new RestorePoint(1,Instant.parse("2026-10-18T02:33:03.123Z"),Map.of(),List.of(),"manifest");
        var b=new RestorePoint(2,Instant.parse("2026-10-18T02:33:03.456Z"),Map.of(),List.of(),"manifest");
        var points=List.of(a,b); var zone=ZoneId.of("Asia/Shanghai");
        assertEquals("20261018-103303.123",RestorePointSelector.label(a,zone,points));
        assertEquals(a,RestorePointSelector.select("20261018-103303.123",points,zone));
        assertThrows(IllegalArgumentException.class,()->RestorePointSelector.select("20261018-103303",points,zone));
        assertThrows(IllegalArgumentException.class,()->RestorePointSelector.select("20261018",points,zone));
        assertThrows(IllegalArgumentException.class,()->RestorePointSelector.select("20260230",points,zone));
    }
}
