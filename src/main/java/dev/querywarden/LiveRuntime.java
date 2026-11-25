package dev.querywarden;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Keeps the web server's setup in step with policy.yaml and the database, without a restart.
 *
 * <p>Every check (30 seconds by default) reads the policy file if it changed and describes the database
 * as the agent sees it (tables, columns, comments, category values). If either changed, a new
 * {@link WebServer.Runtime} is built (a new prompt, new example questions) and swapped in at once;
 * questions already running finish on the old one. An invalid policy file is not applied: the last
 * good one stays in use and the error is shown in the Settings panel.
 *
 * <p>Example questions are written by Claude when the policy has none, and cached in
 * {@code .querywarden/examples.json} by a fingerprint of the schema and definitions, so a restart
 * doesn't pay for new ones unless something changed.
 */
public final class LiveRuntime implements Supplier<WebServer.Runtime> {

    static final int EXAMPLE_COUNT = 6;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Database.Config cfg;
    private final DataSource pool;
    private final String policyArg;
    private final SqlGenerator generator;
    private final AiHelper ai;
    private final Path auditLog;
    private final Path cacheFile;

    private volatile WebServer.Runtime current;
    private volatile String context;          // schema text + definitions: what Claude sees
    private volatile String schemaText;       // the database as the agent sees it, on its own
    private long policyStamp;
    private volatile Path policyPath;
    private volatile String lastError;
    private volatile Instant lastChecked;
    private volatile Instant lastChange;
    private volatile String lastChangeReason = "started";
    private ScheduledExecutorService timer;

    public LiveRuntime(Database.Config cfg, DataSource pool, String policyArg, SqlGenerator generator, AiHelper ai,
                       Path auditLog, Path cacheFile) throws Exception {
        this.cfg = cfg;
        this.pool = pool;
        this.policyArg = policyArg;
        this.generator = generator;
        this.ai = ai;
        this.auditLog = auditLog;
        this.cacheFile = cacheFile;
        Policy.Loaded loaded = Policy.resolve(policyArg);   // invalid at startup: fail loudly
        policyStamp = stamp(loaded.path());
        policyPath = loaded.path();
        rebuild(loaded.policy(), loaded.source(), "started");
    }

    @Override
    public WebServer.Runtime get() {
        return current;
    }

