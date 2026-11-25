package dev.querywarden;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Writes a first policy for any PostgreSQL database, from its own tables and columns:
 * every table is allowed except ones whose names look like credentials or internals, columns whose
 * names look like secrets or very personal data are blocked, and contact details are masked.
 * It is a starting point to review, not a decision: the file says what it guessed and why.
 */
public final class PolicyInit {

    /** Tables that usually hold credentials, sessions or internals: left out. */
    static final Pattern SENSITIVE_TABLE = Pattern.compile(
            "(^|_)(api_?keys?|secrets?|tokens?|credentials?|passwords?|sessions?|auth\\w*|oauth\\w*|"
                    + "migrations?|schema_migrations|flyway\\w*|liquibase\\w*|databasechangelog\\w*)(_|$)");
    /** Columns never to read: secrets, government ids, pay, card and bank numbers, birth dates. */
    static final Pattern BLOCK_COLUMN = Pattern.compile(
            "(^|_)(password|passwd|pwd|pass_?hash|hash|salt|secret|token|api_?key|private_?key|otp|mfa\\w*|"
                    + "ssn|social_?security\\w*|tax_?id|tin|national_?id|passport\\w*|driver_?licen[cs]e\\w*|"
                    + "salary|salaries|wage|wages|pay_?rate|compensation|bonus|"
                    + "iban|bic|swift|routing\\w*|account_?number|acct_?no|card_?number|cc_?num\\w*|cvv|cvc|pan|"
                    + "dob|date_?of_?birth|birth_?date|birthday)(_|$)");
    /** Columns that may be used (counted, grouped) but are shown as *** in results. */
    static final Pattern MASK_COLUMN = Pattern.compile(
            "(^|_)(e_?mail\\w*|phone\\w*|mobile\\w*|fax|address\\w*|street\\w*|ip|ip_?address)(_|$)");

    public record Table(String name, List<String> columns) {}

    public record Draft(String schema, List<Table> allowed, List<String> leftOut, Map<String, List<String>> blocked,
                        Set<String> masked) {}

    private PolicyInit() {}

