package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ModelSettingsTest {

    @Test
    void defaultsAreOpusForSqlAndHaikuForHelpers() {
        ModelSettings d = ModelSettings.fromMap(Map.of());
        assertEquals("claude-opus-5-5", d.sqlModel());
        assertEquals("medium", d.sqlEffort());
        assertEquals("claude-haiku-5-5", d.helperModel());
        assertEquals("low", d.helperEffort());
    }

    @Test
    void environmentOverridesEachSetting() {
        ModelSettings m = ModelSettings.fromMap(Map.of(
                "QW_SQL_MODEL", "claude-sonnet-5-5", "QW_SQL_EFFORT", "HIGH",
                "QW_HELPER_MODEL", " claude-sonnet-5-5 ", "QW_HELPER_EFFORT", ""));
        assertEquals("claude-sonnet-5-5", m.sqlModel());
        assertEquals("high", m.sqlEffort(), "effort is case-insensitive");
        assertEquals("claude-sonnet-5-5", m.helperModel(), "surrounding spaces are ignored");
        assertEquals("low", m.helperEffort(), "blank keeps the default");
    }

    @Test
    void rejectsInvalidValuesWithAClearMessage() {
        var e1 = assertThrows(IllegalArgumentException.class,
                () -> ModelSettings.fromMap(Map.of("QW_SQL_EFFORT", "extreme")));
        assertTrue(e1.getMessage().contains("QW_SQL_EFFORT") && e1.getMessage().contains("low, medium"));
        var e2 = assertThrows(IllegalArgumentException.class,
                () -> ModelSettings.fromMap(Map.of("QW_HELPER_MODEL", "gpt-5")));
        assertTrue(e2.getMessage().contains("doesn't look like a Claude model id"));
        assertThrows(IllegalArgumentException.class,
                () -> ModelSettings.fromMap(Map.of("QW_SQL_MODEL", "claude-opus-5-5; rm -rf /")));
    }

    @Test
    void refusalFallbackOnlyForModelsThatSupportIt() {
        assertTrue(ModelSettings.supportsServerFallback("claude-opus-5-5"));
        assertTrue(ModelSettings.supportsServerFallback("claude-sonnet-5-5"));
        assertFalse(ModelSettings.supportsServerFallback("claude-haiku-5-5"), "Haiku 5.5 has no server-side fallback");
        assertFalse(ModelSettings.supportsServerFallback("claude-opus-4-8"));
    }
}
