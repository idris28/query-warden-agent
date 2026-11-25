package dev.querywarden;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * PostgreSQL setup and schema introspection.
 *
 * <p>Two roles are involved:
 * <ul>
 *   <li>an <b>admin</b> role (used only by {@code setup}) that owns the schema and data;</li>
 *   <li>the <b>agent</b> role that runs every generated query. It can only log in, read the
 *       allowed tables, and read the allowed columns: GRANTs are generated from the {@link Policy}.
 *       It is read-only by default and has a statement timeout set on the role itself.</li>
 * </ul>
 */
public final class Database {

    /** Connection settings, read from environment variables with local-dev defaults. */
    public record Config(String url, String adminUser, String adminPassword, String agentUser, String agentPassword) {
        public static Config fromEnv() {
            return new Config(
                    env("QW_DB_URL", "jdbc:postgresql://localhost:5432/shop"),
                    env("QW_ADMIN_USER", "postgres"),
                    env("QW_ADMIN_PASSWORD", "postgres"),
                    env("QW_AGENT_USER", "qw_agent"),
                    env("QW_AGENT_PASSWORD", "qw_agent_dev"));
        }

        public Connection admin() throws SQLException {
            return DriverManager.getConnection(url, adminUser, adminPassword);
        }

        public Connection agent() throws SQLException {
            return DriverManager.getConnection(url, agentUser, agentPassword);
        }

        private static String env(String key, String def) {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? def : v;
        }
    }

    private static final String DDL = """
            CREATE TABLE %1$s.customers (
                id          integer PRIMARY KEY,
                name        text NOT NULL,
                email       text NOT NULL,
                phone       text,
                country     text NOT NULL,
                signup_date date NOT NULL
            );
            CREATE TABLE %1$s.products (
                id        integer PRIMARY KEY,
                name      text NOT NULL,
                category  text NOT NULL,
                price     numeric(10,2) NOT NULL,
                stock     integer NOT NULL
            );
            CREATE TABLE %1$s.orders (
                id          integer PRIMARY KEY,
                customer_id integer NOT NULL REFERENCES %1$s.customers(id),
                order_date  date NOT NULL,
                status      text NOT NULL CHECK (status IN ('pending','shipped','delivered','cancelled'))
            );
            CREATE TABLE %1$s.order_items (
                id         integer PRIMARY KEY,
                order_id   integer NOT NULL REFERENCES %1$s.orders(id),
                product_id integer NOT NULL REFERENCES %1$s.products(id),
                quantity   integer NOT NULL,
                unit_price numeric(10,2) NOT NULL
            );
            CREATE TABLE %1$s.employees (
                id         integer PRIMARY KEY,
                name       text NOT NULL,
                department text NOT NULL,
                hire_date  date NOT NULL,
                salary     numeric(10,2) NOT NULL,
                ssn        text NOT NULL
            );
            CREATE TABLE %1$s.api_keys (
                id      integer PRIMARY KEY,
                owner   text NOT NULL,
                secret  text NOT NULL
            );
            COMMENT ON COLUMN %1$s.orders.status IS 'one of pending, shipped, delivered, cancelled';
            COMMENT ON COLUMN %1$s.order_items.unit_price IS 'price paid per unit at order time; revenue = quantity * unit_price';
            """;

    private Database() {}

    /** Drops and recreates the schema, seeds deterministic sample data, and (re)creates the agent role. */
    /** Label put on the demo schema and role, so setup can recognise its own objects. */
    static final String DEMO_MARKER = "querywarden-demo";

    /** Thrown when setup would touch a database that doesn't look like a QueryWarden demo database. */
    public static final class SetupRefusedException extends Exception {
        public SetupRefusedException(String message) {
            super(message);
        }
    }