    /** The schema to use when none is given: the one with the most tables (not a system schema). */
    public static String guessSchema(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT table_schema, COUNT(*) FROM information_schema.tables
                WHERE table_schema NOT IN ('pg_catalog', 'information_schema') AND table_schema NOT LIKE 'pg\\_%'
                GROUP BY table_schema ORDER BY 2 DESC, 1 LIMIT 1""");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : "public";
        }
    }

    public static Draft draft(Connection c, String schema) throws SQLException {
        Map<String, List<String>> cols = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT c.table_name, c.column_name FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = ? AND t.table_type IN ('BASE TABLE', 'VIEW')
                ORDER BY c.table_name, c.ordinal_position""")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        return draft(schema, cols);
    }

    /** The classification, separated from the database so it can be tested. */
    static Draft draft(String schema, Map<String, List<String>> cols) {
        List<Table> allowed = new ArrayList<>();
        List<String> leftOut = new ArrayList<>();
        Map<String, List<String>> blocked = new LinkedHashMap<>();
        Set<String> masked = new TreeSet<>();
        cols.forEach((table, columns) -> {
            String t = table.toLowerCase(Locale.ROOT);
            if (!plain(t) || SENSITIVE_TABLE.matcher(t).find()) { leftOut.add(table); return; }
            allowed.add(new Table(t, columns));
            List<String> b = new ArrayList<>();
            for (String col : columns) {
                String n = col.toLowerCase(Locale.ROOT);
                if (!plain(n)) continue;
                if (BLOCK_COLUMN.matcher(n).find()) b.add(n);
                else if (MASK_COLUMN.matcher(n).find()) masked.add(n);
            }
            if (!b.isEmpty()) blocked.put(t, b);
        });
        return new Draft(schema, allowed, leftOut, blocked, masked);
    }

    /** Names the policy file can hold (letters, digits, underscores; no quoting needed). */
    private static boolean plain(String name) {
        return name.matches("[a-z_][a-z0-9_$]*");
    }

    public static String toYaml(Draft d, String databaseLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                # QueryWarden policy: what the agent may see and do.
                # Written by `querywarden init` from %s. It is a starting point: review every
                # section, then check it with `querywarden policy` and `querywarden doctor`.
                #
                # Names are case-insensitive. Unknown keys are an error, so typos can't silently switch a
                # protection off. This file controls QueryWarden's own checks and masking; give the database
                # user the same permissions too (`querywarden grants` prints the SQL).

                # The PostgreSQL schema the tables live in.
                schema: %s

                # Tables Claude may query. Everything else is invisible and blocked.
                """.formatted(databaseLabel, d.schema()));
        if (d.allowed().isEmpty()) sb.append("# (no tables found in this schema: add them here)\nallowed_tables: []\n");
        else {
            sb.append("allowed_tables:\n");
            for (Table t : d.allowed()) sb.append("  - ").append(t.name()).append('\n');
        }
        if (!d.leftOut().isEmpty()) {
            sb.append("# Left out because the name looks like credentials, sessions or internals (add back if needed):\n");
            for (String t : d.leftOut()) sb.append("#  - ").append(t).append('\n');
        }
        sb.append("""

                # Columns that must never be read: not shown to Claude, refused anywhere in a query, and
                # SELECT * is refused on these tables. Guessed from the names (secrets, ids, pay, card and
                # bank numbers, birth dates): add any others that are sensitive in your data.
                """);
        if (d.blocked().isEmpty()) sb.append("blocked_columns: {}\n");
        else {
            sb.append("blocked_columns:\n");
            d.blocked().forEach((t, c) -> sb.append("  ").append(t).append(": [").append(String.join(", ", c)).append("]\n"));
        }
        sb.append("""

                # Columns that may be used (counted, grouped, joined) but are shown as *** in results.
                # Values that look like emails, phone numbers, SSNs or secret keys are hidden as well.
                """);
        sb.append("masked_columns: [").append(String.join(", ", d.masked())).append("]\n");
        sb.append("""

                # Extra functions to block, on top of the built-in list of dangerous PostgreSQL functions.
                blocked_functions: []

                # Result rows are only sent to Claude (for one-line summaries) if this is true.
                share_results_with_ai: false

                # Let Claude see the values of short category columns (statuses, country codes), so it writes
                # WHERE country = 'DE' rather than 'Germany'. Names, emails and similar are never included.
                share_category_values: false

                # Let Claude ask "which do you mean?" when a question can be read several ways.
                ask_clarifying_questions: true

                # What your business terms mean, applied whenever a question uses them. For example:
                # definitions:
                #   active customer: A customer with an order in the last 90 days.

                # Measures as exact SQL (SELECT <sql> FROM <from> WHERE <where>). For example:
                # metrics:
                #   revenue:
                #     description: Money from orders that weren't cancelled.
                #     sql: SUM(oi.quantity * oi.unit_price)
                #     from: order_items oi JOIN orders o ON o.id = oi.order_id
                #     where: o.status <> 'cancelled'
                #     time_column: o.order_date

                # Example questions on the web page are written by Claude from the tables above.

                limits:
                  max_rows: 200
                  timeout_ms: 3000
                  max_joins: 4
                  max_selects: 6
                  max_tables_in_prompt: 30
                """);
        return sb.toString();
    }

    /** A short summary for the terminal. */
    public static String summary(Draft d) {
        int blockedCols = d.blocked().values().stream().mapToInt(List::size).sum();
        Set<String> notes = new LinkedHashSet<>();
        notes.add(d.allowed().size() + " table(s) allowed");
        if (!d.leftOut().isEmpty()) notes.add(d.leftOut().size() + " left out (" + String.join(", ", d.leftOut()) + ")");
        notes.add(blockedCols + " column(s) blocked");
        notes.add(d.masked().size() + " column name(s) masked");
        return String.join("; ", notes);
    }
}
