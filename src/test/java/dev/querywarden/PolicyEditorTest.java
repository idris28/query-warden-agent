package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Changing policy.yaml and the models from the web page. */
class PolicyEditorTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> ALL = union(PolicyEditor.CONTENT_KEYS, PolicyEditor.RULE_KEYS);

    static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new java.util.HashSet<>(a);
        s.addAll(b);
        return s;
    }

    static String demoText() throws Exception {
        return Files.readString(Path.of("policy.yaml"));
    }

    static ObjectNode edits(String json) throws Exception {
        return (ObjectNode) JSON.readTree(json);
    }

    @Test
    void onlyChangedSectionsAreRewrittenAndCommentsStay() throws Exception {
        String text = demoText();
        Policy p = Policy.parse(text, "demo");
        var values = PolicyEditor.values(p);
        ((ObjectNode) values.get("definitions")).put("active customer", "A customer with an order in the last 90 days.");

        var edit = PolicyEditor.apply(text, p, values, PolicyEditor.CONTENT_KEYS);   // everything sent, one thing changed
        assertEquals(List.of("Definition added: active customer"), edit.changes().stream().map(PolicyEditor.Change::text).toList());
        assertEquals("A customer with an order in the last 90 days.", edit.policy().definitions().get("active customer"));
        assertTrue(edit.yaml().contains("# QueryWarden policy: what the agent may see and do."), "the header comment stays");
        assertTrue(edit.yaml().contains("# Measures defined as exact SQL"), "comments of other sections stay");
        String before = text.substring(0, text.indexOf("\nmetrics:")), after = edit.yaml().substring(0, edit.yaml().indexOf("\nmetrics:"));
        assertEquals(before, after, "nothing above the edited section moved");
        assertEquals(p.metrics(), edit.policy().metrics());

        var none = PolicyEditor.apply(text, p, PolicyEditor.values(p), PolicyEditor.CONTENT_KEYS);
        assertTrue(none.changes().isEmpty());
        assertEquals(text, none.yaml(), "no change, no rewrite");
    }

    @Test
    void rulesNeedTheRightToChangeThem() throws Exception {
        String text = demoText();
        Policy p = Policy.parse(text, "demo");
        var e = assertThrows(IllegalArgumentException.class, () -> PolicyEditor.apply(text, p,
                edits("{\"allowed_tables\": [\"customers\"]}"), PolicyEditor.CONTENT_KEYS));
        assertTrue(e.getMessage().contains("only be changed on the computer"));
        assertThrows(IllegalArgumentException.class, () -> PolicyEditor.apply(text, p, edits("{\"api_key\": \"x\"}"), ALL));
    }

    @Test
    void invalidResultsAreRejectedBeforeAnythingIsWritten() throws Exception {
        String text = demoText();
        Policy p = Policy.parse(text, "demo");
        var leak = assertThrows(Policy.PolicyException.class, () -> PolicyEditor.apply(text, p, edits("""
                {"metrics": {"payroll": {"description": "Total pay", "sql": "SUM(salary)", "from": "employees"}}}"""),
                PolicyEditor.CONTENT_KEYS));
        assertTrue(leak.getMessage().contains("metrics.payroll is not allowed"), leak.getMessage());
        assertThrows(Policy.PolicyException.class, () -> PolicyEditor.apply(text, p, edits("""
                {"limits": {"max_rows": 0, "timeout_ms": 3000, "max_joins": 4, "max_selects": 6,
                 "max_question_chars": 1000, "max_sql_chars": 4000, "max_tables_in_prompt": 30}}"""), PolicyEditor.CONTENT_KEYS));
        assertThrows(Policy.PolicyException.class, () -> PolicyEditor.apply(text, p, edits("{\"allowed_tables\": []}"), ALL));
    }

    @Test
    void changesThatOpenUpDataAreFlagged() throws Exception {
        String text = demoText();
        Policy p = Policy.parse(text, "demo");
        var safer = PolicyEditor.apply(text, p, edits("""
                {"blocked_columns": {"employees": ["salary", "ssn", "hire_date"]}, "masked_columns": ["email", "phone", "name"]}"""), ALL);
        assertFalse(safer.widens(), safer.changes().toString());
        assertTrue(safer.policy().isMasked("name"));

        for (String json : List.of(
                "{\"blocked_columns\": {\"employees\": [\"ssn\"]}}",
                "{\"masked_columns\": [\"phone\"]}",
                "{\"share_results_with_ai\": true}",
                "{\"allowed_tables\": [\"customers\", \"products\", \"orders\", \"order_items\", \"employees\", \"api_keys\"]}")) {
            Policy base = json.contains("share_results") ? Policy.parse(text.replace("share_results_with_ai: true", "share_results_with_ai: false"), "t") : p;
            String baseText = json.contains("share_results") ? text.replace("share_results_with_ai: true", "share_results_with_ai: false") : text;
            var e = PolicyEditor.apply(baseText, base, edits(json), ALL);
            assertTrue(e.widens(), json + " -> " + e.changes());
        }
    }

    @Test
    void saveKeepsABackupAndUndoGoesBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policy.yaml");
        Files.writeString(file, demoText());
        PolicyEditor editor = new PolicyEditor(file, dir.resolve(".querywarden"));
        Policy p = Policy.load(file);
        var edit = PolicyEditor.apply(Files.readString(file), p, edits("{\"ask_clarifying_questions\": false}"), ALL);
        editor.save(edit, "ann");
        assertFalse(Policy.load(file).askClarifyingQuestions());
        assertEquals(1, editor.backupCount());
        String log = Files.readString(dir.resolve(".querywarden/policy-changes.jsonl"));
        assertTrue(log.contains("\"by\":\"ann\"") && log.contains("Clarifying questions: off"), log);

        var undone = editor.undo("ann", Policy.load(file));
        assertTrue(Policy.load(file).askClarifyingQuestions());
        assertEquals("Clarifying questions: on", undone.get(0).text());
        assertEquals(0, editor.backupCount());
        assertThrows(IllegalStateException.class, () -> editor.undo("ann", Policy.load(file)));
    }

    @Test
    void textIsWrittenReadablyAndReadsBackExactly() throws Exception {
        var yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        for (String t : List.of("An order with status 'delivered'.", "x: y", "#hash", "true", "123", "- dash", "",
                "\"Last N days\", \"recent\" and \"latest\" count back from the latest order_date in the orders table, not from today's date.",
                "Two  spaces stay", "line\nbreak", "ends with colon:", "SUM(oi.quantity * oi.unit_price)")) {
            String doc = "k: " + PolicyEditor.scalar(t, 4).replace("\n", "\n");
            assertEquals(t, yaml.readTree(doc).get("k").asText(), "round trip of [" + t + "] via\n" + doc);
        }
        assertEquals("An order with status 'delivered'.", PolicyEditor.scalar("An order with status 'delivered'.", 4));
        assertTrue(PolicyEditor.scalar("word ".repeat(40).strip(), 4).startsWith(">-\n    word"), "long text is wrapped");
    }

    @Test
    void limitsKeepTheirCommentsWhenChanged() throws Exception {
        String text = demoText();
        Policy p = Policy.parse(text, "demo");
        var values = PolicyEditor.values(p);
        ((ObjectNode) values.get("limits")).put("max_rows", 500);
        var edit = PolicyEditor.apply(text, p, values, PolicyEditor.CONTENT_KEYS);
        assertEquals(500, edit.policy().maxRows());
        assertTrue(edit.yaml().contains("max_rows: 500             # results are cut to this many rows"), edit.yaml());
        assertTrue(edit.yaml().contains("# longest question accepted"), "other lines untouched");
    }

    @Test
    void sectionsMissingFromTheFileAreAdded() {
        String text = "# mine\nschema: shop\nallowed_tables: [orders]\n";
        String out = PolicyEditor.replaceSection(text, "definitions", "definitions:\n  \"x\": \"y\"");
        assertTrue(out.startsWith("# mine\nschema: shop\nallowed_tables: [orders]\n"));
        assertTrue(out.contains("\ndefinitions:\n  \"x\": \"y\"\n"));
    }

    @Test
    void modelsSwitchLiveAndCanGoBackToTheDefaults(@TempDir Path dir) throws Exception {
        List<String> built = new ArrayList<>();
        Path file = dir.resolve("models.json");
        ModelSwitch sw = new ModelSwitch(file, ModelSettings.defaults(),
                m -> { built.add(m.sqlModel()); return sys -> text -> new SqlGenerator.Output(true, "SELECT 1", "", ""); },
                m -> AiHelper.NONE);
        assertEquals(ModelSettings.DEFAULT_SQL_MODEL, sw.current().sqlModel());
        sw.save(Map.of("sql_model", "claude-opus-5-5", "sql_effort", "high"));
        assertEquals("claude-opus-5-5", sw.current().sqlModel());
        assertEquals(List.of(ModelSettings.DEFAULT_SQL_MODEL, "claude-opus-5-5"), built, "a new client for the new model");
        assertEquals("claude-opus-5-5", ModelSwitch.withSaved(ModelSettings.defaults(), file).sqlModel(), "kept for the next start");
        assertThrows(IllegalArgumentException.class, () -> sw.save(Map.of("sql_model", "gpt-5")));
        assertThrows(IllegalArgumentException.class, () -> sw.save(Map.of("sql_effort", "extreme")));
        sw.reset();
        assertFalse(Files.exists(file));
        assertEquals(ModelSettings.DEFAULT_SQL_MODEL, sw.current().sqlModel());
    }
}
