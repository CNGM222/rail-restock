package dev.feederrestock.integration;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * This mod's own container registry, used when mapart-feeder is not installed.
 *
 * <p>The file is {@code config/feederrestock-containers.json} and deliberately uses
 * mapart-feeder's {@code containers} array layout (dimension + x/y/z), so the two
 * mods can read the same schema and a list can be copied between them. A
 * double-chest half is stored canonically (the lower coordinate), so marking
 * either half toggles the same single entry.</p>
 *
 * <p>Only a plain client-side convenience: no server data is involved, and a
 * malformed file is tolerated the same way mapart-feeder's is.</p>
 */
public final class LocalMarks implements MarkSource {
    public static final String FILE_NAME = "feederrestock-containers.json";
    /** Same cap mapart-feeder uses, to keep a mistaken key press harmless. */
    public static final int MAX_MARKS = 256;

    /** What one marking key press did. */
    public enum Toggle {
        ADDED, REMOVED, LIMIT
    }

    private final List<FeederMarks.Mark> marks = new ArrayList<>();
    private long stamp = Long.MIN_VALUE;
    private long nextCheck;

    @Override
    public void refresh(long tick) {
        if (tick < nextCheck) return;
        nextCheck = tick + 20;
        Path path = path();
        long current;
        try {
            if (!Files.isRegularFile(path)) {
                marks.clear();
                stamp = Long.MIN_VALUE;
                return;
            }
            current = Files.getLastModifiedTime(path).toMillis() * 31L + Files.size(path);
        } catch (IOException | RuntimeException error) {
            return;
        }
        if (stamp == current) return;
        try {
            marks.clear();
            marks.addAll(FeederMarks.parse(Files.readString(path, StandardCharsets.UTF_8)));
            stamp = current;
        } catch (IOException | RuntimeException error) {
            // Keep the last good list; a half-written file must not disable marking.
        }
    }

    @Override
    public List<BlockPos> positions(String dimension) {
        return FeederMarks.positions(List.copyOf(marks), dimension);
    }

    @Override
    public int size() {
        return marks.size();
    }

    @Override
    public void clear() {
        marks.clear();
        stamp = Long.MIN_VALUE;
    }

    public boolean contains(String dimension, BlockPos pos) {
        FeederMarks.Mark wanted = new FeederMarks.Mark(dimension, pos.asLong());
        return marks.contains(wanted);
    }

    /**
     * Adds or removes one canonical position in memory. The caller saves and
     * reports afterwards, so a key press never fails halfway through a write.
     */
    public Toggle toggle(String dimension, BlockPos pos) {
        FeederMarks.Mark wanted = new FeederMarks.Mark(dimension, pos.asLong());
        if (marks.remove(wanted)) return Toggle.REMOVED;
        if (marks.size() >= MAX_MARKS) return Toggle.LIMIT;
        marks.add(wanted);
        return Toggle.ADDED;
    }

    /** Writes the current list; an unwritable config directory only loses the change. */
    public void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, serialize(marks) + System.lineSeparator(), StandardCharsets.UTF_8);
            stamp = Files.getLastModifiedTime(path).toMillis() * 31L + Files.size(path);
        } catch (IOException | RuntimeException error) {
            // Registration still works for this session; nothing else to do here.
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** Same pretty JSON mapart-feeder writes, so both files stay interchangeable. */
    public static String serialize(List<FeederMarks.Mark> marks) {
        JsonArray array = new JsonArray();
        for (FeederMarks.Mark mark : marks) {
            JsonObject object = new JsonObject();
            object.addProperty("dimension", mark.dimension());
            BlockPos pos = BlockPos.fromLong(mark.pos());
            object.addProperty("x", pos.getX());
            object.addProperty("y", pos.getY());
            object.addProperty("z", pos.getZ());
            array.add(object);
        }
        JsonObject root = new JsonObject();
        root.add("containers", array);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }
}
