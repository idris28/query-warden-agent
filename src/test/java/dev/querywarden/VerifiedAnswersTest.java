package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verified answers library and feedback store (no database needed). */
class VerifiedAnswersTest {

    @Test
    void sameQuestionIgnoresCasePunctuationAndFillerWords() {
        assertEquals(VerifiedAnswers.words("How many orders are there per status?"),
                VerifiedAnswers.words("how many ORDERS per status"));
        assertEquals(VerifiedAnswers.words("Revenue by categories"), VerifiedAnswers.words("revenue by category"));
        assertFalse(VerifiedAnswers.words("orders per status").equals(VerifiedAnswers.words("orders per country")));
    }

    @Test
    void findsTheSameAndSimilarQuestions(@TempDir Path dir) throws Exception {
        VerifiedAnswers lib = new VerifiedAnswers(dir.resolve("v.json"));
        lib.add("How many orders are there per status?", "SELECT status, COUNT(*) FROM orders GROUP BY status", "ann", "");
        lib.add("Revenue by product category", "SELECT category, 1 FROM products", "ann", "");
        lib.add("How many customers per country?", "SELECT country, COUNT(*) FROM customers GROUP BY country", "ann", "");

        var same = lib.find("how many orders per status", 3);
        assertTrue(same.get(0).same());
        assertEquals(1.0, same.get(0).score());

        var similar = lib.find("How many orders per status in 2025?", 3);
        assertFalse(similar.get(0).same(), "an added year makes it a different question");
        assertTrue(similar.get(0).answer().question().contains("per status"));
        assertTrue(similar.get(0).score() >= VerifiedAnswers.EXAMPLE_THRESHOLD, "but similar enough to show as an example");

        var unrelated = lib.find("Which employees work in Sales?", 3);
        assertTrue(unrelated.stream().allMatch(m -> m.score() < VerifiedAnswers.EXAMPLE_THRESHOLD), unrelated.toString());
    }

    @Test
    void verifyingTheSameQuestionAgainReplacesIt(@TempDir Path dir) throws Exception {
        VerifiedAnswers lib = new VerifiedAnswers(dir.resolve("v.json"));
        lib.add("Orders per status", "SELECT 1 FROM orders", "ann", "");
        var newer = lib.add("orders per status?", "SELECT 2 FROM orders", "bob", "fixed");
        assertEquals(1, lib.list().size());
        assertEquals(newer, lib.list().get(0));
        assertTrue(lib.remove(newer.id()));
        assertTrue(lib.list().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> lib.add("q", " ", "x", ""));
    }

    @Test
    void changesByAnotherProcessArePickedUp(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("v.json");
        VerifiedAnswers server = new VerifiedAnswers(f), cli = new VerifiedAnswers(f);
        assertTrue(server.list().isEmpty());
        var a = cli.add("Orders per status", "SELECT 1 FROM orders", "ann", "");
        assertEquals(List.of(a), server.list());
    }

    @Test
    void fileNameFollowsThePolicy() {
        assertEquals("policy.verified.json", VerifiedAnswers.forPolicy(Path.of("policy.yaml")).file().getFileName().toString());
        assertEquals("crm.verified.json", VerifiedAnswers.forPolicy(Path.of("/x/crm.yml")).file().getFileName().toString());
        assertEquals("verified.json", VerifiedAnswers.forPolicy(null).file().getFileName().toString());
    }

    @Test
    void sqlComparisonIgnoresSpacingCaseAndAddedLimit() {
        assertEquals(VerifiedAnswers.normaliseSql("SELECT  a\nFROM t"), VerifiedAnswers.normaliseSql("select a from t LIMIT 200"));
        assertFalse(VerifiedAnswers.normaliseSql("SELECT a FROM t").equals(VerifiedAnswers.normaliseSql("SELECT b FROM t")));
    }

    @Test
    void feedbackIsKeptAndResolved(@TempDir Path dir) throws Exception {
        FeedbackStore fb = new FeedbackStore(dir.resolve("feedback.jsonl"));
        var up = fb.add("ann", "Orders per status", "", "SELECT 1", "up", "");
        Thread.sleep(5);
        var down = fb.add("bob", "Revenue 2025", "", "SELECT 2", "down", "should exclude cancelled");
        assertEquals(List.of(down.id(), up.id()), fb.open().stream().map(FeedbackStore.Item::id).toList(), "wrong first");
        assertTrue(fb.resolve(down.id(), "verified", "carol"));
        assertEquals(List.of(up.id()), fb.open().stream().map(FeedbackStore.Item::id).toList());
        var resolved = fb.all().stream().filter(i -> i.id().equals(down.id())).findFirst().orElseThrow();
        assertEquals("verified", resolved.status());
        assertEquals("carol", resolved.resolvedBy());
        assertEquals("should exclude cancelled", resolved.comment());
        assertFalse(fb.resolve("f_nope", "dismissed", "x"));
        assertThrows(IllegalArgumentException.class, () -> fb.add("a", "q", "", "", "sideways", ""));
    }
}
