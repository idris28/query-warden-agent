package dev.querywarden.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class ResultMatcherTest {

    static List<Object> row(Object... v) { return List.of(v); }

    @Test
    void sameRowsAnyOrderWhenUnordered() {
        var ref = List.of(row("BR", 19), row("CA", 10));
        assertTrue(ResultMatcher.compare(List.of(row("CA", 10), row("BR", 19)), ref, false).match());
    }

    @Test
    void columnNamesOrderAndExtraColumnsDontMatter() {
        var ref = List.of(row("Mia Dubois", new BigDecimal("16360.52")));
        var got = List.of(row(59, new BigDecimal("16360.52"), "Mia Dubois"));   // id + reordered columns
        assertTrue(ResultMatcher.compare(got, ref, true).match());
    }

    @Test
    void numbersComparedAtTwoDecimals() {
        assertTrue(ResultMatcher.compare(List.of(row(2.5099)), List.of(row(new BigDecimal("2.51"))), false).match());
        assertTrue(ResultMatcher.compare(List.of(row(new BigDecimal("14083.50"))), List.of(row(14083.5)), false).match());
        assertFalse(ResultMatcher.compare(List.of(row(2.52)), List.of(row(2.51)), false).match());
    }

    @Test
    void monthFormatsAreEquivalent() {
        var ref = List.of(row("2025-01-01T00:00", 32));
        assertTrue(ResultMatcher.compare(List.of(row("2025-01-01", 32)), ref, true).match());
        assertTrue(ResultMatcher.compare(List.of(row("2025-01", 32)), ref, true).match());
        assertFalse(ResultMatcher.compare(List.of(row("2025-02-01", 32)), ref, true).match());
    }

    @Test
    void wrongOrMissingRowsFail() {
        var ref = List.of(row("BR", 19), row("CA", 10));
        assertFalse(ResultMatcher.compare(List.of(row("BR", 19)), ref, false).match(), "missing row");
        assertFalse(ResultMatcher.compare(List.of(row("BR", 19), row("CA", 11)), ref, false).match(), "wrong value");
        assertFalse(ResultMatcher.compare(List.of(row("BR", 10), row("CA", 19)), ref, false).match(),
                "same values in each column but paired with the wrong rows");
    }

    @Test
    void orderMattersForRankings() {
        var ref = List.of(row("a", 3), row("b", 2), row("c", 1));
        assertTrue(ResultMatcher.compare(List.of(row("a", 3), row("b", 2), row("c", 1)), ref, true).match());
        assertFalse(ResultMatcher.compare(List.of(row("c", 1), row("b", 2), row("a", 3)), ref, true).match());
        assertTrue(ResultMatcher.compare(List.of(row("c", 1), row("b", 2), row("a", 3)), ref, false).match());
    }

    @Test
    void tiesInARankingMaySwap() {
        var ref = List.of(row("a", 5), row("b", 5), row("c", 1));
        assertTrue(ResultMatcher.compare(List.of(row("b", 5), row("a", 5), row("c", 1)), ref, true).match());
    }

    @Test
    void emptyAnswerNeverMatchesANonEmptyReference() {
        assertFalse(ResultMatcher.compare(List.of(), List.of(row(0)), false).match());
    }
}
