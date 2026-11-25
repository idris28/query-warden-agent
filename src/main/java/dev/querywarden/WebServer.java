package dev.querywarden;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

/**
 * The web page's server. It runs the same {@link QueryWardenAgent} as the command line; the browser
 * never talks to the database or to Claude directly.
 *
 * <p>First version: local use only. Protections on top of the agent's guardrails:
 * <ul>
 *   <li>listens on a loopback address only, and refuses to start on any other;</li>
 *   <li>rejects requests whose {@code Host} header isn't local (blocks DNS-rebinding tricks) and
 *       cross-site {@code Origin}s;</li>
 *   <li>accepts only small JSON bodies on the API;</li>
 *   <li>rate limit per client and a cap on questions in progress, to protect the API bill;</li>
 *   <li>strict Content-Security-Policy and other security headers on every response;</li>
 *   <li>errors return plain messages only, never stack traces.</li>
 * </ul>
 */
public final class WebServer {

    /** Settings for the server. */
    /**
     * Settings for the server. {@code users} is null for local use (this computer only, no sign-in);
     * with a user store the page is shared: it may listen on the network, and every API request needs
     * a valid access token.
     */
    public record Options(String host, int port, int maxInFlight, int perClientPerMinute, UserStore users) {
        public Options(String host, int port, int maxInFlight, int perClientPerMinute) {
            this(host, port, maxInFlight, perClientPerMinute, null);
        }

        public static Options defaults() {
            return new Options("127.0.0.1", 8080, 4, 10);
        }

        public Options withPort(int p) {
            return new Options(host, p, maxInFlight, perClientPerMinute, users);
        }

        public Options shared(String h, UserStore u) {
            return new Options(h, port, maxInFlight, perClientPerMinute, u);
        }

        public boolean sharing() {
            return users != null;
        }
    }

    static final int MAX_FAILED_SIGN_INS = 10;
    static final long SIGN_IN_WINDOW_MS = 10 * 60_000;
    private final Map<String, Deque<Long>> failedSignIns = new HashMap<>();

    static final Set<String> LOCAL_HOSTNAMES = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    /** Example questions for the page, and where they came from ("policy", "ai" or "none"). */
    public record Examples(List<String> questions, String source) {}

    /**
     * Examples from policy.yaml if it lists any; otherwise Claude suggests some that fit the tables
     * the agent can see. If that fails, the page simply shows none.
     */
    public static Examples resolveExamples(Policy policy, AiHelper ai, String schemaText) {
        if (!policy.examples().isEmpty()) return new Examples(policy.examples(), "policy");
        List<String> suggested = ai.suggestExamples(schemaText, 6);
        return suggested.isEmpty() ? new Examples(List.of(), "none") : new Examples(suggested, "ai");
    }

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; "
            + "frame-ancestors 'none'";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Everything that depends on the policy and the database: swapped as a whole when either changes
     * (see {@link LiveRuntime}). Each request reads it once, so it never mixes an old and a new setup.
     */
    public record Runtime(QueryWardenAgent agent, Policy policy, List<Database.TableInfo> tables, Examples examples,
                          String policySource, java.time.Instant loadedAt, VerifiedAnswers verified) {}

    private final Supplier<Runtime> runtime;
    private final DataSource agentConnections;
    private final AiHelper ai;
    private final Options options;
    private final Semaphore inFlight;
    private final Map<String, Deque<Long>> recentByClient = new HashMap<>();
    private volatile int boundPort;

    /** Without AI help: examples only from the policy, no summaries or follow-ups. */
    public WebServer(QueryWardenAgent agent, Policy policy, DataSource agentConnections,
                     Supplier<List<Database.TableInfo>> schema, Options options) {
        this(agent, policy, agentConnections, schema, AiHelper.NONE,
                new Examples(policy.examples(), policy.examples().isEmpty() ? "none" : "policy"), options);
    }

    public WebServer(QueryWardenAgent agent, Policy policy, DataSource agentConnections,
                     Supplier<List<Database.TableInfo>> schema, AiHelper ai, Examples examples, Options options) {
        this(fixed(new Runtime(agent, policy, schema.get(), examples, null, java.time.Instant.now(), null)),
                agentConnections, ai, options);
    }

