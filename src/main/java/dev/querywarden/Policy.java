package dev.querywarden;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for every guardrail layer: the prompt (what schema Claude sees),
 * the static validator, the database GRANTs created by {@link Database#setup}, and output masking.
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
        Set<String> blockedFunctions) {

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
                Set.of(
                        // sleeping / resource abuse
                        "pg_sleep", "pg_sleep_for", "pg_sleep_until",
                        // file system and server access
                        "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
                        "lo_import", "lo_export", "lo_get", "lo_open",
                        // session / server manipulation
                        "set_config", "current_setting", "pg_terminate_backend", "pg_cancel_backend",
                        "pg_reload_conf", "pg_advisory_lock", "pg_advisory_xact_lock",
                        // remote access
                        "dblink", "dblink_exec", "query_to_xml", "query_to_json",
                        "table_to_xml", "cursor_to_xml", "xmlparse"));
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
                maxJoins, maxSelects, maxQuestionChars, maxSqlChars, blockedFunctions);
    }

    public Policy withTimeoutMillis(int ms) {
        return new Policy(schema, allowedTables, blockedColumns, maskedColumns, maxRows, ms,
                maxJoins, maxSelects, maxQuestionChars, maxSqlChars, blockedFunctions);
    }

    static String norm(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }
}
