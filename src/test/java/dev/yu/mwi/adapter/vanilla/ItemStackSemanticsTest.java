package dev.yu.mwi.adapter.vanilla;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ItemStackSemanticsTest {
    private CompoundTag item() throws Exception {
        return TagParser.parseTag("{id:'minecraft:stone',count:1,components:{'sfm:labels':{labels:{power:[[I;1,2,3],[I;4,5,6]]},dimension:'minecraft:overworld'}}}");
    }
    private ListTag positions(CompoundTag item) { return item.getCompound("components").getCompound("sfm:labels").getCompound("labels").getList("power", Tag.TAG_INT_ARRAY); }
    @Test void onlySupportedCoordinatePermutationIsEquivalent() throws Exception {
        CompoundTag expected = item(), actual = expected.copy();
        ListTag positions = positions(actual); Tag first = positions.remove(0); positions.add(first);
        assertTrue(ItemStackSemantics.sameRepresentation(expected, actual, true));
        assertFalse(ItemStackSemantics.sameRepresentation(expected, actual, false));
        positions.set(0, new IntArrayTag(new int[]{4,5,7}));
        assertFalse(ItemStackSemantics.sameRepresentation(expected, actual, true));
    }
    @Test void countDimensionMetadataAndOrderedListsRemainStrict() throws Exception {
        CompoundTag expected = item(), actual = expected.copy(); actual.putInt("count", 2);
        assertFalse(ItemStackSemantics.sameRepresentation(expected, actual, true));
        actual = expected.copy(); actual.getCompound("components").getCompound("sfm:labels").putString("dimension", "minecraft:the_nether");
        assertFalse(ItemStackSemantics.sameRepresentation(expected, actual, true));
        expected.getCompound("components").put("unknown:ordered", TagParser.parseTag("{items:[1,2] }"));
        actual = expected.copy(); actual.getCompound("components").put("unknown:ordered", TagParser.parseTag("{items:[2,1]}"));
        assertFalse(ItemStackSemantics.sameRepresentation(expected, actual, true));
    }
    @Test void duplicateOrMistypedCoordinatesAreRejected() throws Exception {
        CompoundTag bad = item(); positions(bad).add(new IntArrayTag(new int[]{1,2,3}));
        assertThrows(IllegalArgumentException.class, () -> ItemStackSemantics.sameRepresentation(bad, bad, true));
        CompoundTag wrong = item(); positions(wrong).set(0, new IntArrayTag(new int[]{1,2}));
        assertThrows(IllegalArgumentException.class, () -> ItemStackSemantics.sameRepresentation(wrong, wrong, true));
    }
    @Test void privateFixtureHasOnlyTheAuditedNestedSetPermutation() throws Exception {
        String fixture = System.getProperty("mwi.restoreFixture");
        Assumptions.assumeTrue(fixture != null, "Private production fixture is supplied outside Git");
        CompoundTag expected = NbtIo.readCompressed(Path.of(fixture, "curios-expected.nbt"), NbtAccounter.create(64L*1024*1024));
        CompoundTag actual = NbtIo.readCompressed(Path.of(fixture, "curios-actual.nbt"), NbtAccounter.create(64L*1024*1024));
        CompoundTag second = NbtIo.readCompressed(Path.of(fixture, "curios-second-round.nbt"), NbtAccounter.create(64L*1024*1024));
        assertNotEquals(expected, actual); assertEquals(actual, second);
        ListTag left = expected.getCompound("capability").getList("Curios", Tag.TAG_COMPOUND);
        ListTag right = actual.getCompound("capability").getList("Curios", Tag.TAG_COMPOUND);
        assertEquals(left.size(), right.size());
        int changed = 0;
        for (int i=0;i<left.size();i++) {
            if (left.get(i).equals(right.get(i))) continue;
            changed++;
            assertEquals("adv_pattern_encoder", left.getCompound(i).getString("Identifier"));
            CompoundTag a = left.getCompound(i).getCompound("StacksHandler").getCompound("Stacks").getList("Items", Tag.TAG_COMPOUND).getCompound(0);
            CompoundTag b = right.getCompound(i).getCompound("StacksHandler").getCompound("Stacks").getList("Items", Tag.TAG_COMPOUND).getCompound(0);
            assertTrue(ItemStackSemantics.sameRepresentation(a, b, true));
            left.set(i, right.get(i).copy());
        }
        assertEquals(1, changed); assertEquals(expected, actual);
    }
}
