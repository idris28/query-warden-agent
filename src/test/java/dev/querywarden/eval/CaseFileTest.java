package dev.querywarden.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The evaluation questions file is valid, and its readable copy is up to date. */
class CaseFileTest {

    @Test
    void casesFileIsValid() throws Exception {
        var cases = EvalRunner.load(Path.of("eval/cases.yaml"));   // also rejects duplicate ids
        assertFalse(cases.isEmpty());
        for (var c : cases) {
            assertFalse(c.question().isBlank(), c.id() + " has no question");
            assertFalse(c.tags().isEmpty(), c.id() + " has no tag");
            if (!c.decline()) assertFalse(c.references().isEmpty(), c.id() + " has no reference SQL");
            else assertTrue(c.references().isEmpty(), c.id() + ": decline questions have no reference");
        }
    }

    @Test
    void readableListMatchesTheCasesFile() throws Exception {
        String expected = EvalRunner.caseListMarkdown(EvalRunner.load(Path.of("eval/cases.yaml")));
        assertEquals(expected, Files.readString(Path.of("eval/CASES.md")),
                "eval/CASES.md is out of date: run `java -jar target/querywarden.jar eval --list` and commit it");
    }
}
