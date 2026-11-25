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
        boolean shareResultsWithAi) {

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

    /** Built-in policy for the demo {@code shop} database. The shipped policy.yaml matches it. */
    public static Policy defaults() {
        return new Policy(
                "shop",
                Set.of("customers", "products", "orders", "order_items", "employees"),
                Map.of("employees", Set.of("salary", "ssn")),
                Set.of("email", "phone"),
                200,
                3000,
                4,
                6,
                1000,
                4000,
                BUILT_IN_BLOCKED_FUNCTIONS,
                List.of(),     // no examples: the web page asks Claude to suggest some
                false);        // result rows never leave this machine unless the policy says so
    }

    public static final int MAX_EXAMPLES = 12;
    public static final int MAX_EXAMPLE_CHARS = 200;

    // ------------------------------------------------------------------ loading from YAML

    /** A problem in a policy file. The message says which key is wrong and why. */
    public static final class PolicyException extends Exception {
        public PolicyException(String message) {
            super(message);
        }
    }

    /** A policy plus a description of where it came from (shown to the user). */
    public record Loaded(Policy policy, String source) {}

    public static final String DEFAULT_FILE = "policy.yaml";

    private static final Set<String> TOP_KEYS = Set.of(
            "schema", "allowed_tables", "blocked_columns", "masked_columns", "blocked_functions", "limits",
            "examples", "share_results_with_ai");
    private static final Set<String> LIMIT_KEYS = Set.of(
            "max_rows", "timeout_ms", "max_joins", "max_selects", "max_question_chars", "max_sql_chars");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    /**
     * Finds the policy to use, in this order:
     * the {@code --policy} path if given, then the {@code QW_POLICY} environment variable,
     * then {@code policy.yaml} in the current directory, then the built-in {@link #defaults()}.
     * A path given explicitly (flag or variable) must exist.
     */
    public static Loaded resolve(String cliPath) throws PolicyException {
        if (cliPath != null && !cliPath.isBlank()) return new Loaded(load(Path.of(cliPath)), cliPath);
        String env = System.getenv("QW_POLICY");
        if (env != null && !env.isBlank()) return new Loaded(load(Path.of(env)), env + " (from QW_POLICY)");
        Path local = Path.of(DEFAULT_FILE);
        if (Files.isRegularFile(local)) return new Loaded(load(local), DEFAULT_FILE);
        return new Loaded(defaults(), "built-in defaults (no " + DEFAULT_FILE + " found)");
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
        JsonNode root;
        try {
            root = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse("");
            throw new PolicyException(origin + ": not valid YAML: " + msg);
        }
        Policy d = defaults();
        if (root == null || root.isMissingNode() || root.isNull()) return d;
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
        boolean share = d.shareResultsWithAi();
        if (root.has("share_results_with_ai") && !root.get("share_results_with_ai").isNull()) {
            JsonNode sh = root.get("share_results_with_ai");
            if (!sh.isBoolean()) throw new PolicyException(origin + ": share_results_with_ai must be true or false.");
            share = sh.asBoolean();
        }

        JsonNode limits = root.path("limits");
        if (!limits.isMissingNode() && !limits.isNull()) {
            if (!limits.isObject()) throw new PolicyException(origin + ": limits must be a set of key: value pairs.");
            checkKeys(limits, LIMIT_KEYS, origin, "limits.");
        }
        return new Policy(
                schema, Set.copyOf(allowed), Map.copyOf(blocked), Set.copyOf(masked),
                limit(limits, "max_rows", d.maxRows(), 1, 10_000, origin),
                limit(limits, "timeout_ms", d.timeoutMillis(), 100, 600_000, origin),
                limit(limits, "max_joins", d.maxJoins(), 0, 50, origin),
                limit(limits, "max_selects", d.maxSelects(), 1, 50, origin),
                limit(limits, "max_question_chars", d.maxQuestionChars(), 1, 100_000, origin),
                limit(limits, "max_sql_chars", d.maxSqlChars(), 100, 100_000, origin),
                Set.copyOf(functions),
                examples,
                share);
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
        sb.append("limits:\n");
        sb.append("  max_rows: ").append(maxRows).append('\n');
        sb.append("  timeout_ms: ").append(timeoutMillis).append('\n');
        sb.append("  max_joins: ").append(maxJoins).append('\n');
        sb.append("  max_selects: ").append(maxSelects).append('\n');
        sb.append("  max_question_chars: ").append(maxQuestionChars).append('\n');
        sb.append("  max_sql_chars: ").append(maxSqlChars).append('\n');
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
                maxJoins, maxSelects, maxQuestionChars, maxSqlChars, blockedFunctions, examples, shareResultsWithAi);
    }

    public Policy withTimeoutMillis(int ms) {
        return new Policy(schema, allowedTables, blockedColumns, maskedColumns, maxRows, ms,
                maxJoins, maxSelects, maxQuestionChars, maxSqlChars, blockedFunctions, examples, shareResultsWithAi);
    }

    static String norm(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }
}
