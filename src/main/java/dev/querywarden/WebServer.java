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
    public record Options(String host, int port, int maxInFlight, int perClientPerMinute) {
        public static Options defaults() {
            return new Options("127.0.0.1", 8080, 4, 10);
        }

        public Options withPort(int p) {
            return new Options(host, p, maxInFlight, perClientPerMinute);
        }
    }

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

    private final QueryWardenAgent agent;
    private final Policy policy;
    private final DataSource agentConnections;
    private final Supplier<List<Database.TableInfo>> schema;
    private final AiHelper ai;
    private final Examples examples;
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
        this.agent = agent;
        this.policy = policy;
        this.agentConnections = agentConnections;
        this.schema = schema;
        this.ai = ai;
        this.examples = examples;
        this.options = options;
        this.inFlight = new Semaphore(options.maxInFlight());
    }

    /** Builds and starts the server. Throws if the host isn't a loopback address. */
    public Javalin start() {
        requireLoopback(options.host());
        Javalin app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.jetty.host = options.host();
            config.jetty.port = options.port();
            config.http.maxRequestSize = 8_192;
            config.staticFiles.add("/web", Location.CLASSPATH);

            config.routes.before(this::guard);
            config.routes.get("/api/health", this::health);
            config.routes.get("/api/schema", this::schema);
            config.routes.post("/api/ask", this::ask);
            config.routes.post("/api/ask/stream", this::askStream);
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
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Database.TableInfo t : schema.get()) {
            List<Map<String, Object>> cols = new ArrayList<>();
            for (Database.ColumnInfo c : t.columns()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", c.name());
                col.put("type", c.type());
                col.put("note", c.note());
                col.put("masked", policy.isMasked(c.name()));
                cols.add(col);
            }
            tables.add(Map.of("name", t.name(), "columns", cols));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", tables);
        out.put("examples", examples.questions());
        out.put("examples_source", examples.source());
        out.put("shares_results_with_ai", policy.shareResultsWithAi());
        out.put("max_question_chars", policy.maxQuestionChars());
        out.put("max_rows", policy.maxRows());
        json(ctx, 200, out);
    }

    /** Reads {"question": "..."} and applies the rate and busy limits. Returns null after replying with an error. */
    private String admit(Context ctx) {
        String type = ctx.contentType() == null ? "" : ctx.contentType().toLowerCase(Locale.ROOT);
        if (!type.startsWith("application/json")) {
            json(ctx, 415, failed("Send the question as JSON."));
            return null;
        }
        String question;
        try {
            JsonNode body = JSON.readTree(ctx.body());
            JsonNode q = body == null ? null : body.get("question");
            if (q == null || !q.isTextual()) throw new IllegalArgumentException();
            question = q.asText();
        } catch (Exception e) {
            json(ctx, 400, failed("Expected {\"question\": \"...\"}."));
            return null;
        }
        if (!allow(ctx.ip())) {
            json(ctx, 429, failed("Too many questions. You can ask up to " + options.perClientPerMinute()
                    + " per minute; please wait a moment."));
            return null;
        }
        if (!inFlight.tryAcquire()) {
            json(ctx, 503, failed("QueryWarden is busy with other questions. Please try again in a few seconds."));
            return null;
        }
        return question;   // caller must release inFlight
    }

    private void ask(Context ctx) {
        String question = admit(ctx);
        if (question == null) return;
        String client = ctx.ip();
        long start = System.nanoTime();
        QueryWardenAgent.Result r;
        try {
            r = agent.ask(question, "web " + client);
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
        String question = admit(ctx);
        if (question == null) return;
        String client = ctx.ip();
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
            QueryWardenAgent.Result r = agent.ask(question, "web " + client,
                    p -> send.accept(Map.of("type", "progress", "stage", p.stage(), "text", p.text())));
            Map<String, Object> result = new LinkedHashMap<>(toJson(r, (System.nanoTime() - start) / 1_000_000));
            result.put("type", "result");
            send.accept(result);

            if (r.status() == QueryWardenAgent.Status.OK && ai != AiHelper.NONE) {
                boolean share = policy.shareResultsWithAi();
                send.accept(Map.of("type", "progress", "stage", "summarizing",
                        "text", share ? "Claude is summarising the result" : "Claude is suggesting follow-up questions"));
                AiHelper.Insight insight = ai.describe(Database.formatSchema(schema.get()), question, r.sql(),
                        r.result().columns(), share ? r.result().rows() : null);
                send.accept(Map.of("type", "insights", "summary", insight.summary(), "follow_ups", insight.followUps()));
            }
            send.accept(Map.of("type", "done"));
        } catch (java.io.UncheckedIOException e) {
            // the browser closed the connection; nothing more to send
        } finally {
            inFlight.release();
        }
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
