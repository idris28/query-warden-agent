package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** `setup` must refuse to touch anything that doesn't look like a QueryWarden demo database. */
@Testcontainers
class SetupSafetyTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("realdb").withUsername("postgres").withPassword("postgres");

    static final Policy POLICY = Policy.defaults();
    Database.Config cfg;

    @BeforeEach
    void reset() throws Exception {
        cfg = new Database.Config(PG.getJdbcUrl(), "postgres", "postgres", "qw_agent", "pw");
        cleanup();
    }

    @AfterEach
    void cleanup() throws Exception {
        admin("""
                DROP SCHEMA IF EXISTS shop CASCADE;
                DROP SCHEMA IF EXISTS billing CASCADE;
                DROP TABLE IF EXISTS public.customers;
                """);
        try (Connection c = cfg.admin(); Statement st = c.createStatement()) {
            for (String role : new String[]{"qw_agent", "app_user"}) {
                try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + role + "'")) {
                    if (rs.next()) {
                        st.execute("DROP OWNED BY " + role + " CASCADE");
                        st.execute("DROP ROLE " + role);
                    }
                }
            }
        }
    }

    void admin(String sql) throws Exception {
        try (Connection c = cfg.admin(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    long count(String sql) throws Exception {
        try (Connection c = cfg.admin(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void runsOnEmptyDatabaseAndCanRerun() {
        assertDoesNotThrow(() -> Database.setup(cfg, POLICY));
        assertDoesNotThrow(() -> Database.setup(cfg, POLICY), "re-running on its own demo database must work");
    }

    @Test
    void refusesWhenSchemaExistsButWasNotCreatedBySetup() throws Exception {
        admin("CREATE SCHEMA shop; CREATE TABLE shop.invoices (id int); INSERT INTO shop.invoices VALUES (1)");
        var e = assertThrows(Database.SetupRefusedException.class, () -> Database.setup(cfg, POLICY));
        assertTrue(e.getMessage().contains("Schema 'shop' already exists"), e.getMessage());
        assertEquals(1, count("SELECT count(*) FROM shop.invoices"), "real data must be untouched");
    }

    @Test
    void refusesWhenOtherSchemasHoldTables() throws Exception {
        admin("CREATE TABLE public.customers (id int); INSERT INTO public.customers VALUES (1), (2)");
        var e = assertThrows(Database.SetupRefusedException.class, () -> Database.setup(cfg, POLICY));
        assertTrue(e.getMessage().contains("Schema 'public' contains 1 table"), e.getMessage());
        assertEquals(0, count("SELECT count(*) FROM pg_namespace WHERE nspname = 'shop'"), "nothing may be created");
        assertEquals(2, count("SELECT count(*) FROM public.customers"));
    }

    @Test
    void refusesWhenAgentRoleAlreadyExistsForSomethingElse() throws Exception {
        admin("CREATE ROLE qw_agent LOGIN PASSWORD 'their-real-password'");
        var e = assertThrows(Database.SetupRefusedException.class, () -> Database.setup(cfg, POLICY));
        assertTrue(e.getMessage().contains("Role 'qw_agent' already exists"), e.getMessage());
    }

    @Test
    void refusesWhenAgentUserIsTheAdmin() {
        var sameUser = new Database.Config(PG.getJdbcUrl(), "postgres", "postgres", "postgres", "postgres");
        var e = assertThrows(Database.SetupRefusedException.class, () -> Database.setup(sameUser, POLICY));
        assertTrue(e.getMessage().contains("same user"), e.getMessage());
    }

    @Test
    void refusesWhenDemoSchemaWasLaterJoinedByRealTables() throws Exception {
        Database.setup(cfg, POLICY);
        admin("CREATE SCHEMA billing; CREATE TABLE billing.payments (id int)");
        var e = assertThrows(Database.SetupRefusedException.class, () -> Database.setup(cfg, POLICY));
        assertTrue(e.getMessage().contains("Schema 'billing' contains"), e.getMessage());
        assertEquals(600, count("SELECT count(*) FROM shop.orders"), "demo data from the first run stays as it was");
    }
}
