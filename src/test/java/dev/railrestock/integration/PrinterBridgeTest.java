package dev.railrestock.integration;

import me.aleksilassila.litematica.printer.config.Configs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrinterBridgeTest {
    @BeforeEach
    void resetPrinter() {
        Configs.PRINT_MODE.setBooleanValue(false);
    }

    @Test
    void suspendingAnEnabledPrinterTurnsItOffAndRestoresIt() {
        Configs.PRINT_MODE.setBooleanValue(true);
        PrinterBridge bridge = new PrinterBridge();
        assertTrue(bridge.isAvailable(), "测试替身应当能被反射找到");

        bridge.setSuspended(true);
        assertFalse(Configs.PRINT_MODE.getBooleanValue());
        assertTrue(bridge.isSuspended());

        bridge.setSuspended(false);
        assertTrue(Configs.PRINT_MODE.getBooleanValue());
        assertFalse(bridge.isSuspended());
    }

    @Test
    void aPrinterThePlayerDisabledIsNeverEnabledByTheMod() {
        // The mod must not start printing that the player turned off.
        PrinterBridge bridge = new PrinterBridge();
        bridge.setSuspended(true);
        assertFalse(Configs.PRINT_MODE.getBooleanValue());
        bridge.setSuspended(false);
        assertFalse(Configs.PRINT_MODE.getBooleanValue());
    }

    @Test
    void repeatedSuspensionsRestoreExactlyOnce() {
        Configs.PRINT_MODE.setBooleanValue(true);
        PrinterBridge bridge = new PrinterBridge();
        bridge.setSuspended(true);
        bridge.setSuspended(true);
        bridge.setSuspended(false);
        assertTrue(Configs.PRINT_MODE.getBooleanValue());
        bridge.setSuspended(false);
        assertTrue(Configs.PRINT_MODE.getBooleanValue());
    }

    @Test
    void resumeIsSafeWhenNeverSuspended() {
        PrinterBridge bridge = new PrinterBridge();
        bridge.resume();
        assertFalse(bridge.isSuspended());
        assertFalse(Configs.PRINT_MODE.getBooleanValue());
    }
}
