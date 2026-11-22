package dev.railrestock.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reflection adapter for the Litematica printer mod, so restocking can suspend
 * actual block printing instead of only Litematica's own Easy Place.
 *
 * <p>Two layouts exist and both are supported:</p>
 * <ul>
 *   <li>original aleksilassila: {@code Configs.PRINT_MODE} is the print switch;</li>
 *   <li>BiliXWhite fork: {@code Configs.Core.WORK_SWITCH} is the master switch and
 *       {@code Configs.Print.ENABLED} is the print module.</li>
 * </ul>
 *
 * <p>Only the print switch is toggled, and only if it was on: a printer the player
 * already turned off is never switched on. The printer's own config file is not
 * touched, but note that a manual toggle during a suspension may be overridden
 * when the switch is restored.</p>
 */
public final class PrinterBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("railrestock");
    private static final String PREFIX = "me.aleksilassila.litematica.printer.";

    private boolean resolved;
    private Object primary;
    private Object secondary;
    private Method getter;
    private Method setter;
    private Boolean tickHook;
    private boolean suspended;

    /** True when a supported printer is installed; unresolved calls are cheap no-ops. */
    public boolean isAvailable() {
        resolve();
        return primary != null;
    }

    /**
     * True when the printer exposes {@code Printer#onGameTick}, which the mixin
     * cancels while restocking. Toggling the config switch is then unnecessary and
     * would make the player's print toggle flicker, so it is only used as fallback.
     */
    public boolean hasTickHook() {
        if (tickHook == null) {
            try {
                Class<?> printer = load(PREFIX + "Printer");
                printer.getMethod("onGameTick");
                tickHook = Boolean.TRUE;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                tickHook = Boolean.FALSE;
            }
        }
        return tickHook;
    }

    public boolean isSuspended() {
        return suspended;
    }

    /**
     * Suspends or restores the printer. Suspending remembers that this mod was the
     * one that turned it off, so a printer the player disabled stays disabled.
     */
    public void setSuspended(boolean value) {
        resolve();
        if (primary == null) return;
        Object target = switchForSuspend();
        if (value) {
            if (suspended || !read(target)) return;
            suspended = true;
            write(target, false);
        } else {
            if (!suspended) return;
            suspended = false;
            write(target, true);
        }
    }

    /** Never leaves the printer disabled after the mod stops for any reason. */
    public void resume() {
        setSuspended(false);
    }

    private Object switchForSuspend() {
        return secondary != null ? secondary : primary;
    }

    private void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> configs = load(PREFIX + "config.Configs");
            Class<?> forkCore = tryLoad(PREFIX + "config.Configs$Core");
            Class<?> forkPrint = tryLoad(PREFIX + "config.Configs$Print");
            Object workSwitch = forkCore == null ? null : staticField(forkCore, "WORK_SWITCH");
            Object printModule = forkPrint == null ? null : staticField(forkPrint, "ENABLED");
            if (workSwitch != null && printModule != null) {
                primary = workSwitch;
                secondary = printModule;
            } else {
                primary = staticField(configs, "PRINT_MODE");
            }
            if (primary == null) return;
            getter = primary.getClass().getMethod("getBooleanValue");
            setter = primary.getClass().getMethod("setBooleanValue", boolean.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            primary = null;
            secondary = null;
            LOGGER.warn("未找到可用的投影打印机开关，仅暂停 Litematica Easy Place：{}", describe(error));
        }
    }

    private boolean read(Object config) {
        try {
            Object value = getter.invoke(config);
            return value instanceof Boolean flag && flag;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    private void write(Object config, boolean value) {
        try {
            setter.invoke(config, value);
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.warn("写入投影打印机开关失败：{}", describe(error));
        }
    }

    private static Object staticField(Class<?> owner, String name) {
        try {
            Field field = owner.getField(name);
            if (!Modifier.isStatic(field.getModifiers())) return null;
            field.trySetAccessible();
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, PrinterBridge.class.getClassLoader());
    }

    private static Class<?> tryLoad(String name) {
        try {
            return load(name);
        } catch (ClassNotFoundException | LinkageError error) {
            return null;
        }
    }

    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable next = error; next != null; next = next.getCause()) {
            if (!text.isEmpty()) text.append("；");
            text.append(next.getClass().getSimpleName()).append("：").append(next.getMessage());
        }
        return text.toString();
    }
}
