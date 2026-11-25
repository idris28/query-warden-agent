package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Loading and validating policy.yaml. No database needed. */
class PolicyTest {

    @Test
    void shippedPolicyFileMatchesBuiltInDefaults() throws Exception {
        Policy shipped = Policy.load(Path.of("policy.yaml"));
        Policy d = Policy.defaults();
        // The guardrails must match exactly; only the demo's examples and result sharing differ.
        assertEquals(new Policy(d.schema(), d.allowedTables(), d.blockedColumns(), d.maskedColumns(), d.maxRows(),
                        d.timeoutMillis(), d.maxJoins(), d.maxSelects(), d.maxQuestionChars(), d.maxSqlChars(),
                        d.blockedFunctions(), shipped.examples(), shipped.shareResultsWithAi(), shipped.definitions(),
                        shipped.shareCategoryValues()),
                shipped, "policy.yaml in the project root should describe the demo database exactly");
        assertTrue(shipped.examples().isEmpty(), "the demo lets Claude write the example questions");
        assertTrue(shipped.shareResultsWithAi(), "demo data is fake, so the demo shares results");
        assertTrue(d.examples().isEmpty());
        assertFalse(d.shareResultsWithAi(), "without a policy file, results never leave the machine");
        assertFalse(d.shareCategoryValues(), "nor do category values");
        assertTrue(shipped.shareCategoryValues(), "the demo shares them (fake data)");
    }

    @Test
    void definitionsKeepTheirOrderAndAreValidated() throws Exception {
        Policy p = Policy.parse("""
                definitions:
                  revenue: "Sum of quantity * unit_price, excluding cancelled orders."
                  open order: "Status pending or shipped."
                """, "test");
        assertEquals(List.of("revenue", "open order"), List.copyOf(p.definitions().keySet()));
        assertTrue(p.definitionsText().startsWith("- revenue: Sum of quantity"));
        assertEquals(p, Policy.parse(p.toYaml(), "roundtrip"));

        var e1 = assertThrows(Policy.PolicyException.class, () -> Policy.parse("definitions: [revenue]", "t"));
        assertTrue(e1.getMessage().contains("must map a term"));
        var e2 = assertThrows(Policy.PolicyException.class, () -> Policy.parse("definitions:\n  revenue: \"\"", "t"));
        assertTrue(e2.getMessage().contains("must be a sentence"));
        assertTrue(Policy.load(Path.of("policy.yaml")).definitions().containsKey("revenue"), "the demo policy defines revenue");
    }

    @Test
    void definitionsReachClaudesInstructions() throws Exception {
        Policy p = Policy.parse("definitions:\n  revenue: \"Excludes cancelled orders.\"", "t");
        String block = QueryWardenAgent.definitionsBlock(p);
        assertTrue(block.contains("Business definitions") && block.contains("- revenue: Excludes cancelled orders."));
        assertEquals("", QueryWardenAgent.definitionsBlock(Policy.defaults()), "no section when there are none");
    }

    @Test
    void examplesAndResultSharing() throws Exception {
        Policy p = Policy.parse("""
                examples:
                  - "Orders: how many per status?"
                  - Revenue by month
                share_results_with_ai: true
                """, "test");
        assertEquals(List.of("Orders: how many per status?", "Revenue by month"), p.examples());
        assertTrue(p.shareResultsWithAi());
        assertEquals(p, Policy.parse(p.toYaml(), "roundtrip"), "questions with colons survive toYaml");
    }

    @Test
    void emptyFileMeansDefaults() throws Exception {
        assertEquals(Policy.defaults(), Policy.parse("", "test"));
        assertEquals(Policy.defaults(), Policy.parse("# only a comment\n", "test"));
    }

    @Test
    void readsACustomPolicyAndKeepsDefaultsForMissingKeys() throws Exception {
        Policy p = Policy.parse("""
                schema: Public
                allowed_tables: [Invoices, payments]
                blocked_columns:
                  payments: [card_number]
                masked_columns: [iban]
                limits:
                  max_rows: 50
                """, "test");
        assertEquals("public", p.schema());
        assertEquals(Set.of("invoices", "payments"), p.allowedTables());
        assertFalse(p.columnAllowed("payments", "CARD_NUMBER"));
        assertTrue(p.isMasked("iban"));
        assertFalse(p.isMasked("email"), "masked_columns replaces the default list");
        assertEquals(50, p.maxRows());
        assertEquals(Policy.defaults().timeoutMillis(), p.timeoutMillis(), "unset limits keep their defaults");
    }

