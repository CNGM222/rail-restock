package dev.feederrestock.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shipped defaults and the reduced option set. 0.6.0 dropped the
 * "scan the surroundings" switches on purpose: registered containers are the only
 * source, so those fields must not come back by accident.
 */
class RestockConfigTest {
    @Test
    void defaultsMatchTheDocumentedValues() {
        RestockConfig config = new RestockConfig();
        assertEquals(5, config.preOpenPauseTicks, "开箱前应提前几 tick 停印");
        assertEquals(6, config.printPauseTicks);
        assertEquals(40, config.visitPauseCapTicks);
        assertEquals(5, config.scanIntervalTicks);
        assertTrue(config.unobtrusiveInteraction);
        assertFalse(config.autoRecountMaterialList);
        assertTrue(config.chatNotifications);
        assertTrue(config.actionbarStatus);
        assertTrue(config.highlightContainers, "容器高亮默认开启");
        assertEquals(48, config.highlightRange);
    }

    @Test
    void blindScanningOptionsAreGone() throws NoSuchFieldException {
        for (String removed : new String[]{"shareFeederContainers", "onlyMarkedContainers",
                "includeChests", "includeBarrels", "includeShulkerBoxes"}) {
            assertThrows(NoSuchFieldException.class, () -> RestockConfig.class.getDeclaredField(removed),
                    "已删除的选项不应回来：" + removed);
        }
        // The registered-container integration is mandatory now, not a switch.
        assertEquals(new RestockConfig().knowledgeExpireTicks, 6000);
    }
}
