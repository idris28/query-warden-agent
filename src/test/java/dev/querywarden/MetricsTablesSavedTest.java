package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Structured metrics, table picking for large databases, and saved questions (no database needed). */
class MetricsTablesSavedTest {

    static final String METRICS = """
            metrics:
              revenue:
                description: Money from orders that weren't cancelled.
                sql: SUM(oi.quantity * oi.unit_price)
                from: order_items oi JOIN orders o ON o.id = oi.order_id
                where: o.status <> 'cancelled'
                time_column: o.order_date
            """;

    // ------------------------------------------------------------------ metrics
    @Test
    void metricsAreParsedCheckedAndRoundTripped() throws Exception {
        Policy p = Policy.parse(Policy.demo(), METRICS, "test");
        Policy.Metric m = p.metrics().get("revenue");
        assertEquals("SELECT SUM(oi.quantity * oi.unit_price) AS revenue FROM order_items oi JOIN orders o ON o.id = oi.order_id"
                + " WHERE o.status <> 'cancelled'", m.query("revenue"));
        assertTrue(p.metricsText().contains("time column: o.order_date"));
        assertEquals(p, Policy.parse(Policy.demo(), p.toYaml(), "roundtrip"));
        assertTrue(QueryWardenAgent.definitionsBlock(p).contains("Metrics."));
        assertEquals("", QueryWardenAgent.definitionsBlock(Policy.demo()));
    }

    @Test
    void metricsMustPassTheSameChecksAsClaudesSql() {
        var blocked = assertThrows(Policy.PolicyException.class, () -> Policy.parse(Policy.demo(), """
                metrics:
                  payroll: {description: Total pay, sql: SUM(salary), from: employees}
                """, "t"));
        assertTrue(blocked.getMessage().contains("metrics.payroll is not allowed"), blocked.getMessage());
        var table = assertThrows(Policy.PolicyException.class, () -> Policy.parse(Policy.demo(), """
                metrics:
                  keys: {description: Keys, sql: COUNT(*), from: api_keys}
                """, "t"));
        assertTrue(table.getMessage().contains("not allowed"), table.getMessage());
        var semicolon = assertThrows(Policy.PolicyException.class, () -> Policy.parse(Policy.demo(), """
                metrics:
                  n: {description: N, sql: "COUNT(*)", from: "orders; DROP TABLE orders"}
                """, "t"));
        assertTrue(semicolon.getMessage().contains("';'"));
        var unknown = assertThrows(Policy.PolicyException.class, () -> Policy.parse(Policy.demo(), """
                metrics:
                  n: {description: N, sql: "COUNT(*)", from: orders, group_by: status}
                """, "t"));
        assertTrue(unknown.getMessage().contains("unknown key"));
        var missing = assertThrows(Policy.PolicyException.class, () -> Policy.parse(Policy.demo(), """
                metrics:
                  n: {description: N, sql: "COUNT(*)"}
                """, "t"));
        assertTrue(missing.getMessage().contains("metrics.n.from is required"));
    }

    @Test
    void tablesInPromptLimitIsAPolicySetting() throws Exception {
        assertEquals(30, Policy.demo().maxTablesInPrompt());
        assertEquals(5, Policy.parse(Policy.demo(), "limits: {max_tables_in_prompt: 5}", "t").maxTablesInPrompt());
        assertThrows(Policy.PolicyException.class, () -> Policy.parse(Policy.demo(), "limits: {max_tables_in_prompt: 0}", "t"));
    }

    // ------------------------------------------------------------------ table picking
    static Database.TableInfo table(String name, List<String> cols, String... fks) {
        List<Database.ColumnInfo> c = new ArrayList<>();
        for (String col : cols) c.add(new Database.ColumnInfo(col, col.endsWith("_id") || col.equals("id") ? "integer" : "text", null));
        return new Database.TableInfo(name, c, List.of(fks));
    }

    static List<Database.TableInfo> bigSchema() {
        List<Database.TableInfo> t = new ArrayList<>();
        t.add(table("customers", List.of("id", "name", "country")));
        t.add(table("orders", List.of("id", "customer_id", "status"), "customer_id -> customers.id"));
        t.add(table("products", List.of("id", "name", "category")));
        t.add(table("order_items", List.of("id", "order_id", "product_id", "quantity"),
                "order_id -> orders.id", "product_id -> products.id"));
        t.add(table("employees", List.of("id", "name", "department")));
        t.add(table("warehouses", List.of("id", "city")));
        for (int i = 0; i < 40; i++) t.add(table("audit_log_" + i, List.of("id", "event", "payload")));
        return t;
    }

    @Test
    void picksMatchingTablesAndTheTablesThatLinkThem() {
        TableSelector sel = new TableSelector(bigSchema());
        List<String> picked = sel.select("Which product categories sell best to customers in Germany?", List.of(), 5)
                .stream().map(Database.TableInfo::name).toList();
        assertTrue(picked.containsAll(List.of("products", "customers")), picked.toString());
        assertTrue(picked.contains("orders") && picked.contains("order_items"), "the join path is included: " + picked);
        assertFalse(picked.contains("employees"), picked.toString());
        assertTrue(picked.size() <= 5);

        List<String> staff = sel.select("How many employees per department?", List.of(), 3)
                .stream().map(Database.TableInfo::name).toList();
        assertEquals("employees", staff.get(0));
    }

    @Test
    void tablesUsedEarlierInTheConversationAreAlwaysShown() {
        TableSelector sel = new TableSelector(bigSchema());
        List<String> picked = sel.select("now only Germany", List.of("SELECT city FROM warehouses"), 3)
                .stream().map(Database.TableInfo::name).toList();
        assertTrue(picked.contains("warehouses"), picked.toString());
        assertEquals("category", TableSelector.stem("categories"));
        assertEquals("customer", TableSelector.stem("customers"));
        assertEquals("status", TableSelector.stem("statuses"));
    }

