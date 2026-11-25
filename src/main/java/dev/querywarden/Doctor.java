package dev.querywarden;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks what QueryWarden's database user can really do, by logging in as that user and asking
 * PostgreSQL (the has_*_privilege functions include permissions inherited from roles and PUBLIC).
 * The policy only governs QueryWarden's own checks; these permissions are the protection that holds
 * even if those checks have a gap, so they must match.
 *
 * <ul>
 *   <li>FAIL: the user can do something the policy forbids (read a blocked column or a table outside
 *       the policy, write anything, create objects, has dangerous role attributes or memberships)</li>
 *   <li>WARN: weaker defaults or policy entries that don't match the database</li>
 *   <li>PASS: what was checked and found fine</li>
 * </ul>
 */
public final class Doctor {

    public enum Level { PASS, WARN, FAIL }

    public record Finding(Level level, String message) {
        @Override public String toString() { return level + "  " + message; }
    }

    // Padded with spaces on purpose: Java text blocks drop trailing spaces before a closing """.
    private static final String NON_SYSTEM = " n.nspname NOT IN ('pg_catalog', 'information_schema') "
            + "AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp%' ";

    private Doctor() {}

    public static List<Finding> check(Database.Config cfg, Policy policy) {
        List<Finding> out = new ArrayList<>();
        Connection c;
        try {
            c = cfg.agent();
        } catch (SQLException e) {
            out.add(new Finding(Level.FAIL, "Couldn't log in as '" + cfg.agentUser() + "': " + firstLine(e.getMessage())
                    + ". Check QW_DB_URL, QW_AGENT_USER and QW_AGENT_PASSWORD."));
            return out;
        }
        try (c; Statement st = c.createStatement()) {
            out.add(pass("Logged in as '" + cfg.agentUser() + "'."));
            roleAttributes(st, out);
            memberships(st, out);
            sessionDefaults(st, out);
            writes(st, out);
            creates(st, out);
            reads(c, policy, out);
            policyMatchesDatabase(c, policy, out);
        } catch (SQLException e) {
            out.add(new Finding(Level.FAIL, "A check couldn't run, so the result is incomplete: " + firstLine(e.getMessage())));
        }
        return out;
    }

    static void roleAttributes(Statement st, List<Finding> out) throws SQLException {
        try (ResultSet rs = st.executeQuery("""
                SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
                FROM pg_roles WHERE rolname = current_user""")) {
            rs.next();
            String[] names = {"SUPERUSER", "CREATEROLE", "CREATEDB", "REPLICATION", "BYPASSRLS"};
            List<String> bad = new ArrayList<>();
            for (int i = 0; i < names.length; i++) if (rs.getBoolean(i + 1)) bad.add(names[i]);
            out.add(bad.isEmpty() ? pass("No dangerous role attributes (superuser, create role/database, replication, bypass RLS).")
                    : fail("The user has " + String.join(", ", bad) + ". Remove with ALTER ROLE … NO" + bad.get(0) + "."));
        }
    }