    /** Checks every {@code seconds} on a background thread. */
    public void start(int seconds) {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "querywarden-live-reload");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                if (check()) System.out.println("Reloaded: " + lastChangeReason);
            } catch (RuntimeException e) {
                lastError = "Check failed: " + e.getMessage();
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (timer != null) timer.shutdownNow();
    }

    /** Looks for changes now. Returns true if a new setup was swapped in. */
    public synchronized boolean check() {
        lastChecked = Instant.now();
        WebServer.Runtime rt = current;
        Policy policy = rt.policy();
        String source = rt.policySource();
        boolean policyChanged = false;
        String policyError = null;
        try {
            Policy.Loaded loaded = Policy.resolve(policyArg);
            long stamp = stamp(loaded.path());
            if (stamp != policyStamp || !loaded.policy().equals(policy)) {
                policyChanged = !loaded.policy().equals(policy);
                policyStamp = stamp;
                policyPath = loaded.path();
                policy = loaded.policy();
                source = loaded.source();
            }
        } catch (Policy.PolicyException e) {
            policyError = "policy file not applied, still using the last good one: " + e.getMessage();
        }
        try {
            boolean schemaChanged = !Database.formatSchema(Database.schemaInfo(cfg, policy)).equals(schemaText);
            lastError = policyError;
            if (!policyChanged && !schemaChanged) return false;
            rebuild(policy, source, policyChanged && schemaChanged ? "policy file and database changed"
                    : policyChanged ? "policy file changed" : "database tables, columns or category values changed");
            return true;
        } catch (Exception e) {
            lastError = (policyError != null ? policyError + " " : "") + "Couldn't read the database: " + e.getMessage();
            return false;
        }
    }

    private void rebuild(Policy policy, String source, String reason) throws Exception {
        List<Database.TableInfo> tables = Database.schemaInfo(cfg, policy);
        String ctx = contextFor(policy, tables);
        schemaText = Database.formatSchema(tables);
        VerifiedAnswers verified = VerifiedAnswers.forPolicy(policyPath);
        QueryWardenAgent agent = new QueryWardenAgent(cfg, pool, policy, generator, 3, auditLog).withVerified(verified);
        WebServer.Examples examples = examplesFor(policy, ctx, List.of(), false);
        context = ctx;
        current = new WebServer.Runtime(agent, policy, tables, examples, source, Instant.now(), verified);
        lastChange = Instant.now();
        lastChangeReason = reason;
    }

    /** Fresh example questions, different from the current ones (the "More examples" button). */
    public synchronized WebServer.Examples refreshExamples() {
        WebServer.Runtime rt = current;
        if (!rt.policy().examples().isEmpty()) return rt.examples();
        WebServer.Examples fresh = examplesFor(rt.policy(), context, rt.examples().questions(), true);
        if (fresh.questions().isEmpty()) return rt.examples();
        current = new WebServer.Runtime(rt.agent(), rt.policy(), rt.tables(), fresh, rt.policySource(), rt.loadedAt(),
                rt.verified());
        return fresh;
    }

    WebServer.Examples examplesFor(Policy policy, String ctx, List<String> avoid, boolean forceNew) {
        if (!policy.examples().isEmpty()) return new WebServer.Examples(policy.examples(), "policy");
        String key = UserStore.sha256(ctx);
        Map<String, List<String>> cache = readCache();
        if (!forceNew && cache.containsKey(key)) return new WebServer.Examples(cache.get(key), "ai");
        List<String> suggested = ai.suggestExamples(ctx, EXAMPLE_COUNT, avoid);
        if (suggested.isEmpty()) {
            return cache.containsKey(key) ? new WebServer.Examples(cache.get(key), "ai") : new WebServer.Examples(List.of(), "none");
        }
        cache.put(key, suggested);
        writeCache(cache);
        return new WebServer.Examples(suggested, "ai");
    }

    static String contextFor(Policy policy, List<Database.TableInfo> tables) {
        return Database.formatSchema(tables)
                + (policy.definitions().isEmpty() ? "" : "\n\nBusiness definitions:\n" + policy.definitionsText());
    }

    /** For the Settings panel. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", timer != null);
        m.put("last_checked", lastChecked == null ? null : lastChecked.toString());
        m.put("last_change", lastChange == null ? null : lastChange.toString());
        m.put("last_change_reason", lastChangeReason);
        m.put("last_error", lastError);
        return m;
    }

    private Map<String, List<String>> readCache() {
        try {
            if (cacheFile != null && Files.exists(cacheFile))
                return new LinkedHashMap<>(JSON.readValue(cacheFile.toFile(), new TypeReference<Map<String, List<String>>>() { }));
        } catch (IOException ignored) {
            // a broken cache is just regenerated
        }
        return new LinkedHashMap<>();
    }

    private void writeCache(Map<String, List<String>> cache) {
        if (cacheFile == null) return;
        try {
            Files.createDirectories(cacheFile.toAbsolutePath().getParent());
            // keep the 20 most recent schema versions
            while (cache.size() > 20) cache.remove(cache.keySet().iterator().next());
            Files.writeString(cacheFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(cache));
        } catch (IOException e) {
            System.err.println("warning: couldn't cache example questions: " + e.getMessage());
        }
    }

    private static long stamp(Path file) {
        try {
            return file == null || !Files.exists(file) ? 0 : Files.getLastModifiedTime(file).toMillis() ^ (Files.size(file) << 20);
        } catch (IOException e) {
            return 0;
        }
    }
}