    @Test
    void blockedFunctionsAddToTheBuiltInListAndNeverReplaceIt() throws Exception {
        Policy p = Policy.parse("blocked_functions: [my_audit_fn]", "test");
        assertTrue(p.functionBlocked("my_audit_fn"));
        assertTrue(p.functionBlocked("pg_sleep"), "built-in dangerous functions stay blocked");
        assertTrue(Policy.parse("blocked_functions: []", "test").functionBlocked("pg_read_file"));
    }

    @Test
    void blockedColumnsCanBeCleared() throws Exception {
        Policy p = Policy.parse("blocked_columns: {}", "test");
        assertTrue(p.blockedColumns().isEmpty());
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
            "blocked_column:\\n  employees: [salary]                | unknown key(s) [blocked_column]",
            "limits:\\n  max_row: 10                             | unknown key(s) [limits.max_row]",
            "allowed_tables: []                                      | allowed_tables must list at least one name",
            "allowed_tables: customers                               | allowed_tables must be a list",
            "allowed_tables: [\"orders; DROP TABLE x\"]               | is not a valid table/column/function name",
            "schema: [a, b]                                          | schema must be a name",
            "blocked_columns: [salary]                               | blocked_columns must map a table name",
            "limits:\\n  max_rows: 0                             | limits.max_rows must be between 1 and 10000",
            "limits:\\n  timeout_ms: 999999999                   | limits.timeout_ms must be between",
            "limits:\\n  max_rows: lots                          | limits.max_rows must be a whole number",
            "allowed_tables: [a\\n  b: c                           | not valid YAML",
            "examples: Revenue by month                              | examples must be a list of questions",
            "examples: [42]                                          | examples must be a list of questions",
            "share_results_with_ai: yes please                       | share_results_with_ai must be true or false",
    })
    void rejectsMistakesWithAClearMessage(String yaml, String expected) {
        var e = assertThrows(Policy.PolicyException.class, () -> Policy.parse(yaml.replace("\\n", "\n"), "test.yaml"));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
        assertTrue(e.getMessage().startsWith("test.yaml"), "error names the file: " + e.getMessage());
    }

    @Test
    void missingFileIsAnError(@TempDir Path dir) {
        var e = assertThrows(Policy.PolicyException.class, () -> Policy.load(dir.resolve("nope.yaml")));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void explicitPathWins(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("p.yaml");
        Files.writeString(f, "allowed_tables: [only_this]\n");
        Policy.Loaded loaded = Policy.resolve(f.toString());
        assertEquals(Set.of("only_this"), loaded.policy().allowedTables());
        assertEquals(f.toString(), loaded.source());
    }

    @Test
    void yamlOutputRoundTrips() throws Exception {
        Policy p = Policy.parse("""
                schema: public
                allowed_tables: [a, b]
                blocked_columns: {b: [secret]}
                masked_columns: [email]
                blocked_functions: [extra_fn]
                limits: {max_rows: 10, timeout_ms: 500}
                """, "test");
        assertEquals(p, Policy.parse(p.toYaml(), "roundtrip"));
    }

    @Test
    void customPolicyDrivesTheValidator() throws Exception {
        Policy p = Policy.parse("""
                allowed_tables: [orders]
                blocked_columns: {orders: [status]}
                """, "test");
        var v = new SqlValidator(p);
        v.validate("SELECT id FROM orders");
        assertEquals("TABLE_NOT_ALLOWED",
                assertThrows(SqlValidator.ValidationException.class, () -> v.validate("SELECT id FROM customers")).code());
        assertEquals("COLUMN_NOT_ALLOWED",
                assertThrows(SqlValidator.ValidationException.class, () -> v.validate("SELECT status FROM orders")).code());
    }
}