    /**
     * Refuses unless the database looks like a dedicated demo database, so setup can never wipe
     * real data. All of these must hold:
     * <ul>
     *   <li>the target schema doesn't exist, or was created by setup (carries {@link #DEMO_MARKER});</li>
     *   <li>no other non-system schema in the database contains tables, views or sequences;</li>
     *   <li>the agent role doesn't exist, or was created by setup (carries the marker);</li>
     *   <li>the agent role isn't the same user as the admin.</li>
     * </ul>
     */
    static void checkDemoDatabase(Connection c, Config cfg, Policy policy) throws SQLException, SetupRefusedException {
        List<String> problems = new ArrayList<>();
        if (cfg.agentUser().equalsIgnoreCase(cfg.adminUser())) {
            problems.add("QW_AGENT_USER and QW_ADMIN_USER are the same user ('" + cfg.agentUser() + "').");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT obj_description(oid, 'pg_namespace') FROM pg_namespace WHERE nspname = ?")) {
            ps.setString(1, policy.schema());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && !DEMO_MARKER.equals(rs.getString(1))) {
                    problems.add("Schema '" + policy.schema() + "' already exists and was not created by setup.");
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT n.nspname, count(*) FROM pg_class cl
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE cl.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
                  AND n.nspname NOT LIKE 'pg_temp_%' AND n.nspname NOT LIKE 'pg_toast_temp_%'
                  AND n.nspname <> ?
                GROUP BY n.nspname ORDER BY 1""")) {
            ps.setString(1, policy.schema());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    problems.add("Schema '" + rs.getString(1) + "' contains " + rs.getLong(2)
                            + " table(s)/view(s); this looks like a database with real data.");
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT shobj_description(oid, 'pg_authid') FROM pg_roles WHERE rolname = ?")) {
            ps.setString(1, cfg.agentUser());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && !DEMO_MARKER.equals(rs.getString(1))) {
                    problems.add("Role '" + cfg.agentUser() + "' already exists and was not created by setup.");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new SetupRefusedException("Refusing to run setup: this doesn't look like a QueryWarden demo database.\n  - "
                    + String.join("\n  - ", problems)
                    + "\nsetup drops and recreates schema '" + policy.schema() + "', so it only runs on an empty "
                    + "database or one it set up before.\nUse a fresh database for the demo (e.g. `docker compose up`). "
                    + "For a real database, skip setup and follow \"Using your real database\" in README.md.");
        }
    }

    /** Drops and recreates the demo schema, seeds sample data, and (re)creates the agent role. */
    public static void setup(Config cfg, Policy policy) throws SQLException, SetupRefusedException {
        String s = ident(policy.schema());
        String agent = ident(cfg.agentUser());
        try (Connection c = cfg.admin(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            // Lock out concurrent setups, then check inside the same transaction that makes the changes.
            st.execute("SELECT pg_advisory_xact_lock(hashtext('querywarden-setup'))");
            checkDemoDatabase(c, cfg, policy);
            st.execute("DROP SCHEMA IF EXISTS " + s + " CASCADE");
            st.execute("CREATE SCHEMA " + s);
            st.execute("COMMENT ON SCHEMA " + s + " IS '" + DEMO_MARKER + "'");
            st.execute(DDL.formatted(s));
            seed(c, policy.schema());

            // --- least-privilege agent role, derived from the policy ---------------------------
            boolean exists;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
                ps.setString(1, cfg.agentUser());
                try (ResultSet rs = ps.executeQuery()) { exists = rs.next(); }
            }
            String pw = "'" + cfg.agentPassword().replace("'", "''") + "'";
            st.execute((exists ? "ALTER ROLE " : "CREATE ROLE ") + agent
                    + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD " + pw);
            st.execute("COMMENT ON ROLE " + agent + " IS '" + DEMO_MARKER + "'");
            st.execute("ALTER ROLE " + agent + " SET default_transaction_read_only = on");
            st.execute("ALTER ROLE " + agent + " SET statement_timeout = '" + policy.timeoutMillis() + "ms'");
            st.execute("ALTER ROLE " + agent + " SET idle_in_transaction_session_timeout = '10s'");
            st.execute("ALTER ROLE " + agent + " SET search_path = " + s);
            st.execute("REVOKE ALL ON SCHEMA public FROM " + agent);
            st.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            st.execute("REVOKE TEMPORARY ON DATABASE " + ident(c.getCatalog()) + " FROM PUBLIC");
            st.execute("GRANT USAGE ON SCHEMA " + s + " TO " + agent);
            for (Map.Entry<String, List<String>> t : columns(c, policy.schema()).entrySet()) {
                if (!policy.tableAllowed(t.getKey())) continue; // e.g. api_keys: no grant at all
                List<String> cols = t.getValue().stream()
                        .filter(col -> policy.columnAllowed(t.getKey(), col)).map(Database::ident).toList();
                st.execute("GRANT SELECT (" + String.join(", ", cols) + ") ON " + s + "." + ident(t.getKey())
                        + " TO " + agent);
            }
            c.commit();
        }
    }

    /**
     * Schema text for the prompt, read through the agent's own connection: information_schema only
     * lists columns the role has privileges on, and the policy filter is applied on top.
     */
    public static String describeSchema(Config cfg, Policy policy) throws SQLException {
        try (Connection c = cfg.agent()) {
            c.setReadOnly(true);
            StringBuilder out = new StringBuilder();
            String sql = """
                    SELECT c.table_name, c.column_name, c.data_type,
                           col_description(format('%I.%I', c.table_schema, c.table_name)::regclass, c.ordinal_position)
                    FROM information_schema.columns c
                    WHERE c.table_schema = ?
                    ORDER BY c.table_name, c.ordinal_position""";
            Map<String, List<String>> tables = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, policy.schema());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String t = rs.getString(1), col = rs.getString(2);
                        if (!policy.tableAllowed(t) || !policy.columnAllowed(t, col)) continue;
                        String note = rs.getString(4);
                        tables.computeIfAbsent(t, k -> new ArrayList<>())
                                .add("    " + col + " " + rs.getString(3) + (note != null ? "  -- " + note : ""));
                    }
                }
            }
            // information_schema hides constraints of tables the role doesn't own, so read pg_catalog.
            String fkSql = """
                    SELECT cl.relname, a.attname, rcl.relname, ra.attname
                    FROM pg_constraint con
                    JOIN pg_class cl ON cl.oid = con.conrelid
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                    JOIN pg_class rcl ON rcl.oid = con.confrelid
                    JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
                    JOIN pg_attribute ra ON ra.attrelid = con.confrelid AND ra.attnum = con.confkey[1]
                    WHERE con.contype = 'f' AND n.nspname = ?
                    ORDER BY 1, 2""";
            try (PreparedStatement ps = c.prepareStatement(fkSql)) {
                ps.setString(1, policy.schema());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (tables.containsKey(rs.getString(1)) && tables.containsKey(rs.getString(3))) {
                            tables.get(rs.getString(1)).add("    FK " + rs.getString(2) + " -> "
                                    + rs.getString(3) + "." + rs.getString(4));
                        }
                    }
                }
            }
            tables.forEach((t, cols) -> out.append("TABLE ").append(t).append(" (\n")
                    .append(String.join("\n", cols)).append("\n)\n"));
            return out.toString().strip();
        }
    }

    private static Map<String, List<String>> columns(Connection c, String schema) throws SQLException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT table_name, column_name FROM information_schema.columns
                WHERE table_schema = ? ORDER BY table_name, ordinal_position""")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        return out;
    }

