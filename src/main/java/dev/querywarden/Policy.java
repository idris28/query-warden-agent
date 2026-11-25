package dev.querywarden;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Single source of truth for every guardrail layer: the prompt (what schema Claude sees),
 * the static validator, the database GRANTs created by {@link Database#setup}, and output masking.
 *
 * <p>Normally loaded from a YAML file ({@code policy.yaml}); see {@link #resolve} and {@link #load}.
 */
public record Policy(
        String schema,
        Set<String> allowedTables,
        Map<String, Set<String>> blockedColumns,
        Set<String> maskedColumns,
        int maxRows,
        int timeoutMillis,
        int maxJoins,
        int maxSelects,
        int maxQuestionChars,
        int maxSqlChars,
        Set<String> blockedFunctions,
        List<String> examples,
        boolean shareResultsWithAi,
        Map<String, String> definitions,
        boolean shareCategoryValues,
        boolean askClarifyingQuestions,
        Map<String, Metric> metrics,
        int maxTablesInPrompt) {

    /**
     * A measure defined as data: always computed with exactly this SQL.
     * The query is {@code SELECT <sql> AS <name> FROM <from> [WHERE <where>]}; Claude adds grouping and
     * time filters around it. {@code timeColumn} is the column to use for "per month", "in 2025", ….
     */
    public record Metric(String description, String sql, String from, String where, String timeColumn) {
        public String query(String name) {
            return "SELECT " + sql + " AS " + name + " FROM " + from + (where.isEmpty() ? "" : " WHERE " + where);
        }
    }

    public static final int MAX_METRICS = 40;
    private static final Set<String> METRIC_KEYS = Set.of("description", "sql", "from", "where", "time_column");

    /** Dangerous PostgreSQL functions. Always blocked; a policy file can only add to this list. */
    public static final Set<String> BUILT_IN_BLOCKED_FUNCTIONS = Set.of(
            // sleeping / resource abuse
            "pg_sleep", "pg_sleep_for", "pg_sleep_until",
            // file system and server access
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
            "lo_import", "lo_export", "lo_get", "lo_open",
            // session / server manipulation
            "set_config", "current_setting", "pg_terminate_backend", "pg_cancel_backend",
            "pg_reload_conf", "pg_advisory_lock", "pg_advisory_xact_lock",
            // remote access and running SQL from a string
            "dblink", "dblink_exec", "query_to_xml", "query_to_json",
            "table_to_xml", "cursor_to_xml", "xmlparse");

    /**
     * The starting point for any policy file: nothing about a particular database. A policy file must
     * list its own {@code allowed_tables} ({@code querywarden init} writes one from the database).
     */
    public static Policy defaults() {
        return base("public", Set.of(), Map.of(), Set.of());
    }

    /** The policy for the sample {@code shop} database that {@code setup} creates (and tests use). */
    public static Policy demo() {
        return base("shop",
                Set.of("customers", "products", "orders", "order_items", "employees"),
                Map.of("employees", Set.of("salary", "ssn")),
                Set.of("email", "phone"));
    }

    private static Policy base(String schema, Set<String> tables, Map<String, Set<String>> blocked, Set<String> masked) {
        return new Policy(
                schema,
                tables,
                blocked,
                masked,
                200,
                3000,
                4,
                6,
                1000,
                4000,
                BUILT_IN_BLOCKED_FUNCTIONS,
                List.of(),     // no examples: the web page asks Claude to suggest some
                false,         // result rows never leave this machine unless the policy says so
                Map.of(),      // no business definitions
                false,         // category values (e.g. country codes) aren't sent to Claude unless the policy says so
                true,          // ask a short clarifying question when a question is genuinely ambiguous
                Map.of(),      // no structured metrics
                30);           // above this many tables, only the relevant ones are sent with each question
    }

    /** The metrics as prompt text, or "" when there are none. */
    public String metricsText() {
        StringBuilder sb = new StringBuilder();
        metrics.forEach((name, m) -> {
            sb.append("- ").append(name).append(": ").append(m.description()).append('\n')
              .append("  ").append(m.query(name)).append('\n');
            if (!m.timeColumn().isEmpty()) sb.append("  time column: ").append(m.timeColumn()).append('\n');
        });
        return sb.toString().strip();
    }

    public static final int MAX_EXAMPLES = 12;
    public static final int MAX_DEFINITIONS = 40;
    public static final int MAX_TERM_CHARS = 60;
    public static final int MAX_DEFINITION_CHARS = 400;

    /** The definitions as prompt text ("- term: meaning" lines), or "" when there are none. */
    public String definitionsText() {
        StringBuilder sb = new StringBuilder();
        definitions.forEach((t, m) -> sb.append("- ").append(t).append(": ").append(m).append('\n'));
        return sb.toString().strip();
    }
    public static final int MAX_EXAMPLE_CHARS = 200;

    // ------------------------------------------------------------------ loading from YAML

    /** A problem in a policy file. The message says which key is wrong and why. */
    public static final class PolicyException extends Exception {
        public PolicyException(String message) {
            super(message);
        }
    }

    /** A policy plus a description of where it came from (shown to the user). */
    /** {@code path} is the file that was read, or null for the built-in defaults. */
    public record Loaded(Policy policy, String source, Path path) {}

    public static final String DEFAULT_FILE = "policy.yaml";

    private static final Set<String> TOP_KEYS = Set.of(
            "schema", "allowed_tables", "blocked_columns", "masked_columns", "blocked_functions", "limits",
            "examples", "share_results_with_ai", "definitions", "share_category_values",
            "ask_clarifying_questions", "metrics");
    private static final Set<String> LIMIT_KEYS = Set.of(
            "max_rows", "timeout_ms", "max_joins", "max_selects", "max_question_chars", "max_sql_chars",
            "max_tables_in_prompt");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    /**
     * Finds the policy to use, in this order:
     * the {@code --policy} path if given, then the {@code QW_POLICY} environment variable,
     * then {@code policy.yaml} in the current directory. A path given explicitly (flag or variable) must
     * exist; with no file at all, there is no policy ({@code querywarden init} creates one).
     */
    public static Loaded resolve(String cliPath) throws PolicyException {
        if (cliPath != null && !cliPath.isBlank()) return new Loaded(load(Path.of(cliPath)), cliPath, Path.of(cliPath));
        String env = System.getenv("QW_POLICY");
        if (env != null && !env.isBlank()) return new Loaded(load(Path.of(env)), env + " (from QW_POLICY)", Path.of(env));
        Path local = Path.of(DEFAULT_FILE);
        if (Files.isRegularFile(local)) return new Loaded(load(local), DEFAULT_FILE, local);
        throw new PolicyException("No " + DEFAULT_FILE + " found. Create one from your database with:\n"
                + "  java -jar target/querywarden.jar init\n"
                + "(or point to one with --policy FILE or QW_POLICY).");
    }

    /** Reads and validates a YAML policy file. Keys that are left out keep their default values. */
    public static Policy load(Path file) throws PolicyException {
        if (!Files.isRegularFile(file)) throw new PolicyException("Policy file not found: " + file);
        try {
            return parse(Files.readString(file), file.toString());
        } catch (IOException e) {
            throw new PolicyException("Could not read policy file " + file + ": " + e.getMessage());
        }
    }

    /** Parses YAML policy text. {@code origin} is only used in error messages. */
    public static Policy parse(String yaml, String origin) throws PolicyException {
        return parse(defaults(), yaml, origin);
    }

    /** Parses YAML policy text on top of {@code base}: keys left out keep the base's values. */
    public static Policy parse(Policy base, String yaml, String origin) throws PolicyException {
        JsonNode root;
        try {
            root = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
            throw new PolicyException(origin + ": not valid YAML: " + msg);
        }
        Policy d = base;
        if (root == null || root.isMissingNode() || root.isNull()) root = new ObjectMapper().createObjectNode();
        if (!root.isObject()) throw new PolicyException(origin + ": expected key: value pairs at the top level.");
        checkKeys(root, TOP_KEYS, origin, "");

        String schema = root.has("schema") ? identifier(root.get("schema"), origin, "schema") : d.schema();
        Set<String> allowed = root.has("allowed_tables")
                ? identifiers(root.get("allowed_tables"), origin, "allowed_tables", false) : d.allowedTables();

        Map<String, Set<String>> blocked = d.blockedColumns();
        if (root.has("blocked_columns")) {
            JsonNode bc = root.get("blocked_columns");
            if (bc.isNull()) {
                blocked = Map.of();
            } else {
                if (!bc.isObject())
                    throw new PolicyException(origin + ": blocked_columns must map a table name to a list of columns.");
                blocked = new LinkedHashMap<>();
                for (Iterator<Map.Entry<String, JsonNode>> it = bc.fields(); it.hasNext(); ) {
                    var e = it.next();
                    String table = identifierText(e.getKey(), origin, "blocked_columns");
                    blocked.put(table, identifiers(e.getValue(), origin, "blocked_columns." + e.getKey(), true));
                }
            }
        }
        Set<String> masked = root.has("masked_columns")
                ? identifiers(root.get("masked_columns"), origin, "masked_columns", true) : d.maskedColumns();
        Set<String> functions = new LinkedHashSet<>(BUILT_IN_BLOCKED_FUNCTIONS);
        if (root.has("blocked_functions"))
            functions.addAll(identifiers(root.get("blocked_functions"), origin, "blocked_functions", true));

        List<String> examples = d.examples();
        if (root.has("examples") && !root.get("examples").isNull()) {
            JsonNode ex = root.get("examples");
            if (!ex.isArray()) throw new PolicyException(origin + ": examples must be a list of questions.");
            List<String> list = new ArrayList<>();
            for (JsonNode item : ex) {
                if (!item.isTextual() || item.asText().isBlank())
                    throw new PolicyException(origin + ": examples must be a list of questions (text).");
                String q = item.asText().strip();
                if (q.length() > MAX_EXAMPLE_CHARS)
                    throw new PolicyException(origin + ": examples: '" + q.substring(0, 40) + "…' is longer than "
                            + MAX_EXAMPLE_CHARS + " characters.");
                list.add(q);
            }
            if (list.size() > MAX_EXAMPLES)
                throw new PolicyException(origin + ": examples: at most " + MAX_EXAMPLES + " questions (got " + list.size() + ").");
            examples = List.copyOf(list);
        }
        Map<String, String> definitions = d.definitions();
        if (root.has("definitions") && !root.get("definitions").isNull()) {
            JsonNode defs = root.get("definitions");
            if (!defs.isObject())
                throw new PolicyException(origin + ": definitions must map a term to its meaning, e.g. revenue: \"…\".");
            Map<String, String> m = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = defs.fields(); it.hasNext(); ) {
                var e = it.next();
                String term = e.getKey().strip();
                if (term.isEmpty() || term.length() > MAX_TERM_CHARS)
                    throw new PolicyException(origin + ": definitions: term '" + term + "' must be 1–" + MAX_TERM_CHARS + " characters.");
                if (!e.getValue().isTextual() || e.getValue().asText().isBlank())
                    throw new PolicyException(origin + ": definitions." + term + " must be a sentence describing it.");
                String meaning = e.getValue().asText().strip();
                if (meaning.length() > MAX_DEFINITION_CHARS)
                    throw new PolicyException(origin + ": definitions." + term + " is longer than " + MAX_DEFINITION_CHARS + " characters.");
                m.put(term, meaning);
            }
            if (m.size() > MAX_DEFINITIONS)
                throw new PolicyException(origin + ": definitions: at most " + MAX_DEFINITIONS + " terms (got " + m.size() + ").");
            definitions = java.util.Collections.unmodifiableMap(m);
        }
        boolean clarify = d.askClarifyingQuestions();
        if (root.has("ask_clarifying_questions") && !root.get("ask_clarifying_questions").isNull()) {
            JsonNode cq = root.get("ask_clarifying_questions");
            if (!cq.isBoolean()) throw new PolicyException(origin + ": ask_clarifying_questions must be true or false.");
            clarify = cq.asBoolean();
        }
        boolean categories = d.shareCategoryValues();
        if (root.has("share_category_values") && !root.get("share_category_values").isNull()) {
            JsonNode cv = root.get("share_category_values");
            if (!cv.isBoolean()) throw new PolicyException(origin + ": share_category_values must be true or false.");
            categories = cv.asBoolean();
        }
        boolean share = d.shareResultsWithAi();
        if (root.has("share_results_with_ai") && !root.get("share_results_with_ai").isNull()) {
            JsonNode sh = root.get("share_results_with_ai");
            if (!sh.isBoolean()) throw new PolicyException(origin + ": share_results_with_ai must be true or false.");
            share = sh.asBoolean();
        }

        Map<String, Metric> metrics = new LinkedHashMap<>();
        if (root.has("metrics") && !root.get("metrics").isNull()) {
            JsonNode ms = root.get("metrics");
            if (!ms.isObject())
                throw new PolicyException(origin + ": metrics must map a name to its description, sql and from.");
            for (Iterator<Map.Entry<String, JsonNode>> it = ms.fields(); it.hasNext(); ) {
                var e = it.next();
                String name = identifierText(e.getKey(), origin, "metrics");
                JsonNode m = e.getValue();
                if (!m.isObject())
                    throw new PolicyException(origin + ": metrics." + name + " needs description, sql and from.");
                checkKeys(m, METRIC_KEYS, origin, "metrics." + name + ".");
                metrics.put(name, new Metric(
                        metricText(m, "description", true, origin, name),
                        metricText(m, "sql", true, origin, name),
                        metricText(m, "from", true, origin, name),
                        metricText(m, "where", false, origin, name),
                        metricText(m, "time_column", false, origin, name)));
            }
            if (metrics.size() > MAX_METRICS)
                throw new PolicyException(origin + ": metrics: at most " + MAX_METRICS + " (got " + metrics.size() + ").");
        }

        JsonNode limits = root.path("limits");
        if (!limits.isMissingNode() && !limits.isNull()) {
            if (!limits.isObject()) throw new PolicyException(origin + ": limits must be a set of key: value pairs.");
            checkKeys(limits, LIMIT_KEYS, origin, "limits.");
        }
        if (allowed.isEmpty())
            throw new PolicyException(origin + ": allowed_tables must list the tables QueryWarden may query. "
                    + "`querywarden init` writes a policy with them from your database.");
        Policy p = new Policy(
                schema, Set.copyOf(allowed), Map.copyOf(blocked), Set.copyOf(masked),
                limit(limits, "max_rows", d.maxRows(), 1, 10_000, origin),
                limit(limits, "timeout_ms", d.timeoutMillis(), 100, 600_000, origin),
                limit(limits, "max_joins", d.maxJoins(), 0, 50, origin),
                limit(limits, "max_selects", d.maxSelects(), 1, 50, origin),
                limit(limits, "max_question_chars", d.maxQuestionChars(), 1, 100_000, origin),
                limit(limits, "max_sql_chars", d.maxSqlChars(), 100, 100_000, origin),
                Set.copyOf(functions),
                examples,
                share,
                definitions,
                categories,
                clarify,
                java.util.Collections.unmodifiableMap(metrics),
                limit(limits, "max_tables_in_prompt", d.maxTablesInPrompt(), 1, 10_000, origin));
        // every metric must pass the same checks as Claude's SQL: allowed tables and columns only
        SqlValidator validator = new SqlValidator(p);
        for (var e : p.metrics().entrySet()) {
            try {
                validator.validate(e.getValue().query(e.getKey()));
            } catch (SqlValidator.ValidationException ex) {
                throw new PolicyException(origin + ": metrics." + e.getKey() + " is not allowed: " + ex.getMessage());
            }
        }
        return p;
    }

    private static String metricText(JsonNode m, String key, boolean required, String origin, String name)
            throws PolicyException {
        JsonNode n = m.get(key);
        if (n == null || n.isNull() || (n.isTextual() && n.asText().isBlank())) {
            if (required) throw new PolicyException(origin + ": metrics." + name + "." + key + " is required.");
            return "";
        }
        if (!n.isTextual()) throw new PolicyException(origin + ": metrics." + name + "." + key + " must be text.");
        String t = n.asText().strip().replaceAll("\\s+", " ");
        if (t.length() > 1000) throw new PolicyException(origin + ": metrics." + name + "." + key + " is too long.");
        if (t.contains(";")) throw new PolicyException(origin + ": metrics." + name + "." + key + " must not contain ';'.");
        return t;
    }

    private static void checkKeys(JsonNode node, Set<String> known, String origin, String prefix) throws PolicyException {
        List<String> unknown = new ArrayList<>();
        node.fieldNames().forEachRemaining(k -> { if (!known.contains(k)) unknown.add(prefix + k); });
        if (!unknown.isEmpty())
            throw new PolicyException(origin + ": unknown key(s) " + unknown + ". Allowed: "
                    + known.stream().sorted().map(k -> prefix + k).toList());
    }

    private static String identifier(JsonNode n, String origin, String key) throws PolicyException {
        if (n == null || !n.isTextual()) throw new PolicyException(origin + ": " + key + " must be a name.");
        return identifierText(n.asText(), origin, key);
    }

    private static String identifierText(String text, String origin, String key) throws PolicyException {
        String t = text == null ? "" : text.trim();
        if (!IDENTIFIER.matcher(t).matches())
            throw new PolicyException(origin + ": " + key + ": '" + t + "' is not a valid table/column/function name.");
        return t.toLowerCase(Locale.ROOT);
    }

    private static Set<String> identifiers(JsonNode n, String origin, String key, boolean mayBeEmpty)
            throws PolicyException {
        if (n == null || n.isNull()) {
            if (mayBeEmpty) return Set.of();
            throw new PolicyException(origin + ": " + key + " must list at least one name.");
        }
        if (!n.isArray()) throw new PolicyException(origin + ": " + key + " must be a list, e.g. [a, b].");
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode item : n) out.add(identifier(item, origin, key));
        if (out.isEmpty() && !mayBeEmpty) throw new PolicyException(origin + ": " + key + " must list at least one name.");
        return out;
    }

    private static int limit(JsonNode limits, String key, int def, int min, int max, String origin)
            throws PolicyException {
        JsonNode n = limits.path(key);
        if (n.isMissingNode() || n.isNull()) return def;
        if (!n.isIntegralNumber())
            throw new PolicyException(origin + ": limits." + key + " must be a whole number.");
        long v = n.asLong();
        if (v < min || v > max)
            throw new PolicyException(origin + ": limits." + key + " must be between " + min + " and " + max + " (got " + v + ").");
        return (int) v;
    }

    /** The policy as YAML text, for `querywarden policy`. */
    public String toYaml() {
        StringBuilder sb = new StringBuilder();
        sb.append("schema: ").append(schema).append('\n');
        sb.append("allowed_tables: ").append(sorted(allowedTables)).append('\n');
        sb.append("blocked_columns:").append(blockedColumns.isEmpty() ? " {}" : "").append('\n');
        blockedColumns.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("  ").append(e.getKey()).append(": ").append(sorted(e.getValue())).append('\n'));
        sb.append("masked_columns: ").append(sorted(maskedColumns)).append('\n');
        Set<String> extra = new LinkedHashSet<>(blockedFunctions);
        extra.removeAll(BUILT_IN_BLOCKED_FUNCTIONS);
        sb.append("blocked_functions: ").append(sorted(extra))
                .append("   # plus ").append(BUILT_IN_BLOCKED_FUNCTIONS.size()).append(" built-in\n");
        try {
            // JSON arrays are valid YAML, and this quotes the questions safely
            sb.append("examples: ").append(new ObjectMapper().writeValueAsString(examples)).append('\n');
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        sb.append("share_results_with_ai: ").append(shareResultsWithAi).append('\n');
        sb.append("share_category_values: ").append(shareCategoryValues).append('\n');
        sb.append("ask_clarifying_questions: ").append(askClarifyingQuestions).append('\n');
        try {
            sb.append("definitions: ").append(new ObjectMapper().writeValueAsString(definitions)).append('\n');
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (!metrics.isEmpty()) {
            sb.append("metrics:\n");
            try {
                ObjectMapper j = new ObjectMapper();
                for (var e : metrics.entrySet()) {
                    Metric m = e.getValue();
                    sb.append("  ").append(e.getKey()).append(":\n");
                    sb.append("    description: ").append(j.writeValueAsString(m.description())).append('\n');
                    sb.append("    sql: ").append(j.writeValueAsString(m.sql())).append('\n');
                    sb.append("    from: ").append(j.writeValueAsString(m.from())).append('\n');
                    if (!m.where().isEmpty()) sb.append("    where: ").append(j.writeValueAsString(m.where())).append('\n');
                    if (!m.timeColumn().isEmpty())
                        sb.append("    time_column: ").append(j.writeValueAsString(m.timeColumn())).append('\n');
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        sb.append("limits:\n");
        sb.append("  max_rows: ").append(maxRows).append('\n');
        sb.append("  timeout_ms: ").append(timeoutMillis).append('\n');
        sb.append("  max_joins: ").append(maxJoins).append('\n');
        sb.append("  max_selects: ").append(maxSelects).append('\n');
        sb.append("  max_question_chars: ").append(maxQuestionChars).append('\n');
        sb.append("  max_sql_chars: ").append(maxSqlChars).append('\n');
        sb.append("  max_tables_in_prompt: ").append(maxTablesInPrompt).append('\n');
        return sb.toString();
    }

    private static String sorted(Set<String> s) {
        return "[" + String.join(", ", s.stream().sorted().toList()) + "]";
    }

    public boolean tableAllowed(String table) {
        return allowedTables.contains(norm(table));
    }

    public boolean columnAllowed(String table, String column) {
        return !blockedColumns.getOrDefault(norm(table), Set.of()).contains(norm(column));
    }

    public boolean hasBlockedColumns(String table) {
        return !blockedColumns.getOrDefault(norm(table), Set.of()).isEmpty();
    }

    public boolean isMasked(String column) {
        return maskedColumns.contains(norm(column));
    }

    public boolean functionBlocked(String name) {
        String n = norm(name);
        int dot = n.lastIndexOf('.');
        return blockedFunctions.contains(dot >= 0 ? n.substring(dot + 1) : n);
    }

    public Policy withMaxRows(int rows) {
        return new Policy(schema, allowedTables, blockedColumns, maskedColumns, rows, timeoutMillis,
                maxJoins, maxSelects, maxQuestionChars, maxSqlChars, blockedFunctions, examples, shareResultsWithAi, definitions, shareCategoryValues, askClarifyingQuestions, metrics, maxTablesInPrompt);
    }

    public Policy withTimeoutMillis(int ms) {
        return new Policy(schema, allowedTables, blockedColumns, maskedColumns, maxRows, ms,
                maxJoins, maxSelects, maxQuestionChars, maxSqlChars, blockedFunctions, examples, shareResultsWithAi, definitions, shareCategoryValues, askClarifyingQuestions, metrics, maxTablesInPrompt);
    }

    static String norm(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }
}