    static void memberships(Statement st, List<Finding> out) throws SQLException {
        List<String> roles = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("""
                SELECT r.rolname FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
                WHERE m.member = (SELECT oid FROM pg_roles WHERE rolname = current_user) ORDER BY 1""")) {
            while (rs.next()) roles.add(rs.getString(1));
        }
        out.add(roles.isEmpty() ? pass("Not a member of any other role.")
                : fail("The user is a member of " + String.join(", ", roles)
                        + ". It could switch to those roles (SET ROLE) and use their permissions. Revoke the membership."));
    }

    static void sessionDefaults(Statement st, List<Finding> out) throws SQLException {
        String ro = show(st, "default_transaction_read_only");
        out.add("on".equals(ro) ? pass("Read-only by default.")
                : warn("default_transaction_read_only is '" + ro + "'. QueryWarden still runs read-only, but set "
                        + "ALTER ROLE … SET default_transaction_read_only = on as a second barrier."));
        String timeout = show(st, "statement_timeout");
        out.add(!"0".equals(timeout) ? pass("Statement timeout is " + timeout + ".")
                : warn("statement_timeout is 0 (no limit). QueryWarden sets its own, but a role default is a good backstop."));
    }

    static void writes(Statement st, List<Finding> out) throws SQLException {
        List<String> found = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("""
                SELECT n.nspname || '.' || c.relname,
                       array_to_string(ARRAY(SELECT p FROM unnest(ARRAY['INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER']) p
                                             WHERE has_table_privilege(c.oid, p)), ', ')
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r','p','v','m','f') AND """ + NON_SYSTEM + """
                  AND (has_table_privilege(c.oid, 'INSERT') OR has_table_privilege(c.oid, 'UPDATE')
                    OR has_table_privilege(c.oid, 'DELETE') OR has_table_privilege(c.oid, 'TRUNCATE')
                    OR has_table_privilege(c.oid, 'REFERENCES') OR has_table_privilege(c.oid, 'TRIGGER')
                    OR EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attnum > 0
                               AND (has_column_privilege(c.oid, a.attnum, 'INSERT') OR has_column_privilege(c.oid, a.attnum, 'UPDATE'))))
                ORDER BY 1""")) {
            while (rs.next()) found.add(rs.getString(1) + (rs.getString(2).isEmpty() ? " (column-level writes)" : " (" + rs.getString(2) + ")"));
        }
        try (ResultSet rs = st.executeQuery("SELECT n.nspname || '.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relkind = 'S' AND " + NON_SYSTEM
                // CASE: PostgreSQL may evaluate WHERE conditions in any order, and this function errors on non-sequences
                + " AND CASE WHEN c.relkind = 'S' THEN has_sequence_privilege(c.oid, 'UPDATE') ELSE false END ORDER BY 1")) {
            while (rs.next()) found.add(rs.getString(1) + " (sequence UPDATE)");
        }
        out.add(found.isEmpty() ? pass("Can't write to any table or sequence.")
                : fail("Can write to " + found.size() + " object(s): " + String.join("; ", found) + ". Revoke these."));
    }

    static void creates(Statement st, List<Finding> out) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("SELECT n.nspname FROM pg_namespace n WHERE " + NON_SYSTEM
                + " AND has_schema_privilege(n.oid, 'CREATE') ORDER BY 1")) {
            while (rs.next()) schemas.add(rs.getString(1));
        }
        out.add(schemas.isEmpty() ? pass("Can't create objects in any schema.")
                : fail("Can create objects in schema(s) " + String.join(", ", schemas)
                        + ". Run REVOKE CREATE ON SCHEMA … FROM PUBLIC (and from the user)."));
        try (ResultSet rs = st.executeQuery("SELECT has_database_privilege(current_database(), 'CREATE'), "
                + "has_database_privilege(current_database(), 'TEMP')")) {
            rs.next();
            if (rs.getBoolean(1)) out.add(fail("Can create schemas in this database. Revoke CREATE on the database."));
            out.add(rs.getBoolean(2) ? warn("Can create temporary tables. Harmless for reading, but "
                    + "REVOKE TEMPORARY ON DATABASE … FROM PUBLIC is tighter.") : pass("Can't create temporary tables."));
        }
    }

    static void reads(Connection c, Policy policy, List<Finding> out) throws SQLException {
        // Anything readable outside the policy: other tables, other schemas, blocked columns.
        List<String> outside = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        Set<String> readableAllowed = new TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT n.nspname, c.relname, a.attname
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid
                WHERE c.relkind IN ('r','p','v','m','f') AND a.attnum > 0 AND NOT a.attisdropped AND """ + NON_SYSTEM + """
                  AND has_schema_privilege(n.oid, 'USAGE') AND has_column_privilege(c.oid, a.attnum, 'SELECT')
                ORDER BY 1, 2, 3""")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String schema = rs.getString(1), table = rs.getString(2), col = rs.getString(3);
                    String name = schema + "." + table;
                    if (!schema.equals(policy.schema()) || !policy.tableAllowed(table)) {
                        if (!outside.contains(name)) outside.add(name);
                    } else if (!policy.columnAllowed(table, col)) {
                        blocked.add(name + "." + col);
                    } else {
                        readableAllowed.add(table);
                    }
                }
            }
        }
        out.add(outside.isEmpty() ? pass("Can't read any table outside allowed_tables.")
                : fail("Can read " + outside.size() + " table(s) not in allowed_tables: " + String.join(", ", outside)
                        + ". Revoke SELECT (check grants to PUBLIC too)."));
        out.add(blocked.isEmpty() ? pass("Can't read any blocked column.")
                : fail("Can read blocked column(s): " + String.join(", ", blocked) + ". Revoke SELECT on them, "
                        + "or on the whole table and grant the allowed columns one by one (querywarden grants)."));
        for (String t : policy.allowedTables().stream().sorted().toList()) {
            if (!readableAllowed.contains(t))
                out.add(warn("Allowed table '" + t + "' isn't readable by the user, so Claude can't use it."));
        }
        if (!readableAllowed.isEmpty())
            out.add(pass("Can read the allowed tables: " + String.join(", ", readableAllowed) + "."));
    }

    static void policyMatchesDatabase(Connection c, Policy policy, List<Finding> out) throws SQLException {
        // The agent can only see columns it was granted, so typos are checked against what it can see,
        // plus pg_attribute (column names are visible to every user even without SELECT).
        var cols = Grants.columns(c, policy.schema());
        for (var e : policy.blockedColumns().entrySet()) {
            for (String col : e.getValue()) {
                List<String> tableCols = cols.get(e.getKey());
                if (tableCols != null && !tableCols.contains(col))
                    out.add(warn("Blocked column '" + e.getKey() + "." + col + "' doesn't exist (typo in policy.yaml?)."));
            }
        }
        for (String t : policy.allowedTables())
            if (!cols.containsKey(t)) out.add(warn("Allowed table '" + t + "' doesn't exist in schema '" + policy.schema() + "'."));
    }

    // ------------------------------------------------------------------ helpers
    static String show(Statement st, String setting) throws SQLException {
        try (ResultSet rs = st.executeQuery("SHOW " + setting)) {
            rs.next();
            return rs.getString(1);
        }
    }

    static Finding pass(String m) { return new Finding(Level.PASS, m); }
    static Finding warn(String m) { return new Finding(Level.WARN, m); }
    static Finding fail(String m) { return new Finding(Level.FAIL, m); }

    static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