    private static void seed(Connection c, String schema) throws SQLException {
        Random rng = new Random(7);
        String s = ident(schema);
        String[] countries = {"US", "UK", "DE", "FR", "IN", "BR", "JP", "CA"};
        String[] first = {"Ava", "Liam", "Noah", "Mia", "Zoe", "Omar", "Priya", "Kenji", "Lucas", "Sara"};
        String[] last = {"Smith", "Khan", "Garcia", "Muller", "Sato", "Silva", "Brown", "Dubois"};
        LocalDate start = LocalDate.of(2024, 1, 1);

        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + s + ".customers VALUES (?,?,?,?,?,?)")) {
            for (int i = 1; i <= 120; i++) {
                String n = pick(rng, first) + " " + pick(rng, last);
                ps.setInt(1, i);
                ps.setString(2, n);
                ps.setString(3, n.toLowerCase().replace(' ', '.') + i + "@example.com");
                ps.setString(4, "+1-555-" + (1000 + rng.nextInt(9000)));
                ps.setString(5, pick(rng, countries));
                ps.setObject(6, start.plusDays(rng.nextInt(600)));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        Map<String, String[]> catalog = new LinkedHashMap<>();
        catalog.put("Electronics", new String[]{"Laptop", "Headphones", "Monitor", "Keyboard", "Webcam"});
        catalog.put("Books", new String[]{"SQL Primer", "Data Design", "Java Deep Dive", "Stats 101"});
        catalog.put("Home", new String[]{"Desk Lamp", "Office Chair", "Coffee Maker", "Plant Pot"});
        catalog.put("Sports", new String[]{"Yoga Mat", "Dumbbells", "Running Shoes"});
        List<double[]> prices = new ArrayList<>(); // [id, price]
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + s + ".products VALUES (?,?,?,?,?)")) {
            int id = 1;
            for (var e : catalog.entrySet()) {
                for (String n : e.getValue()) {
                    double max = e.getKey().equals("Electronics") ? 1500 : 200;
                    double price = Math.round((8 + rng.nextDouble() * (max - 8)) * 100) / 100.0;
                    ps.setInt(1, id);
                    ps.setString(2, n);
                    ps.setString(3, e.getKey());
                    ps.setBigDecimal(4, java.math.BigDecimal.valueOf(price));
                    ps.setInt(5, rng.nextInt(301));
                    ps.addBatch();
                    prices.add(new double[]{id, price});
                    id++;
                }
            }
            ps.executeBatch();
        }

        String[] statuses = {"pending", "shipped", "delivered", "delivered", "delivered", "cancelled"};
        try (PreparedStatement po = c.prepareStatement("INSERT INTO " + s + ".orders VALUES (?,?,?,?)");
             PreparedStatement pi = c.prepareStatement("INSERT INTO " + s + ".order_items VALUES (?,?,?,?,?)")) {
            int itemId = 1;
            for (int oid = 1; oid <= 600; oid++) {
                po.setInt(1, oid);
                po.setInt(2, 1 + rng.nextInt(120));
                po.setObject(3, start.plusDays(30 + rng.nextInt(660)));
                po.setString(4, pick(rng, statuses));
                po.addBatch();
                for (int k = 0, n = 1 + rng.nextInt(4); k < n; k++) {
                    double[] p = prices.get(rng.nextInt(prices.size()));
                    pi.setInt(1, itemId++);
                    pi.setInt(2, oid);
                    pi.setInt(3, (int) p[0]);
                    pi.setInt(4, 1 + rng.nextInt(3));
                    pi.setBigDecimal(5, java.math.BigDecimal.valueOf(p[1]));
                    pi.addBatch();
                }
            }
            po.executeBatch();
            pi.executeBatch();
        }

        String[] depts = {"Engineering", "Sales", "Support", "Marketing"};
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + s + ".employees VALUES (?,?,?,?,?,?)")) {
            for (int i = 1; i <= 30; i++) {
                ps.setInt(1, i);
                ps.setString(2, pick(rng, first) + " " + pick(rng, last));
                ps.setString(3, pick(rng, depts));
                ps.setObject(4, start.minusDays(rng.nextInt(2000)));
                ps.setBigDecimal(5, java.math.BigDecimal.valueOf((50 + rng.nextInt(151)) * 1000L));
                ps.setString(6, (100 + rng.nextInt(900)) + "-" + (10 + rng.nextInt(90)) + "-" + (1000 + rng.nextInt(9000)));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + s + ".api_keys VALUES (1, 'billing-service', 'sk_live_DO_NOT_LEAK_1'),"
                    + " (2, 'etl', 'sk_live_DO_NOT_LEAK_2')");
        }
    }

    private static String pick(Random rng, String[] a) {
        return a[rng.nextInt(a.length)];
    }

    /** Quote an SQL identifier. */
    static String ident(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
