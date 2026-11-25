package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.zaxxer.hikari.HikariDataSource;

/** policy.yaml and database changes are picked up without a restart; examples are cached. */
@Testcontainers
class LiveRuntimeTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shop").withUsername("postgres").withPassword("postgres");

    static Database.Config cfg;
    static HikariDataSource pool;

    @BeforeAll
    static void setup() throws Exception {
        cfg = new Database.Config(PG.getJdbcUrl(), "postgres", "postgres", "qw_agent", "pw");
        Database.setup(cfg, Policy.defaults());
        pool = cfg.agentPool(2);
    }

    @AfterAll
    static void close() {
        pool.close();
    }

    /** Counts calls and remembers what it was asked to avoid. */
    static final class CountingAi implements AiHelper {
        final AtomicInteger calls = new AtomicInteger();
        final List<List<String>> avoided = new CopyOnWriteArrayList<>();

        @Override
        public List<String> suggestExamples(String schemaText, int count) {
            return suggestExamples(schemaText, count, List.of());
        }

        @Override
        public List<String> suggestExamples(String schemaText, int count, List<String> avoid) {
            int n = calls.incrementAndGet();
            avoided.add(avoid);
            return List.of("Example set " + n + " question A", "Example set " + n + " question B");
        }

        @Override
        public Insight describe(String s, String q, String sql, List<String> c, List<List<Object>> r) {
            return Insight.NONE;
        }
    }

    static final SqlGenerator UNUSED = system -> text -> new SqlGenerator.Output(false, "", "", "unused");

    @Test
    void examplesAreWrittenOnceAndCached(@TempDir Path dir) throws Exception {
        Path policy = dir.resolve("policy.yaml");
        Files.writeString(policy, "schema: shop\n");
        Path cache = dir.resolve("cache/examples.json");
        CountingAi ai = new CountingAi();
        LiveRuntime first = new LiveRuntime(cfg, pool, policy.toString(), UNUSED, ai, null, cache);
        assertEquals("ai", first.get().examples().source());
        assertEquals(1, ai.calls.get());
        assertTrue(Files.exists(cache));

        LiveRuntime restarted = new LiveRuntime(cfg, pool, policy.toString(), UNUSED, ai, null, cache);
        assertEquals(1, ai.calls.get(), "same schema after a restart: examples come from the cache, no new call");
        assertEquals(first.get().examples(), restarted.get().examples());
    }

    @Test
    void policyChangesApplyWithoutRestartAndBadFilesAreRefused(@TempDir Path dir) throws Exception {
        Path policy = dir.resolve("policy.yaml");
        Files.writeString(policy, "schema: shop\n");
        LiveRuntime live = new LiveRuntime(cfg, pool, policy.toString(), UNUSED, new CountingAi(), null, null);
        assertFalse(live.check(), "nothing changed yet");

        Files.writeString(policy, "schema: shop\ndefinitions:\n  revenue: \"Excludes cancelled orders.\"\n");
        assertTrue(live.check());
        assertTrue(live.get().agent().systemPrompt().contains("- revenue: Excludes cancelled orders."), "new prompt in use");
        assertEquals("policy file changed", live.status().get("last_change_reason"));

        Policy good = live.get().policy();
        Files.writeString(policy, "schema: shop\nblocked_column: {employees: [salary]}\n");   // typo
        assertFalse(live.check());
        assertEquals(good, live.get().policy(), "the last good policy stays in use");
        assertTrue(String.valueOf(live.status().get("last_error")).contains("unknown key"), live.status().toString());

        Files.writeString(policy, "schema: shop\n");
        assertTrue(live.check());
        assertEquals(null, live.status().get("last_error"), "fixed file clears the error");
    }

    @Test
    void newColumnsInTheDatabaseArePickedUp(@TempDir Path dir) throws Exception {
        Path policy = dir.resolve("policy.yaml");
        Files.writeString(policy, "schema: shop\n");
        LiveRuntime live = new LiveRuntime(cfg, pool, policy.toString(), UNUSED, new CountingAi(), null, null);
        try (Connection c = cfg.admin(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE shop.products ADD COLUMN IF NOT EXISTS colour text");
            st.execute("GRANT SELECT (colour) ON shop.products TO qw_agent");
        }
        try {
            assertTrue(live.check());
            assertTrue(live.get().agent().systemPrompt().contains("colour"), "Claude now sees the new column");
            assertTrue(String.valueOf(live.status().get("last_change_reason")).contains("database"));
        } finally {
            try (Connection c = cfg.admin(); Statement st = c.createStatement()) {
                st.execute("ALTER TABLE shop.products DROP COLUMN colour");
            }
        }
    }

    @Test
    void moreExamplesAvoidsTheCurrentOnes(@TempDir Path dir) throws Exception {
        Path policy = dir.resolve("policy.yaml");
        Files.writeString(policy, "schema: shop\n");
        CountingAi ai = new CountingAi();
        LiveRuntime live = new LiveRuntime(cfg, pool, policy.toString(), UNUSED, ai, null, null);
        List<String> before = live.get().examples().questions();
        WebServer.Examples fresh = live.refreshExamples();
        assertNotNull(fresh);
        assertFalse(fresh.questions().equals(before));
        assertEquals(before, ai.avoided.get(ai.avoided.size() - 1), "Claude is told which questions to avoid");
        assertEquals(fresh, live.get().examples(), "the page gets the new set");
    }

    @Test
    void policyExamplesStillWinWhenGiven(@TempDir Path dir) throws Exception {
        Path policy = dir.resolve("policy.yaml");
        Files.writeString(policy, "schema: shop\nexamples: [\"My own question\"]\n");
        CountingAi ai = new CountingAi();
        LiveRuntime live = new LiveRuntime(cfg, pool, policy.toString(), UNUSED, ai, null, null);
        assertEquals("policy", live.get().examples().source());
        assertEquals(0, ai.calls.get());
    }
}
