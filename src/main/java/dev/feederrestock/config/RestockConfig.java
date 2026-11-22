package dev.feederrestock.config;

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
    /** How often the registered containers are checked for reachability while idle. */
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
     * Printing is suspended this many ticks BEFORE the container is opened, so the
     * printer is never mid-placement when the inventory session starts.
     */
    public int preOpenPauseTicks = 5;
    /**
     * Easy Place is suspended only for this many ticks after the last slot action.
     * It is a deadline, not a visit lock, so a slow server sync cannot stall printing.
     */
    public int printPauseTicks = 6;
    /**
     * Hard cap for pausing while a container visit is in progress (pre-open → open →
     * transfer → settle). Printing resumes on its own after this many ticks even if
     * the server never answers, so a stalled visit can never freeze printing.
     */
    public int visitPauseCapTicks = 40;
    /** Local GUI/focus suppression only; server interaction stays vanilla. */
    public boolean unobtrusiveInteraction = true;
    /**
     * Automatically re-count the building material list. Litematica shows its own
     * "scheduled task added" chat line and HUD progress for every count, so this is
     * opt-in and off by default. When off, the mod never schedules a count itself;
     * it only uses a list that was already counted once.
     */
    public boolean autoRecountMaterialList = false;
    /**
     * How long a surveyed container's contents stay trusted. Expiring them lets a
     * chest refilled by the player be surveyed again without restarting the game.
     */
    public int knowledgeExpireTicks = 6000;
    /** Draws a box around every registered container: green usable, yellow unknown. */
    public boolean highlightContainers = true;
    /** Maximum distance, in blocks, at which registered containers are highlighted. */
    public int highlightRange = 48;
    public boolean chatNotifications = true;
    public boolean actionbarStatus = true;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("feederrestock.json");
    }

    /** Saves the current values; used by the in-game settings screen. */
    public void save() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A failed save must never break gameplay; the in-memory values stay active.
        }
    }

    /** Restores every field to the shipped default (the settings screen's "reset"). */
    public void resetToDefaults() {
        RestockConfig defaults = new RestockConfig();
        scanIntervalTicks = defaults.scanIntervalTicks;
        clickIntervalTicks = defaults.clickIntervalTicks;
        openTimeoutTicks = defaults.openTimeoutTicks;
        syncTimeoutTicks = defaults.syncTimeoutTicks;
        emptyContainerCooldownTicks = defaults.emptyContainerCooldownTicks;
        successCooldownTicks = defaults.successCooldownTicks;
        containerRevisitTicks = defaults.containerRevisitTicks;
        recountIntervalTicks = defaults.recountIntervalTicks;
        manualPauseTicks = defaults.manualPauseTicks;
        preOpenPauseTicks = defaults.preOpenPauseTicks;
        printPauseTicks = defaults.printPauseTicks;
        visitPauseCapTicks = defaults.visitPauseCapTicks;
        knowledgeExpireTicks = defaults.knowledgeExpireTicks;
        highlightContainers = defaults.highlightContainers;
        highlightRange = defaults.highlightRange;
        unobtrusiveInteraction = defaults.unobtrusiveInteraction;
        autoRecountMaterialList = defaults.autoRecountMaterialList;
        chatNotifications = defaults.chatNotifications;
        actionbarStatus = defaults.actionbarStatus;
    }

    public static RestockConfig load(Logger logger) {
        Path path = path();
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
                if (!tree.has("preOpenPauseTicks")) config.preOpenPauseTicks = 5;
                if (!tree.has("printPauseTicks")) config.printPauseTicks = 6;
                if (!tree.has("visitPauseCapTicks")) config.visitPauseCapTicks = 40;
                if (!tree.has("knowledgeExpireTicks")) config.knowledgeExpireTicks = 6000;
                if (!tree.has("highlightContainers")) config.highlightContainers = true;
                if (!tree.has("highlightRange")) config.highlightRange = 48;
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
        preOpenPauseTicks = clamp(preOpenPauseTicks, 0, 20);
        printPauseTicks = clamp(printPauseTicks, 1, 40);
        visitPauseCapTicks = clamp(visitPauseCapTicks, 5, 200);
        knowledgeExpireTicks = clamp(knowledgeExpireTicks, 100, 72000);
        highlightRange = clamp(highlightRange, 8, 128);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
