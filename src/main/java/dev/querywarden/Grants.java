package dev.querywarden;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SQL that creates QueryWarden's read-only database user, derived from the policy and the
 * database's real tables. Used by {@code querywarden grants} (printed for you to review and run)
 * and by the demo {@code setup}.
 *
 * <p>Every table is granted column by column, never as a whole: a sensitive column added to a table
 * later stays unreadable until someone grants it on purpose.
 */
public final class Grants {

    private Grants() {}

    /** What the generated SQL covers, plus warnings about policy entries that don't match the database. */
    public record Plan(List<String> roleStatements, List<String> grantStatements, List<String> warnings) {}

    public static Plan plan(Connection admin, Policy policy, String agentUser, String password, boolean roleExists)
            throws SQLException {
        String s = Database.ident(policy.schema());
        String agent = Database.ident(agentUser);
        List<String> role = new ArrayList<>();
        String pw = "'" + password.replace("'", "''") + "'";
        role.add((roleExists ? "ALTER ROLE " : "CREATE ROLE ") + agent
                + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD " + pw);
        role.add("ALTER ROLE " + agent + " SET default_transaction_read_only = on");
        role.add("ALTER ROLE " + agent + " SET statement_timeout = '" + policy.timeoutMillis() + "ms'");
        role.add("ALTER ROLE " + agent + " SET idle_in_transaction_session_timeout = '10s'");
        role.add("ALTER ROLE " + agent + " SET search_path = " + s);

        List<String> grants = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        grants.add("GRANT CONNECT ON DATABASE " + Database.ident(admin.getCatalog()) + " TO " + agent);
        grants.add("GRANT USAGE ON SCHEMA " + s + " TO " + agent);
        Map<String, List<String>> columns = columns(admin, policy.schema());
        for (String table : policy.allowedTables().stream().sorted().toList()) {
            List<String> cols = columns.get(table);
            if (cols == null) {
                warnings.add("Table '" + table + "' is in allowed_tables but doesn't exist in schema '"
                        + policy.schema() + "'.");
                continue;
            }
            Set<String> blocked = policy.blockedColumns().getOrDefault(table, Set.of());
            for (String b : blocked) {
                if (!cols.contains(b))
                    warnings.add("Blocked column '" + table + "." + b + "' doesn't exist (typo?). It is still never granted.");
            }
            List<String> granted = cols.stream().filter(c -> policy.columnAllowed(table, c)).map(Database::ident).toList();
            if (granted.isEmpty()) {
                warnings.add("Every column of '" + table + "' is blocked, so nothing is granted on it.");
                continue;
            }
            grants.add("GRANT SELECT (" + String.join(", ", granted) + ") ON " + s + "." + Database.ident(table)
                    + " TO " + agent + (blocked.isEmpty() ? "" : "   -- not granted: " + String.join(", ", blocked.stream().sorted().toList())));
        }
        for (String masked : policy.maskedColumns()) {
            if (columns.values().stream().noneMatch(c -> c.contains(masked)))
                warnings.add("Masked column '" + masked + "' doesn't exist in any table (typo?).");
        }
        return new Plan(role, grants, warnings);
    }

    /** The full script, ready to review and run with psql. */
    public static String script(Plan plan, Policy policy, String agentUser, String database) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- QueryWarden: read-only database user '").append(agentUser).append("' for schema '")
                .append(policy.schema()).append("'\n")
                .append("-- Generated from policy.yaml. Review it, replace the password, then run it as an administrator:\n")
                .append("--   psql -d ").append(database).append(" -f grants.sql\n")
                .append("-- Afterwards check the result with:  querywarden doctor\n\n");
        for (String w : plan.warnings()) sb.append("-- WARNING: ").append(w).append('\n');
        if (!plan.warnings().isEmpty()) sb.append('\n');
        sb.append("BEGIN;\n\n-- The user: can log in, nothing else; read-only and time-limited by default\n");
        for (String st : plan.roleStatements()) sb.append(st).append(";\n");
        sb.append("\n-- What it may read (column by column; blocked columns are left out)\n");
        for (String st : plan.grantStatements()) {
            int comment = st.indexOf("   -- ");
            sb.append(comment < 0 ? st + ";" : st.substring(0, comment) + ";" + st.substring(comment)).append('\n');
        }
        sb.append("\nCOMMIT;\n\n")
                .append("-- Recommended, but they affect every user of the database, so review before running:\n")
                .append("-- REVOKE CREATE ON SCHEMA public FROM PUBLIC;\n")
                .append("-- REVOKE TEMPORARY ON DATABASE ").append(Database.ident(database)).append(" FROM PUBLIC;\n")
                .append("-- Also make sure PUBLIC can't read sensitive tables: \\dp ").append(policy.schema()).append(".*\n");
        return sb.toString();
    }

    static Map<String, List<String>> columns(Connection c, String schema) throws SQLException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.attrelid::regclass::text, cl.relname, a.attname
                FROM pg_attribute a JOIN pg_class cl ON cl.oid = a.attrelid JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE n.nspname = ? AND cl.relkind IN ('r', 'p', 'v', 'm', 'f') AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY cl.relname, a.attnum""")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getString(3));
            }
        }
        return out;
    }
}
