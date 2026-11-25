package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** `grants` produces a user that `doctor` approves; `doctor` catches each kind of over-permission. */
@Testcontainers
class DoctorTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shop").withUsername("postgres").withPassword("postgres");

    static final Policy POLICY = Policy.demo();
    static Database.Config cfg;

    @BeforeAll
    static void setup() throws Exception {
        cfg = new Database.Config(PG.getJdbcUrl(), "postgres", "postgres", "qw_agent", "pw");
        Database.setup(cfg, POLICY);
    }

    static List<Doctor.Finding> fails(Database.Config c) {
        return Doctor.check(c, POLICY).stream().filter(f -> f.level() == Doctor.Level.FAIL).toList();
    }

    static void admin(String sql) throws Exception {
        try (Connection c = cfg.admin(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void demoSetupPasses() {
        assertEquals(List.of(), fails(cfg));
    }

    @Test
    void catchesEachKindOfOverPermission() throws Exception {
        String[][] cases = {
                {"GRANT SELECT (salary) ON shop.employees TO qw_agent", "REVOKE SELECT (salary) ON shop.employees FROM qw_agent", "blocked column"},
                {"GRANT INSERT ON shop.orders TO qw_agent", "REVOKE INSERT ON shop.orders FROM qw_agent", "Can write"},
                {"GRANT UPDATE (status) ON shop.orders TO qw_agent", "REVOKE UPDATE (status) ON shop.orders FROM qw_agent", "Can write"},
                {"GRANT SELECT ON shop.api_keys TO PUBLIC", "REVOKE SELECT ON shop.api_keys FROM PUBLIC", "not in allowed_tables"},
                {"GRANT CREATE ON SCHEMA shop TO qw_agent", "REVOKE CREATE ON SCHEMA shop FROM qw_agent", "create objects"},
                {"CREATE ROLE helper_role; GRANT helper_role TO qw_agent", "REVOKE helper_role FROM qw_agent; DROP ROLE helper_role", "member of"},
                {"ALTER ROLE qw_agent CREATEDB", "ALTER ROLE qw_agent NOCREATEDB", "CREATEDB"},
        };
        for (String[] c : cases) {
            admin(c[0]);
            try {
                var f = fails(cfg);
                assertFalse(f.isEmpty(), "doctor missed: " + c[0]);
                assertTrue(f.stream().anyMatch(x -> x.message().contains(c[2])), c[0] + " -> " + f);
            } finally {
                admin(c[1]);
            }
        }
        assertEquals(List.of(), fails(cfg), "everything restored");
    }

    @Test
    void wrongPasswordIsAClearFailure() {
        var bad = new Database.Config(cfg.url(), "postgres", "postgres", "qw_agent", "wrong");
        var f = fails(bad);
        assertEquals(1, f.size());
        assertTrue(f.get(0).message().contains("Couldn't log in"));
    }

    @Test
    void generatedGrantsPassTheDoctor() throws Exception {
        try (Connection admin = cfg.admin(); Statement st = admin.createStatement()) {
            Grants.Plan plan = Grants.plan(admin, POLICY, "qw_second", "second_pw", false);
            for (String sql : plan.roleStatements()) st.execute(sql);
            for (String sql : plan.grantStatements()) {
                int comment = sql.indexOf("   -- ");
                st.execute(comment < 0 ? sql : sql.substring(0, comment));
            }
            String script = Grants.script(plan, POLICY, "qw_second", admin.getCatalog());
            assertTrue(script.contains("-- not granted: salary, ssn"));
            assertFalse(script.contains("api_keys"), "tables outside the policy are never mentioned");
        }
        var second = new Database.Config(cfg.url(), "postgres", "postgres", "qw_second", "second_pw");
        assertEquals(List.of(), fails(second));
    }

    @Test
    void grantsWarnAboutPolicyTypos() throws Exception {
        Policy typo = Policy.parse(Policy.demo(), """
                allowed_tables: [customers, invoices]
                blocked_columns: {customers: [emial]}
                masked_columns: [phnoe]
                """, "test");
        try (Connection admin = cfg.admin()) {
            var w = Grants.plan(admin, typo, "x", "y", false).warnings();
            assertTrue(w.stream().anyMatch(s -> s.contains("'invoices'")), w.toString());
            assertTrue(w.stream().anyMatch(s -> s.contains("customers.emial")), w.toString());
            assertTrue(w.stream().anyMatch(s -> s.contains("'phnoe'")), w.toString());
        }
    }
}
