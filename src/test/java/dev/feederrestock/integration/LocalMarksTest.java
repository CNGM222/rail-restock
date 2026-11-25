package dev.feederrestock.integration;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The standalone registry is the only way to register containers when
 * mapart-feeder is absent, so its toggle semantics, cap and file format are
 * pinned here. Toggling itself never touches the disk; the controller saves only
 * after a successful change.
 */
class LocalMarksTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void toggleAddsThenRemovesTheSamePosition() {
        LocalMarks marks = new LocalMarks();
        BlockPos pos = new BlockPos(10, 64, -3);
        assertEquals(LocalMarks.Toggle.ADDED, marks.toggle(OVERWORLD, pos));
        assertTrue(marks.contains(OVERWORLD, pos));
        assertEquals(1, marks.size());
        assertEquals(LocalMarks.Toggle.REMOVED, marks.toggle(OVERWORLD, pos));
        assertFalse(marks.contains(OVERWORLD, pos));
        assertEquals(0, marks.size());
    }

    @Test
    void dimensionsAreIndependent() {
        LocalMarks marks = new LocalMarks();
        BlockPos pos = new BlockPos(1, 2, 3);
        marks.toggle(OVERWORLD, pos);
        assertFalse(marks.contains(NETHER, pos));
        assertEquals(1, marks.positions(OVERWORLD).size());
        assertTrue(marks.positions(NETHER).isEmpty());
    }

    @Test
    void aRemovalStillWorksAtTheCap() {
        LocalMarks marks = new LocalMarks();
        for (int i = 0; i < LocalMarks.MAX_MARKS; i++) {
            assertEquals(LocalMarks.Toggle.ADDED, marks.toggle(OVERWORLD, new BlockPos(i, 64, 0)));
        }
        BlockPos extra = new BlockPos(LocalMarks.MAX_MARKS, 64, 0);
        assertEquals(LocalMarks.Toggle.LIMIT, marks.toggle(OVERWORLD, extra));
        assertEquals(LocalMarks.MAX_MARKS, marks.size());
        // The cap must not trap the player: an existing entry can still be removed.
        assertEquals(LocalMarks.Toggle.REMOVED, marks.toggle(OVERWORLD, new BlockPos(0, 64, 0)));
        assertEquals(LocalMarks.Toggle.ADDED, marks.toggle(OVERWORLD, extra));
    }

    @Test
    void writtenFileRoundTripsThroughTheSharedParser() {
        List<FeederMarks.Mark> input = new ArrayList<>();
        input.add(new FeederMarks.Mark(OVERWORLD, BlockPos.asLong(1, 2, 3)));
        input.add(new FeederMarks.Mark(NETHER, BlockPos.asLong(-8, 64, 120)));
        String json = LocalMarks.serialize(input);
        assertEquals(input, FeederMarks.parse(json));
        // Same containers/dimension/x/y/z schema as mapart-feeder, so a list can be
        // copied between config/mapartfeeder.json and our own file.
        assertTrue(json.contains("\"containers\""));
        assertTrue(json.contains("\"dimension\""));
        assertTrue(json.contains("\"x\""));
        assertTrue(json.contains("\"y\""));
        assertTrue(json.contains("\"z\""));
    }
}
