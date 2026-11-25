package dev.feederrestock.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only view of the raw-material containers registered by mapart-feeder
 * (its {@code config/mapartfeeder.json} {@code containers} array).
 *
 * <p>The file is only read, never written: mapart-feeder owns it and rewrites it
 * whenever the player presses its marking key. Reading is cheap (size plus
 * modification time) and re-parsed only when the file actually changed, so a
 * container registered mid-session is used without restarting the game.</p>
 */
public final class FeederMarks implements MarkSource {
    public static final String MOD_ID = "mapartfeeder";
    private static final String FILE_NAME = "mapartfeeder.json";
    /** Double-chest halves share one mark, exactly like mapart-feeder's cache key. */
    public record Mark(String dimension, long pos) { }

    private final Set<Mark> marks = new HashSet<>();
    private boolean present;
    private long stamp = Long.MIN_VALUE;
    private long nextCheck;

    /** True when mapart-feeder is installed, i.e. its list and its K key are in charge. */
    public static boolean isInstalled() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /** Re-reads the file at most once per second; a missing file clears the marks. */
    @Override
    public void refresh(long tick) {
        if (tick < nextCheck) return;
        nextCheck = tick + 20;
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        long current;
        try {
            if (!Files.isRegularFile(path)) {
                clear();
                return;
            }
            current = Files.getLastModifiedTime(path).toMillis() * 31L + Files.size(path);
        } catch (IOException | RuntimeException error) {
            clear();
            return;
        }
        if (present && current == stamp) return;
        try {
            replace(parse(Files.readString(path, StandardCharsets.UTF_8)));
            present = true;
            stamp = current;
        } catch (IOException | RuntimeException error) {
            // A half-written file must not stop restocking; keep the last good marks.
            present = false;
        }
    }

    public boolean isPresent() {
        return present;
    }

    @Override
    public int size() {
        return marks.size();
    }

    /** Registered positions in one dimension; these are the only sources we accept. */
    @Override
    public List<BlockPos> positions(String dimension) {
        return positions(List.copyOf(marks), dimension);
    }

    public static List<BlockPos> positions(List<Mark> marks, String dimension) {
        List<BlockPos> result = new ArrayList<>();
        for (Mark mark : marks) {
            if (mark.dimension().equals(dimension)) result.add(BlockPos.fromLong(mark.pos()));
        }
        return result;
    }

    @Override
    public void clear() {
        marks.clear();
        present = false;
        stamp = Long.MIN_VALUE;
    }

    private void replace(List<Mark> parsed) {
        marks.clear();
        marks.addAll(parsed);
    }

    /**
     * Tolerant parse: unknown fields are ignored and a malformed entry is skipped
     * rather than failing the whole list, because the file belongs to another mod.
     */
    public static List<Mark> parse(String json) {
        List<Mark> result = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonElement containers = root.get("containers");
        if (containers == null || !containers.isJsonArray()) return result;
        for (JsonElement element : containers.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String dimension = text(object, "dimension");
            Integer x = integer(object, "x");
            Integer y = integer(object, "y");
            Integer z = integer(object, "z");
            if (dimension.isEmpty() || x == null || y == null || z == null) continue;
            result.add(new Mark(dimension, BlockPos.asLong(x, y, z)));
        }
        return result;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "";
        return value.getAsString();
    }

    private static Integer integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            return value.getAsInt();
        } catch (RuntimeException error) {
            return null;
        }
    }
}
