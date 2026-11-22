package dev.feederrestock.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.feederrestock.config.RestockConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The settings screen is only as good as its translations: a missing key shows
 * a raw translation id in game. This checks every public config field has a
 * label in both languages, so a new option cannot ship without one.
 */
class SettingsLangTest {
    @Test
    void everyConfigOptionHasALabelInBothLanguages() throws IOException {
        JsonObject chinese = lang("zh_cn");
        JsonObject english = lang("en_us");
        for (Field field : RestockConfig.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers()) || Modifier.isStatic(field.getModifiers())) continue;
            String key = "options.feederrestock." + field.getName();
            assertTrue(chinese.has(key), "缺少中文标签：" + key);
            assertTrue(english.has(key), "缺少英文标签：" + key);
        }
    }

    @Test
    void screenChromeIsTranslated() throws IOException {
        JsonObject chinese = lang("zh_cn");
        JsonObject english = lang("en_us");
        for (String key : new String[]{"screen.feederrestock.settings", "screen.feederrestock.reset",
                "screen.feederrestock.page.interaction", "screen.feederrestock.page.taking",
                "screen.feederrestock.page.timeouts", "screen.feederrestock.page.feedback",
                "options.feederrestock.value", "key.feederrestock.settings"}) {
            assertTrue(chinese.has(key), "缺少中文标签：" + key);
            assertTrue(english.has(key), "缺少英文标签：" + key);
        }
        // Sliders format "label: value", so the pattern must keep both placeholders.
        assertEquals(2, countPlaceholders(chinese.get("options.feederrestock.value").getAsString()));
        assertEquals(2, countPlaceholders(english.get("options.feederrestock.value").getAsString()));
    }

    @Test
    void bothLanguagesOfferTheSameKeys() throws IOException {
        assertEquals(lang("zh_cn").keySet(), lang("en_us").keySet());
    }

    private static int countPlaceholders(String text) {
        return text.split("%s", -1).length - 1;
    }

    private static JsonObject lang(String code) throws IOException {
        String path = "assets/feederrestock/lang/" + code + ".json";
        try (InputStream stream = SettingsLangTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
