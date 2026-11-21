package dev.railrestock.integration;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeederMarksTest {
    @Test
    void readsRegisteredContainers() {
        List<FeederMarks.Mark> marks = FeederMarks.parse("""
                {"containers":[
                  {"dimension":"minecraft:overworld","x":1,"y":2,"z":3},
                  {"dimension":"minecraft:the_nether","x":-8,"y":64,"z":120}
                ],
                "center":{"dimension":"minecraft:overworld","x":0,"y":64,"z":0}}
                """);
        assertEquals(2, marks.size());
        assertTrue(marks.contains(new FeederMarks.Mark("minecraft:overworld", BlockPos.asLong(1, 2, 3))));
        assertEquals("minecraft:the_nether", marks.get(1).dimension());
        assertEquals(BlockPos.asLong(-8, 64, 120), marks.get(1).pos());
    }

    @Test
    void toleratesMissingListAndForeignFields() {
        assertTrue(FeederMarks.parse("{\"unobtrusiveInteraction\":true}").isEmpty());
        assertEquals(1, FeederMarks.parse("""
                {"containers":[{"dimension":"minecraft:overworld","x":0,"y":70,"z":0,
                                "name":"石头箱","enabled":true}]}
                """).size());
    }

    @Test
    void skipsMalformedEntriesInsteadOfFailingTheWholeFile() {
        List<FeederMarks.Mark> marks = FeederMarks.parse("""
                {"containers":[
                  null,
                  {"dimension":"minecraft:overworld","x":"1","y":2,"z":3},
                  {"dimension":"","x":1,"y":2,"z":3},
                  {"dimension":"minecraft:overworld","x":4,"y":5},
                  {"dimension":"minecraft:overworld","x":6,"y":7,"z":8}
                ]}
                """);
        assertEquals(1, marks.size());
        assertEquals(BlockPos.asLong(6, 7, 8), marks.getFirst().pos());
    }

    @Test
    void doubleChestHalvesShareOneMark() {
        // mapart-feeder stores the canonical (lower) half, which is also the key
        // rail-restock uses, so either half maps to the same mark.
        List<FeederMarks.Mark> marks = FeederMarks.parse("""
                {"containers":[{"dimension":"minecraft:overworld","x":10,"y":64,"z":10}]}
                """);
        assertTrue(marks.contains(new FeederMarks.Mark("minecraft:overworld", BlockPos.asLong(10, 64, 10))));
        assertFalse(marks.contains(new FeederMarks.Mark("minecraft:overworld", BlockPos.asLong(11, 64, 10))));
        assertFalse(marks.contains(new FeederMarks.Mark("minecraft:the_nether", BlockPos.asLong(10, 64, 10))));
    }

    @Test
    void rejectsInputThatIsNotAJsonObject() {
        assertThrows(RuntimeException.class, () -> FeederMarks.parse("not json"));
        assertThrows(RuntimeException.class, () -> FeederMarks.parse("[]"));
    }
}