    /** A server whose setup can change while it runs (live reload of policy and schema). */
    public WebServer(Supplier<Runtime> runtime, DataSource agentConnections, AiHelper ai, Options options) {
        this.runtime = runtime;
        this.agentConnections = agentConnections;
        this.ai = ai;
        this.options = options;
        this.inFlight = new Semaphore(options.maxInFlight());
    }

    private static Supplier<Runtime> fixed(Runtime r) {
        return () -> r;
    }

    /** Builds and starts the server. Throws if the host isn't a loopback address. */
    public Javalin start() {
        if (!options.sharing()) {
            requireLoopback(options.host());
        } else {
            try {
                if (options.users().list().isEmpty())
                    throw new IllegalArgumentException("Sharing needs at least one person. Add one with: querywarden users add NAME");
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Can't read " + options.users().file() + ": " + e.getMessage());
            }
        }
        Javalin app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.jetty.host = options.host();
            config.jetty.port = options.port();
            config.http.maxRequestSize = 8_192;
            config.staticFiles.add("/web", Location.CLASSPATH);

            config.routes.before(this::guard);
            config.routes.get("/api/health", this::health);
            config.routes.get("/api/me", this::me);
            config.routes.get("/api/schema", this::schema);
            config.routes.get("/api/settings", this::settings);
            config.routes.post("/api/ask", this::ask);
            config.routes.post("/api/ask/stream", this::askStream);
            config.routes.post("/api/examples/refresh", this::refreshExamples);
            config.routes.post("/api/feedback", this::giveFeedback);
            config.routes.get("/api/review", this::review);
            config.routes.post("/api/review/try", this::trySql);
            config.routes.post("/api/review/verify", this::verify);
            config.routes.post("/api/review/dismiss", this::dismiss);
            config.routes.post("/api/review/remove", this::removeVerified);
            config.routes.exception(Exception.class, (e, ctx) -> {
                System.err.println("web: unexpected error on " + ctx.path() + ": " + e);
                json(ctx, 500, Map.of("status", "failed", "message", "Something went wrong on the server."));
            });
            config.routes.error(404, "*", ctx -> {
                if (ctx.path().startsWith("/api/")) json(ctx, 404, Map.of("status", "failed", "message", "Not found."));
            });
        });
        app.start();
        boundPort = app.port();
        return app;
    }

    static void requireLoopback(String host) {
        try {
            if (!InetAddress.getByName(host).isLoopbackAddress())
                throw new IllegalArgumentException("For now the web page only runs on this computer; '" + host
                        + "' is not a local address. Use 127.0.0.1.");
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unknown host: " + host);
        }
    }

    // ------------------------------------------------------------------ protections on every request
    private void guard(Context ctx) {
        ctx.header("Content-Security-Policy", CSP);
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("X-Frame-Options", "DENY");
        ctx.header("Referrer-Policy", "no-referrer");
        ctx.header("Cross-Origin-Opener-Policy", "same-origin");
        ctx.header("Cross-Origin-Resource-Policy", "same-origin");
        if (ctx.path().startsWith("/api/")) ctx.header("Cache-Control", "no-store");

        if (!options.sharing()) {
            if (!isLocalHostHeader(ctx.header("Host"))) {
                ctx.status(403).result("Forbidden: this page only answers on localhost.");
                ctx.skipRemainingHandlers();
                return;
            }
            String origin = ctx.header("Origin");
            if (origin != null && !isLocalOrigin(origin, boundPort)) {
                json(ctx, 403, Map.of("status", "failed", "message", "Requests from other websites are not allowed."));
                ctx.skipRemainingHandlers();
            }
            return;
        }

        // Shared: any address may load the page, but the API needs a token, and requests must come
        // from the page itself (same origin as the Host it was loaded from).
        String origin = ctx.header("Origin");
        if (origin != null && !isSameOrigin(origin, ctx.header("Host"))) {
            json(ctx, 403, Map.of("status", "failed", "message", "Requests from other websites are not allowed."));
            ctx.skipRemainingHandlers();
            return;
        }
        if (!ctx.path().startsWith("/api/")) return;   // the page itself (it shows the sign-in form)
        String ip = ctx.ip();
        if (tooManyFailures(ip)) {
            json(ctx, 429, Map.of("status", "failed", "message",
                    "Too many failed sign-ins from this address. Wait 10 minutes and try again."));
            ctx.skipRemainingHandlers();
            return;
        }
        String auth = ctx.header("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).strip() : null;
        var who = options.users().authenticate(token);
        if (who.isEmpty()) {
            if (token != null) recordFailure(ip);
            json(ctx, 401, Map.of("status", "failed", "auth", "required",
                    "message", token == null ? "Sign in with your access token." : "That access token isn't valid."));
            ctx.skipRemainingHandlers();
            return;
        }
        ctx.attribute("user", who.get());
    }

    /** Who is asking: "web alice@10.0.0.5" when shared, "web 127.0.0.1" locally. For the audit log. */
    private static String client(Context ctx) {
        Object user = ctx.attribute("user");
        return "web " + (user == null ? "" : user + "@") + ctx.ip();
    }

    /** Rate limits apply per person when shared, per address locally. */
    private static String limitKey(Context ctx) {
        Object user = ctx.attribute("user");
        return user == null ? ctx.ip() : "user:" + user;
    }

    static boolean isSameOrigin(String origin, String host) {
        try {
            URI u = URI.create(origin);
            if (u.getHost() == null || host == null) return false;
            String o = u.getHost().toLowerCase(Locale.ROOT) + (u.getPort() < 0 ? "" : ":" + u.getPort());
            String h = host.toLowerCase(Locale.ROOT).strip();
            boolean defaultPort = u.getPort() < 0 || ("http".equals(u.getScheme()) && u.getPort() == 80)
                    || ("https".equals(u.getScheme()) && u.getPort() == 443);
            return o.equals(h) || (defaultPort && u.getHost().toLowerCase(Locale.ROOT).equals(h.replaceAll(":(80|443)$", "")));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private synchronized boolean tooManyFailures(String ip) {
        Deque<Long> times = failedSignIns.get(ip);
        if (times == null) return false;
        long now = System.currentTimeMillis();
        while (!times.isEmpty() && now - times.peekFirst() > SIGN_IN_WINDOW_MS) times.pollFirst();
        return times.size() >= MAX_FAILED_SIGN_INS;
    }

    private synchronized void recordFailure(String ip) {
        failedSignIns.computeIfAbsent(ip, k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
    }

    private void me(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", ctx.attribute("user"));
        out.put("sharing", options.sharing());
        out.put("reviewer", isReviewer(ctx));
        out.put("feedback", feedback != null);
        json(ctx, 200, out);
    }

    static boolean isLocalHostHeader(String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {                       // [::1]:8080
            int end = h.indexOf(']');
            return end > 0 && LOCAL_HOSTNAMES.contains(h.substring(0, end + 1));
        }
        int colon = h.indexOf(':');
        return LOCAL_HOSTNAMES.contains(colon >= 0 ? h.substring(0, colon) : h);
    }

    static boolean isLocalOrigin(String origin, int port) {
        try {
            URI u = URI.create(origin);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return "http".equals(u.getScheme()) && u.getPort() == port
                    && (LOCAL_HOSTNAMES.contains(host) || LOCAL_HOSTNAMES.contains("[" + host + "]"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ endpoints
    private void health(Context ctx) {
        boolean db;
        try (var c = agentConnections.getConnection(); var st = c.createStatement()) {
            st.execute("SELECT 1");
            db = true;
        } catch (Exception e) {
            db = false;
        }
        json(ctx, db ? 200 : 503, Map.of("ok", db, "database", db ? "reachable" : "unreachable"));
    }

    private void schema(Context ctx) {
        Runtime rt = runtime.get();
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Database.TableInfo t : rt.tables()) {
            List<Map<String, Object>> cols = new ArrayList<>();
            for (Database.ColumnInfo c : t.columns()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", c.name());
                col.put("type", c.type());
                col.put("note", c.note());
                col.put("masked", rt.policy().isMasked(c.name()));
                cols.add(col);
            }
            tables.add(Map.of("name", t.name(), "columns", cols));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", tables);
        out.put("examples", rt.examples().questions());
        out.put("examples_source", rt.examples().source());
        out.put("examples_refreshable", exampleRefresher != null);
        out.put("database_id", databaseId(rt));
        out.put("shares_results_with_ai", rt.policy().shareResultsWithAi());
        out.put("max_question_chars", rt.policy().maxQuestionChars());
        out.put("max_rows", rt.policy().maxRows());
        json(ctx, 200, out);
    }

    /** A question plus earlier turns of the same conversation (for follow-ups). */
    record Request(String question, List<QueryWardenAgent.Turn> history) {}

    /**
     * Reads {"question": "...", "history": [{"question": "...", "sql": "..."}]} and applies the rate and
     * busy limits. Returns null after replying with an error.
     */
    private Request admit(Context ctx) {
        String type = ctx.contentType() == null ? "" : ctx.contentType().toLowerCase(Locale.ROOT);
        if (!type.startsWith("application/json")) {
            json(ctx, 415, failed("Send the question as JSON."));
            return null;
        }
        String question;
        List<QueryWardenAgent.Turn> history = new ArrayList<>();
        try {
            JsonNode body = JSON.readTree(ctx.body());
            JsonNode q = body == null ? null : body.get("question");
            if (q == null || !q.isTextual()) throw new IllegalArgumentException();
            question = q.asText();
            JsonNode h = body.path("history");
            if (!h.isMissingNode() && !h.isNull()) {
                if (!h.isArray() || h.size() > 10) throw new IllegalArgumentException();
                for (JsonNode t : h) {
                    if (!t.path("question").isTextual() || !t.path("sql").isTextual()) throw new IllegalArgumentException();
                    history.add(new QueryWardenAgent.Turn(t.get("question").asText(), t.get("sql").asText()));
                }
            }
        } catch (Exception e) {
            json(ctx, 400, failed("Expected {\"question\": \"...\"}."));
            return null;
        }
        if (!allow(limitKey(ctx))) {
            json(ctx, 429, failed("Too many questions. You can ask up to " + options.perClientPerMinute()
                    + " per minute; please wait a moment."));
            return null;
        }
        if (!inFlight.tryAcquire()) {
            json(ctx, 503, failed("QueryWarden is busy with other questions. Please try again in a few seconds."));
            return null;
        }
        return new Request(question, history);   // caller must release inFlight
    }

    private void ask(Context ctx) {
        Runtime rt = runtime.get();
        Request req = admit(ctx);
        if (req == null) return;
        String question = req.question();
        String client = client(ctx);
        long start = System.nanoTime();
        QueryWardenAgent.Result r;
        try {
            r = rt.agent().ask(question, client, p -> { }, req.history());
        } finally {
            inFlight.release();
        }
        json(ctx, 200, toJson(r, (System.nanoTime() - start) / 1_000_000));
    }

    /**
     * Streams newline-delimited JSON events while the question is answered, so the page can show
     * live progress:
     * <pre>
     *   {"type":"progress","stage":"writing|checking|running|rejected|summarizing","text":"..."}
     *   {"type":"result", ...same fields as /api/ask...}
     *   {"type":"insights","summary":"...","follow_ups":["..."]}     (answered questions only)
     *   {"type":"done"}
     * </pre>
     */
    private void askStream(Context ctx) throws Exception {
        Runtime rt = runtime.get();
        Request req = admit(ctx);
        if (req == null) return;
        String question = req.question();
        String client = client(ctx);
        try {
            var res = ctx.res();
            res.setStatus(200);
            res.setContentType("application/x-ndjson; charset=utf-8");
            res.setHeader("Cache-Control", "no-store");
            res.setHeader("X-Accel-Buffering", "no");
            var out = res.getOutputStream();
            java.util.function.Consumer<Map<String, Object>> send = event -> {
                synchronized (out) {
                    try {
                        out.write((JSON.writeValueAsString(event) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        out.flush();
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);   // browser went away
                    }
                }
            };
            long start = System.nanoTime();
            QueryWardenAgent.Result r = rt.agent().ask(question, client,
                    p -> send.accept(Map.of("type", "progress", "stage", p.stage(), "text", p.text())), req.history());
            Map<String, Object> result = new LinkedHashMap<>(toJson(r, (System.nanoTime() - start) / 1_000_000));
            result.put("type", "result");
            send.accept(result);

            if (r.status() == QueryWardenAgent.Status.OK && ai != AiHelper.NONE) {
                boolean share = rt.policy().shareResultsWithAi();
                send.accept(Map.of("type", "progress", "stage", "summarizing",
                        "text", share ? "Claude is summarising the result" : "Claude is suggesting follow-up questions"));
                String context = Database.formatSchema(rt.tables())
                        + (rt.policy().definitions().isEmpty() ? "" : "\n\nBusiness definitions:\n" + rt.policy().definitionsText());
                AiHelper.Insight insight = ai.describe(context, question, r.sql(),
                        r.result().columns(), share ? r.result().rows() : null);
                rt.agent().auditHelperCall(question, client, insight.usage());
                send.accept(Map.of("type", "insights", "summary", insight.summary(), "follow_ups", insight.followUps()));
            }
            send.accept(Map.of("type", "done"));
        } catch (java.io.UncheckedIOException e) {
            // the browser closed the connection; nothing more to send
        } finally {
            inFlight.release();
        }
    }

    // ------------------------------------------------------------------ read-only settings
    private volatile String policySource = "not given";
    private volatile ModelSettings models = ModelSettings.defaults();
    private volatile boolean fallbacksRequested = true;
    private volatile Supplier<Map<String, Object>> liveStatus = () -> Map.of("enabled", false);
    private volatile Supplier<Examples> exampleRefresher = null;

    /** Live reload status for the settings panel, and how to fetch a fresh set of example questions. */
    public WebServer withLive(Supplier<Map<String, Object>> status, Supplier<Examples> refresher) {
        this.liveStatus = status;
        this.exampleRefresher = refresher;
        return this;
    }

    /** What the settings panel shows about where the policy came from and which models answer. */
    public WebServer withInfo(String policySource, ModelSettings models, boolean fallbacksRequested) {
        this.policySource = policySource;
        this.models = models;
        this.fallbacksRequested = fallbacksRequested;
        return this;
    }

    /**
     * The settings in effect, for the page's read-only Settings panel. Never includes secrets (API key,
     * database passwords or URL). Blocked columns are counted, not named: their names are kept out of
     * the page just as they are kept out of what Claude sees.
     */
    private void settings(Context ctx) {
        Runtime rt = runtime.get();
        List<Map<String, Object>> blocked = new ArrayList<>();
        rt.policy().blockedColumns().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(e -> !e.getValue().isEmpty() && rt.policy().tableAllowed(e.getKey()))
                .forEach(e -> blocked.add(Map.of("table", e.getKey(), "count", e.getValue().size())));

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_rows", rt.policy().maxRows());
        limits.put("timeout_ms", rt.policy().timeoutMillis());
        limits.put("max_joins", rt.policy().maxJoins());
        limits.put("max_selects", rt.policy().maxSelects());
        limits.put("max_question_chars", rt.policy().maxQuestionChars());
        limits.put("max_sql_chars", rt.policy().maxSqlChars());

        Map<String, Object> pol = new LinkedHashMap<>();
        pol.put("source", rt.policySource() != null ? rt.policySource() : policySource);
        pol.put("loaded_at", rt.loadedAt().toString());
        pol.put("schema", rt.policy().schema());
        pol.put("allowed_tables", rt.policy().allowedTables().stream().sorted().toList());
        pol.put("blocked_columns", blocked);
        pol.put("masked_columns", rt.policy().maskedColumns().stream().sorted().toList());
        pol.put("blocked_functions", rt.policy().blockedFunctions().size());
        pol.put("limits", limits);
        pol.put("examples_source", rt.examples().source());
        pol.put("share_results_with_ai", rt.policy().shareResultsWithAi());
        pol.put("definitions", rt.policy().definitions());
        pol.put("share_category_values", rt.policy().shareCategoryValues());

        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sql_model", models.sqlModel());
        ai.put("sql_effort", models.sqlEffort());
        ai.put("helper_model", models.helperModel());
        ai.put("helper_effort", models.helperEffort());
        ai.put("helper_enabled", this.ai != AiHelper.NONE);
        ai.put("refusal_fallback_sql", fallbacksRequested && ModelSettings.supportsServerFallback(models.sqlModel()));
        ai.put("refusal_fallback_helper", fallbacksRequested && ModelSettings.supportsServerFallback(models.helperModel()));

        Map<String, Object> web = new LinkedHashMap<>();
        web.put("address", options.host() + ":" + boundPort);
        web.put("local_only", !options.sharing());
        web.put("signed_in_as", ctx.attribute("user"));
        try {
            web.put("people", options.sharing() ? options.users().list().size() : 0);
        } catch (java.io.IOException e) {
            web.put("people", 0);
        }
        web.put("questions_per_minute", options.perClientPerMinute());
        web.put("questions_at_once", options.maxInFlight());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy", pol);
        out.put("models", ai);
        out.put("web", web);
        out.put("live", liveStatus.get());
        out.put("how_to_change", Boolean.TRUE.equals(liveStatus.get().get("enabled"))
                ? "Edit policy.yaml and it is picked up within 30 seconds, as are new tables or columns in the database. "
                        + "Model settings (QW_* variables) need a restart. Nothing can be changed from this page."
                : "Edit policy.yaml (rules) or the QW_* environment variables (models), then restart `querywarden serve`. "
                        + "Settings can't be changed from the page.");
        json(ctx, 200, out);
    }

    /**
     * A short, anonymous id for the database and schema the page is looking at, so the browser can keep
     * recent questions per database. A hash, so it reveals nothing about the connection.
     */
    String databaseId(Runtime rt) {
        try {
            String key;
            try (var c = agentConnections.getConnection()) {
                key = c.getMetaData().getURL() + "|" + rt.policy().schema();
            }
            return UserStore.sha256(key).substring(0, 12);
        } catch (Exception e) {
            return "default";
        }
    }

    /** Asks Claude for a new set of example questions (counts against the same per-person limit). */
    private void refreshExamples(Context ctx) {
        if (exampleRefresher == null) {
            json(ctx, 404, failed("Example questions come from policy.yaml here, so they can't be refreshed."));
            return;
        }
        if (!allow(limitKey(ctx))) {
            json(ctx, 429, failed("Too many requests. Please wait a moment."));
            return;
        }
        Examples e = exampleRefresher.get();
        json(ctx, 200, Map.of("examples", e.questions(), "examples_source", e.source()));
    }

    // ------------------------------------------------------------------ feedback and verified answers
    private volatile FeedbackStore feedback = null;

    /** Where "Correct" / "Wrong" feedback is kept (null: feedback is off). */
    public WebServer withFeedback(FeedbackStore store) {
        this.feedback = store;
        return this;
    }

    /** Locally the one user is the owner and reviews; when shared, only people added with --reviewer. */
    private boolean isReviewer(Context ctx) {
        if (!options.sharing()) return true;
        Object user = ctx.attribute("user");
        return user != null && options.users().isReviewer(user.toString());
    }

    private static String who(Context ctx) {
        Object user = ctx.attribute("user");
        return user == null ? "local user" : user.toString();
    }

    /** Reads a small JSON body; replies with an error and returns null if it isn't one. */
    private JsonNode jsonBody(Context ctx) {
        String type = ctx.contentType() == null ? "" : ctx.contentType().toLowerCase(Locale.ROOT);
        if (!type.startsWith("application/json")) {
            json(ctx, 415, failed("Send JSON."));
            return null;
        }
        try {
            JsonNode b = JSON.readTree(ctx.body());
            if (b == null || !b.isObject()) throw new IllegalArgumentException();
            return b;
        } catch (Exception e) {
            json(ctx, 400, failed("Expected a JSON object."));
            return null;
        }
    }

    private static String text(JsonNode b, String key, int max) {
        JsonNode n = b.get(key);
        if (n == null || n.isNull()) return "";
        if (!n.isTextual() || n.asText().length() > max) throw new IllegalArgumentException(key);
        return n.asText();
    }

    private void giveFeedback(Context ctx) throws Exception {
        if (feedback == null) { json(ctx, 404, failed("Feedback is turned off.")); return; }
        JsonNode b = jsonBody(ctx);
        if (b == null) return;
        if (!allow(limitKey(ctx))) { json(ctx, 429, failed("Too many requests. Please wait a moment.")); return; }
        Policy policy = runtime.get().policy();
        try {
            String rating = text(b, "rating", 10);
            String question = text(b, "question", policy.maxQuestionChars());
            if (question.isBlank()) throw new IllegalArgumentException("question");
            var item = feedback.add(who(ctx), question, text(b, "interpreted_question", policy.maxQuestionChars()),
                    text(b, "sql", policy.maxSqlChars()), rating, text(b, "comment", FeedbackStore.MAX_COMMENT_CHARS));
            json(ctx, 200, Map.of("status", "ok", "id", item.id()));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, failed("Feedback needs a question and a rating of up or down."));
        }
    }

    private boolean requireReviewer(Context ctx) {
        if (isReviewer(ctx)) return true;
        json(ctx, 403, failed("Only reviewers can do this."));
        return false;
    }

    private void review(Context ctx) throws Exception {
        if (!requireReviewer(ctx)) return;
        Runtime rt = runtime.get();
        List<Map<String, Object>> open = new ArrayList<>();
        if (feedback != null) {
            for (var i : feedback.open()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", i.id());
                m.put("at", i.at());
                m.put("user", i.user());
                m.put("question", i.question());
                m.put("interpreted_question", i.interpreted());
                m.put("sql", i.sql());
                m.put("rating", i.rating());
                m.put("comment", i.comment());
                open.add(m);
            }
        }
        List<Map<String, Object>> verified = new ArrayList<>();
        if (rt.verified() != null) {
            for (var a : rt.verified().list()) {
                verified.add(Map.of("id", a.id(), "question", a.question(), "sql", a.sql(), "verified_by", a.verifiedBy(),
                        "verified_at", a.verifiedAt(), "notes", a.notes()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feedback", open);
        out.put("verified", verified);
        out.put("verified_file", rt.verified() == null ? null : rt.verified().file().getFileName().toString());
        json(ctx, 200, out);
    }

    /** Runs SQL through the usual checks and the read-only user, for reviewers editing an answer. */
    private void trySql(Context ctx) throws Exception {
        if (!requireReviewer(ctx)) return;
        JsonNode b = jsonBody(ctx);
        if (b == null) return;
        Runtime rt = runtime.get();
        try {
            var vq = new SqlValidator(rt.policy()).validate(text(b, "sql", rt.policy().maxSqlChars()));
            var qr = new SafeExecutor(agentConnections, rt.policy()).execute(vq.sql());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("sql", vq.sql());
            out.put("columns", qr.columns());
            out.put("rows", qr.rows().subList(0, Math.min(20, qr.rows().size())));
            out.put("row_count", qr.rows().size());
            json(ctx, 200, out);
        } catch (SqlValidator.ValidationException | SafeExecutor.ExecutionException e) {
            json(ctx, 200, Map.of("status", "rejected", "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, failed("SQL is missing or too long."));
        }
    }

    /** Saves a question and its SQL as verified (after checking that the SQL passes and runs). */
    private void verify(Context ctx) throws Exception {
        if (!requireReviewer(ctx)) return;
        JsonNode b = jsonBody(ctx);
        if (b == null) return;
        Runtime rt = runtime.get();
        if (rt.verified() == null) { json(ctx, 404, failed("Verified answers aren't available here.")); return; }
        try {
            String question = text(b, "question", rt.policy().maxQuestionChars());
            String sql = text(b, "sql", rt.policy().maxSqlChars());
            if (question.isBlank() || sql.isBlank()) throw new IllegalArgumentException();
            var vq = new SqlValidator(rt.policy()).validate(sql);
            new SafeExecutor(agentConnections, rt.policy()).execute(vq.sql());
            var saved = rt.verified().add(question, sql, who(ctx), text(b, "notes", 500));
            String fid = text(b, "feedback_id", 40);
            if (!fid.isBlank() && feedback != null) feedback.resolve(fid, "verified", who(ctx));
            json(ctx, 200, Map.of("status", "ok", "id", saved.id()));
        } catch (SqlValidator.ValidationException | SafeExecutor.ExecutionException e) {
            json(ctx, 200, Map.of("status", "rejected", "message", "Not saved: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            json(ctx, 400, failed("A verified answer needs a question and its SQL."));
        }
    }

    private void dismiss(Context ctx) throws Exception {
        if (!requireReviewer(ctx)) return;
        JsonNode b = jsonBody(ctx);
        if (b == null) return;
        boolean ok = feedback != null && feedback.resolve(text(b, "feedback_id", 40), "dismissed", who(ctx));
        json(ctx, ok ? 200 : 404, ok ? Map.of("status", "ok") : failed("No such feedback."));
    }

    private void removeVerified(Context ctx) throws Exception {
        if (!requireReviewer(ctx)) return;
        JsonNode b = jsonBody(ctx);
        if (b == null) return;
        Runtime rt = runtime.get();
        boolean ok = rt.verified() != null && rt.verified().remove(text(b, "id", 40));
        json(ctx, ok ? 200 : 404, ok ? Map.of("status", "ok") : failed("No such verified answer."));
    }

    /** Sliding one-minute window per client. */
    synchronized boolean allow(String client) {
        long now = System.currentTimeMillis();
        Deque<Long> times = recentByClient.computeIfAbsent(client, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60_000) times.pollFirst();
        if (times.size() >= options.perClientPerMinute()) return false;
        times.addLast(now);
        return true;
    }

    static Map<String, Object> toJson(QueryWardenAgent.Result r, long elapsedMillis) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status().name().toLowerCase(Locale.ROOT));
        out.put("message", r.message());
        out.put("sql", r.sql());
        out.put("explanation", r.explanation());
        out.put("interpreted_question", r.interpretedQuestion() == null ? "" : r.interpretedQuestion());
        out.put("verified", r.verified() == null ? null
                : Map.of("kind", r.verified().kind(), "questions", r.verified().questions()));
        List<Map<String, String>> columns = new ArrayList<>();
        if (r.result() != null) {
            for (int i = 0; i < r.result().columns().size(); i++)
                columns.add(Map.of("name", r.result().columns().get(i), "type", r.result().columnTypes().get(i)));
        }
        out.put("columns", columns);
        out.put("rows", r.result() == null ? List.of() : r.result().rows());
        out.put("truncated", r.result() != null && r.result().truncated());
        out.put("warnings", r.warnings());
        out.put("attempts", r.attempts().size());
        List<Map<String, String>> rejected = new ArrayList<>();
        for (QueryWardenAgent.Attempt a : r.attempts()) {
            if (a.status().endsWith("_error")) rejected.add(Map.of("sql", a.sql(), "reason", a.detail()));
        }
        out.put("rejected_attempts", rejected);
        out.put("elapsed_ms", elapsedMillis);
        return out;
    }

    private static Map<String, Object> failed(String message) {
        return Map.of("status", "failed", "message", message);
    }

    private static void json(Context ctx, int status, Object body) {
        try {
            ctx.status(status).contentType("application/json; charset=utf-8").result(JSON.writeValueAsString(body));
        } catch (Exception e) {
            ctx.status(500).result("{\"status\":\"failed\",\"message\":\"Could not build the response.\"}");
        }
    }
}