    // ------------------------------------------------------------------ schedules
    static final ZoneId UTC = ZoneOffset.UTC;

    @Test
    void schedulesAreNormalisedAndValidated() {
        assertEquals("", SavedQuestions.Schedule.normalise(" Off "));
        assertEquals("every 1h", SavedQuestions.Schedule.normalise("hourly"));
        assertEquals("every 15m", SavedQuestions.Schedule.normalise("every 15 minutes"));
        assertEquals("daily 08:00", SavedQuestions.Schedule.normalise("Daily 8:00"));
        assertEquals("weekly mon 07:30", SavedQuestions.Schedule.normalise("weekly monday 7:30"));
        assertThrows(IllegalArgumentException.class, () -> SavedQuestions.Schedule.normalise("every 1m"));
        assertThrows(IllegalArgumentException.class, () -> SavedQuestions.Schedule.normalise("sometimes"));
        assertThrows(IllegalArgumentException.class, () -> SavedQuestions.Schedule.normalise("daily 25:00"));
    }

    @Test
    void nextRunTimes() {
        Instant fri = Instant.parse("2026-11-27T09:00:00Z");   // a Friday
        assertEquals(Instant.parse("2026-11-27T09:15:00Z"), SavedQuestions.Schedule.next("every 15m", fri, UTC));
        assertEquals(Instant.parse("2026-11-28T08:00:00Z"), SavedQuestions.Schedule.next("daily 08:00", fri, UTC));
        assertEquals(Instant.parse("2026-11-27T10:00:00Z"), SavedQuestions.Schedule.next("daily 10:00", fri, UTC));
        assertEquals(Instant.parse("2026-11-30T08:00:00Z"), SavedQuestions.Schedule.next("weekdays 08:00", fri, UTC), "skips the weekend");
        assertEquals(Instant.parse("2026-11-30T08:00:00Z"), SavedQuestions.Schedule.next("weekly mon 08:00", fri, UTC));
    }

    // ------------------------------------------------------------------ saved questions and alerts
    static SafeExecutor.QueryResult result(List<String> cols, List<String> types, List<List<Object>> rows) {
        return new SafeExecutor.QueryResult(cols, types, rows, false, 1, 0);
    }

    @Test
    void savedQuestionsAreStoredAndDue(@TempDir Path dir) throws Exception {
        SavedQuestions store = new SavedQuestions(dir.resolve("s.json"));
        var a = store.add("Open orders", "How many open orders?", "SELECT COUNT(*) AS n FROM orders", "ann", "every 15m",
                new SavedQuestions.Alert("n", ">", 20.0));
        store.add("", "Orders per status", "SELECT status, COUNT(*) FROM orders GROUP BY status", "bob", "", null);
        assertEquals(2, new SavedQuestions(dir.resolve("s.json")).list().size(), "another process sees them");
        assertEquals("Orders per status", store.list().get(1).name(), "the question is the default name");

        Instant created = Instant.parse(a.created());
        assertTrue(store.due(created.plusSeconds(60), UTC).isEmpty());
        assertEquals(List.of(a.id()), store.due(created.plusSeconds(16 * 60), UTC).stream().map(SavedQuestions.Saved::id).toList(),
                "unscheduled questions are never due");
        store.update(a.id(), s -> s.withRun(created.plusSeconds(16 * 60).toString(), "ok", "3", false, ""));
        assertTrue(store.due(created.plusSeconds(20 * 60), UTC).isEmpty(), "counted from the last run");
        assertTrue(store.remove(a.id()));
        assertThrows(IllegalArgumentException.class, () -> store.add("x", "q", "SELECT 1", "a", "whenever", null));
    }

    @Test
    void alertsCompareValuesOrNoticeChanges() {
        var r = result(List.of("status", "n"), List.of("text", "number"),
                List.of(List.of("pending", 12), List.of("shipped", 31)));
        var above = new SavedQuestions.Saved("s_1", "Orders by status", "q", "sql", "", "", "every 1h",
                new SavedQuestions.Alert("", ">", 30.0), "", "", "", false, "");
        var c = SavedQuestions.check(above, r);
        assertTrue(c.fires());
        assertEquals("12", c.value(), "the first number column's first value");
        assertTrue(c.message().contains("1 row(s) where the value > 30"), c.message());

        var below = new SavedQuestions.Saved("s_2", "x", "q", "sql", "", "", "", new SavedQuestions.Alert("n", "<", 5.0),
                "", "", "", false, "");
        assertFalse(SavedQuestions.check(below, r).fires());

        var changes = new SavedQuestions.Saved("s_3", "Pending", "q", "sql", "", "", "", new SavedQuestions.Alert("n", "changes", null),
                "", "", "", false, "");
        assertFalse(SavedQuestions.check(changes, r).fires(), "nothing to compare with on the first run");
        var changed = changes.withRun("t", "ok", "10", false, "");
        assertTrue(SavedQuestions.check(changed, r).fires());
        assertTrue(SavedQuestions.check(changed, r).message().contains("10 → 12"));

        var missingColumn = new SavedQuestions.Saved("s_4", "x", "q", "sql", "", "", "", new SavedQuestions.Alert("nope", ">", 1.0),
                "", "", "", false, "");
        assertFalse(SavedQuestions.check(missingColumn, r).fires());
        assertThrows(IllegalArgumentException.class, () -> new SavedQuestions.Alert("n", "~", 1.0));
        assertThrows(IllegalArgumentException.class, () -> new SavedQuestions.Alert("n", ">", null));
    }
}
