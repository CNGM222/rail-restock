package dev.railrestock.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists parameters only; the enabled state is deliberately never saved. */
public final class RestockConfig {
    public int scanIntervalTicks = 5;
    public int clickIntervalTicks = 2;
    public int openTimeoutTicks = 40;
    public int syncTimeoutTicks = 60;
    public int emptyContainerCooldownTicks = 200;
    public int successCooldownTicks = 20;
    /** Minimum revisit delay AFTER departure; elapsed time alone never rearms a nearby container. */
    public int containerRevisitTicks = 600;
    public int recountIntervalTicks = 200;
    public int manualPauseTicks = 60;
    /**
     * Easy Place is suspended only for this many ticks after the last slot action.
     * It is a deadline, not a visit lock, so a slow server sync cannot stall printing.
     */
    public int printPauseTicks = 6;
    /** Local GUI/focus suppression only; server interaction stays vanilla. */
    public boolean unobtrusiveInteraction = true;
    /**
     * Automatically re-count the building material list. Litematica shows its own
     * "scheduled task added" chat line and HUD progress for every count, so this is
     * opt-in and off by default. When off, the mod never schedules a count itself;
     * it only uses a list that was already counted once.
     */
    public boolean autoRecountMaterialList = false;
    public boolean includeChests = true;
    public boolean includeBarrels = true;
    public boolean includeShulkerBoxes = true;
    /**
     * Share the raw-material containers registered by mapart-feeder. Its
     * {@code config/mapartfeeder.json} is only read, never written: the other mod
     * owns the file and rewrites it when the player marks a container with K.
     */
    public boolean shareFeederContainers = true;
    /** Open only containers registered in mapart-feeder; unmarked chests are ignored. */
    public boolean onlyMarkedContainers = false;
    /**
     * How long a surveyed container's contents stay trusted. Expiring them lets a
     * chest refilled by the player be surveyed again without restarting the game.
     */
    public int knowledgeExpireTicks = 6000;
    public boolean chatNotifications = true;
    public boolean actionbarStatus = true;

    public static RestockConfig load(Logger logger) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("railrestock.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                // Gson bypasses field initialisers, so a key added in a later version
                // would silently load as false. Read the tree to migrate explicitly.
                JsonObject tree = JsonParser.parseReader(reader).getAsJsonObject();
                RestockConfig config = gson.fromJson(tree, RestockConfig.class);
                if (config == null) throw new IllegalArgumentException("配置文件内容为空");
                // Missing keys fall back to the current defaults explicitly, because
                // Gson bypasses field initialisers and would leave them false anyway.
                if (!tree.has("unobtrusiveInteraction")) config.unobtrusiveInteraction = true;
                if (!tree.has("autoRecountMaterialList")) config.autoRecountMaterialList = false;
                if (!tree.has("printPauseTicks")) config.printPauseTicks = 6;
                if (!tree.has("shareFeederContainers")) config.shareFeederContainers = true;
                if (!tree.has("onlyMarkedContainers")) config.onlyMarkedContainers = false;
                if (!tree.has("knowledgeExpireTicks")) config.knowledgeExpireTicks = 6000;
                config.validate();
                return config;
            } catch (IOException | RuntimeException e) {
                logger.error("配置读取失败，保留原文件并使用默认参数", e);
                return new RestockConfig();
            }
        }
        RestockConfig config = new RestockConfig();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(config) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("无法保存默认配置", e);
        }
        return config;
    }

    private void validate() {
        scanIntervalTicks = clamp(scanIntervalTicks, 1, 100);
        clickIntervalTicks = clamp(clickIntervalTicks, 1, 20);
        openTimeoutTicks = clamp(openTimeoutTicks, 10, 200);
        syncTimeoutTicks = clamp(syncTimeoutTicks, 20, 400);
        emptyContainerCooldownTicks = clamp(emptyContainerCooldownTicks, 20, 1200);
        successCooldownTicks = clamp(successCooldownTicks, 5, 600);
        containerRevisitTicks = clamp(containerRevisitTicks, 20, 6000);
        recountIntervalTicks = clamp(recountIntervalTicks, 100, 2400);
        manualPauseTicks = clamp(manualPauseTicks, 20, 600);
        printPauseTicks = clamp(printPauseTicks, 1, 40);
        knowledgeExpireTicks = clamp(knowledgeExpireTicks, 100, 72000);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
