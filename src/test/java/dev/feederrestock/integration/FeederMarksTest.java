package dev.feederrestock.integration;

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
    void positionsAreFilteredByDimension() {
        // Only registered containers in the current dimension may be used; everything
        // else in the file belongs to another world and must be ignored.
        List<FeederMarks.Mark> marks = FeederMarks.parse("""
                {"containers":[
                  {"dimension":"minecraft:overworld","x":10,"y":64,"z":10},
                  {"dimension":"minecraft:the_nether","x":11,"y":64,"z":10},
                  {"dimension":"minecraft:overworld","x":12,"y":64,"z":10}
                ]}
                """);
        List<net.minecraft.util.math.BlockPos> overworld = FeederMarks.positions(marks, "minecraft:overworld");
        assertEquals(List.of(
                new net.minecraft.util.math.BlockPos(10, 64, 10),
                new net.minecraft.util.math.BlockPos(12, 64, 10)), overworld);
        assertEquals(1, FeederMarks.positions(marks, "minecraft:the_nether").size());
        assertTrue(FeederMarks.positions(marks, "minecraft:the_end").isEmpty());
    }

    @Test
    void rejectsInputThatIsNotAJsonObject() {
        assertThrows(RuntimeException.class, () -> FeederMarks.parse("not json"));
        assertThrows(RuntimeException.class, () -> FeederMarks.parse("[]"));
    }
}
