package dev.querywarden;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * QueryWarden CLI.
 * <pre>
 *   querywarden setup                      create schema, sample data and the least-privilege agent role
 *   querywarden ask "top 5 customers by revenue"
 *   querywarden repl                       interactive mode
 *   querywarden check "SELECT ..."         run guardrails + execution on raw SQL (no LLM)
 *   querywarden schema                     print the schema the model sees
 *   querywarden policy                     print the policy in effect
 *   querywarden grants                     print the SQL for the read-only database user (never runs it)
 *   querywarden doctor                     check the database user's real permissions against the policy
 *   querywarden serve [--port N]           web page on http://localhost:8080 (this computer only)
 * </pre>
 * Options: --policy FILE (default: $QW_POLICY, then ./policy.yaml, then built-in defaults),
 *          --no-fallbacks (disable server-side refusal fallbacks)
 */
public final class Main {

    public static void main(String[] argv) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(argv));
        boolean fallbacks = !args.remove("--no-fallbacks");
        String policyPath = null;
        int i = args.indexOf("--policy");
        if (i >= 0) {
            if (i + 1 >= args.size()) {
                System.err.println("--policy needs a file path, e.g. --policy policy.yaml");
                System.exit(4);
            }
            policyPath = args.get(i + 1);
            args.subList(i, i + 2).clear();
        }
        String cmd = args.isEmpty() ? "help" : args.remove(0);
        String rest = String.join(" ", args);
        if (!List.of("setup", "schema", "check", "ask", "repl", "policy", "serve", "eval", "grants", "doctor", "users", "verified", "feedback").contains(cmd)) {
            System.out.println(USAGE);
            return;
        }

        Database.Config cfg = Database.Config.fromEnv();
        Policy policy;
        String policySource;
        Path policyFile;
        try {
            Policy.Loaded loaded = Policy.resolve(policyPath);
            policy = loaded.policy();
            policySource = loaded.source();
            policyFile = loaded.path();
            System.err.println("policy: " + policySource);
        } catch (Policy.PolicyException e) {
            System.err.println("Invalid policy: " + e.getMessage());
            System.exit(4);
            return;
        }
        ModelSettings models;
        try {
            models = ModelSettings.fromEnv();
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid model setting: " + e.getMessage());
            System.exit(4);
            return;
        }

        switch (cmd) {
            case "policy" -> System.out.print(policy.toYaml());
            case "setup" -> {
                try {
                    Database.setup(cfg, policy);
                } catch (Database.SetupRefusedException e) {
                    System.err.println(e.getMessage());
                    System.exit(3);
                }
                System.out.println("Schema '" + policy.schema() + "' seeded; role '" + cfg.agentUser() + "' granted read access.");
            }
            case "schema" -> System.out.println(Database.describeSchema(cfg, policy));
            case "check" -> System.exit(check(cfg, policy, rest));
            case "ask" -> {
                QueryWardenAgent agent = agent(cfg, policy, fallbacks).withVerified(VerifiedAnswers.forPolicy(policyFile));
                QueryWardenAgent.Result r = agent.ask(rest);
                print(r);
                System.exit(r.status() == QueryWardenAgent.Status.OK ? 0 : 1);
            }
            case "serve" -> {
                WebServer.Options opts = WebServer.Options.defaults();
                boolean share = false;
                String host = null;
                String[] parts = rest.isBlank() ? new String[0] : rest.trim().split("\\s+");
                for (int k = 0; k < parts.length; k++) {
                    if (parts[k].equals("--share")) share = true;
                    else if (parts[k].equals("--port") && k + 1 < parts.length && parts[k + 1].matches("\\d{2,5}"))
                        opts = opts.withPort(Integer.parseInt(parts[++k]));
                    else if (parts[k].equals("--host") && k + 1 < parts.length) host = parts[++k];
                    else {
                        System.err.println("usage: querywarden serve [--port N] [--share [--host ADDRESS]]");
                        System.exit(2);
                    }
                }
                if (host != null && !share) {
                    System.err.println("--host needs --share: without sign-in the page only runs on this computer.");
                    System.exit(2);
                }
                if (share) {
                    UserStore users = UserStore.fromEnv();
                    if (users.list().isEmpty()) {
                        System.err.println("Sharing needs at least one person. Add one first:  querywarden users add NAME");
                        System.exit(2);
                    }
                    opts = opts.shared(host == null ? "0.0.0.0" : host, users);
                    System.out.println("Sharing: sign-in required (" + users.list().size() + " person(s) in " + users.file() + ").");
                    System.out.println("Warning: this is plain HTTP, so access tokens travel unencrypted. Use it only on a "
                            + "network you trust, or put an HTTPS proxy (for example Caddy) in front.");
                }
                com.zaxxer.hikari.HikariDataSource pool = cfg.agentPool(opts.maxInFlight() + 1);
                AiHelper ai = new ClaudeAiHelper(models, fallbacks);
                System.out.println("Models: SQL " + models.sqlModel() + " (" + models.sqlEffort() + " effort), helper "
                        + models.helperModel() + " (" + models.helperEffort() + " effort)");
                if (policy.examples().isEmpty())
                    System.out.println("Example questions: written by Claude from the tables (cached in .querywarden/)…");
                // Policy file and database are re-checked every 30 s; changes apply without a restart.
                LiveRuntime live = new LiveRuntime(cfg, pool, policyPath, new ClaudeSqlGenerator(models, fallbacks), ai,
                        Path.of("audit.jsonl"), Path.of(".querywarden", "examples.json"));
                WebServer.Examples examples = live.get().examples();
                System.out.println("Example questions: " + examples.questions().size() + " (from " + examples.source() + ")");
                System.out.println("Result summaries: " + (policy.shareResultsWithAi()
                        ? "on (up to 50 masked result rows are sent to Claude per answer)"
                        : "off (results stay on this computer; Claude only suggests follow-ups)"));
                live.start(30);
                System.out.println("Live reload: on (policy.yaml and the database are checked every 30 seconds).");
                new WebServer(live, pool, ai, opts)
                        .withInfo(policySource, models, fallbacks)
                        .withLive(live::status, live::refreshExamples)
                        .withFeedback(FeedbackStore.fromEnv())
                        .start();
                System.out.println(opts.sharing()
                        ? "QueryWarden is running on " + opts.host() + ":" + opts.port()
                                + ". Colleagues open http://<this computer's address>:" + opts.port() + " and sign in. Ctrl-C to stop."
                        : "QueryWarden is running at http://localhost:" + opts.port()
                                + "  (only reachable from this computer; Ctrl-C to stop)");
            }
            case "eval" -> {
                dev.querywarden.eval.EvalRunner.main(args.toArray(String[]::new), cfg, policy, models, fallbacks);
                System.exit(0);   // don't wait for idle HTTP client threads
            }
            case "grants" -> {
                // Printed for review, never executed. Needs an admin connection to read the real columns.
                try (java.sql.Connection admin = cfg.admin();
                     java.sql.PreparedStatement ps = admin.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
                    ps.setString(1, cfg.agentUser());
                    boolean exists;
                    try (java.sql.ResultSet rs = ps.executeQuery()) { exists = rs.next(); }
                    Grants.Plan plan = Grants.plan(admin, policy, cfg.agentUser(), "CHANGE-ME", exists);
                    System.out.print(Grants.script(plan, policy, cfg.agentUser(), admin.getCatalog()));
                    plan.warnings().forEach(w -> System.err.println("warning: " + w));
                }
            }
            case "users" -> {
                UserStore users = UserStore.fromEnv();
                String[] parts = rest.isBlank() ? new String[0] : rest.trim().split("\\s+");
                String sub = parts.length == 0 ? "list" : parts[0];
                switch (sub) {
                    case "add" -> {
                        boolean reviewer = List.of(parts).contains("--reviewer");
                        parts = java.util.Arrays.stream(parts).filter(x -> !x.equals("--reviewer")).toArray(String[]::new);
                        if (parts.length != 2) { System.err.println("usage: querywarden users add NAME [--reviewer]"); System.exit(2); }
                        try {
                            String token = users.add(parts[1], reviewer);
                            System.out.println("Added '" + parts[1] + "'. Their access token (shown only now, give it to them privately):");
                            System.out.println();
                            System.out.println("  " + token);
                            System.out.println();
                            System.out.println("Only a fingerprint is stored in " + users.file() + ". Lost tokens can't be recovered: "
                                    + "remove the person and add them again.");
                        } catch (IllegalArgumentException e) {
                            System.err.println(e.getMessage());
                            System.exit(2);
                        }
                    }
                    case "remove" -> {
                        if (parts.length != 2) { System.err.println("usage: querywarden users remove NAME"); System.exit(2); }
                        boolean removed = users.remove(parts[1]);
                        System.out.println(removed ? "Removed '" + parts[1] + "'. Their token stops working immediately."
                                : "No person called '" + parts[1] + "'.");
                    }
                    case "list" -> {
                        var list = users.list();
                        if (list.isEmpty()) System.out.println("Nobody yet. Add someone with: querywarden users add NAME");
                        list.forEach(u -> System.out.println(u.name() + (u.reviewer() ? "   (reviewer)" : "") + "   added " + u.created()));
                    }
                    default -> { System.err.println("usage: querywarden users [list | add NAME | remove NAME]"); System.exit(2); }
                }
            }
            case "verified" -> {
                VerifiedAnswers lib = VerifiedAnswers.forPolicy(policyFile);
                String[] parts = rest.isBlank() ? new String[0] : rest.trim().split("\\s+");
                String sub = parts.length == 0 ? "list" : parts[0];
                switch (sub) {
                    case "list" -> {
                        var list = lib.list();
                        System.out.println(list.size() + " verified answer(s) in " + lib.file());
                        for (var a : list) {
                            System.out.println();
                            System.out.println(a.id() + "  " + a.question() + "   (by " + a.verifiedBy() + ", " + a.verifiedAt() + ")");
                            System.out.println("    " + a.sql().replaceAll("\\s+", " "));
                        }
                    }
                    case "remove" -> {
                        if (parts.length != 2) { System.err.println("usage: querywarden verified remove ID"); System.exit(2); }
                        System.out.println(lib.remove(parts[1]) ? "Removed " + parts[1] + "." : "No verified answer " + parts[1] + ".");
                    }
                    case "to-eval" -> {
                        // Evaluation cases from verified answers, to paste into eval/cases.yaml.
                        System.out.println("  # ---------------------------------------------------------------- from verified answers");
                        for (var a : lib.list()) {
                            System.out.println("  - id: verified-" + a.id().replace("v_", ""));
                            System.out.println("    tags: [verified]");
                            System.out.println("    question: " + com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                                    .writeValueAsString(a.question()));
                            System.out.println("    reference:");
                            System.out.println("      - |");
                            for (String l : a.sql().strip().split("\n")) System.out.println("        " + l);
                        }
                    }
                    default -> { System.err.println("usage: querywarden verified [list | remove ID | to-eval]"); System.exit(2); }
                }
            }
            case "feedback" -> {
                FeedbackStore store = FeedbackStore.fromEnv();
                boolean all = rest.trim().equals("--all");
                var items = all ? store.all() : store.open();
                System.out.println(items.size() + (all ? " feedback item(s)" : " open feedback item(s)") + ":");
                for (var item : items) {
                    System.out.println();
                    System.out.println((item.rating().equals("down") ? "WRONG  " : "RIGHT  ") + item.question() + "   ("
                            + item.user() + ", " + item.at() + (all ? ", " + item.status() : "") + ")");
                    if (!item.comment().isBlank()) System.out.println("    comment: " + item.comment());
                    if (!item.sql().isBlank()) System.out.println("    sql: " + item.sql().replaceAll("\\s+", " "));
                }
            }
            case "doctor" -> {
                List<Doctor.Finding> findings = Doctor.check(cfg, policy);
                findings.forEach(System.out::println);
                long fails = findings.stream().filter(f -> f.level() == Doctor.Level.FAIL).count();
                long warns = findings.stream().filter(f -> f.level() == Doctor.Level.WARN).count();
                System.out.println();
                System.out.println(fails == 0
                        ? "OK: the database user matches the policy" + (warns > 0 ? " (" + warns + " warning(s))." : ".")
                        : fails + " problem(s): the database would allow things the policy forbids. Fix them before use.");
                System.exit(fails == 0 ? 0 : 1);
            }
            case "repl" -> {
                QueryWardenAgent agent = agent(cfg, policy, fallbacks).withVerified(VerifiedAnswers.forPolicy(policyFile));
                System.out.println("Ask a question about the database. Follow-ups like \"now only Germany\" use the "
                        + "earlier answers; type 'new' to start over. Ctrl-D to exit.");
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                List<QueryWardenAgent.Turn> history = new ArrayList<>();
                while (true) {
                    System.out.print("\n> ");
                    String line = in.readLine();
                    if (line == null) break;
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    if (line.equalsIgnoreCase("new")) {
                        history.clear();
                        System.out.println("Started a new conversation.");
                        continue;
                    }
                    QueryWardenAgent.Result r = agent.ask(line, "cli", p -> { }, history);
                    print(r);
                    if (r.status() == QueryWardenAgent.Status.OK) history.add(new QueryWardenAgent.Turn(line, r.sql()));
                }
            }
            default -> System.out.println(USAGE);
        }
    }

    private static final String USAGE = """
            usage: querywarden <setup|schema|policy|ask|repl|check|serve> [text] [--policy FILE] [--no-fallbacks]
                   querywarden serve [--port N]    web page at http://localhost:8080 (this computer only)
                   querywarden serve --share [--host ADDRESS] [--port N]   share with colleagues (sign-in required)
                   querywarden users [list | add NAME [--reviewer] | remove NAME]   people who may sign in
                   querywarden verified [list | remove ID | to-eval]          checked question-and-SQL pairs
                   querywarden feedback [--all]                               "Correct"/"Wrong" feedback to review
                   querywarden eval --check | --self-test | --variant NAME [--reps N] | --compare
                   querywarden grants              SQL for the read-only database user (review, then run)
                   querywarden doctor              check the database user's real permissions against the policy
            env:   QW_DB_URL, QW_ADMIN_USER, QW_ADMIN_PASSWORD, QW_AGENT_USER, QW_AGENT_PASSWORD,
                   QW_POLICY, ANTHROPIC_API_KEY""";

    private static QueryWardenAgent agent(Database.Config cfg, Policy policy, boolean fallbacks) throws Exception {
        return new QueryWardenAgent(cfg, policy, new ClaudeSqlGenerator(ModelSettings.fromEnv(), fallbacks), 3,
                Path.of("audit.jsonl"));
    }

    private static int check(Database.Config cfg, Policy policy, String sql) {
        try {
            SqlValidator.ValidatedQuery vq = new SqlValidator(policy).validate(sql);
            SafeExecutor.QueryResult r = new SafeExecutor(cfg, policy).execute(vq.sql());
            System.out.println("SQL: " + vq.sql() + "\n");
            System.out.println(table(r));
            return 0;
        } catch (SqlValidator.ValidationException | SafeExecutor.ExecutionException e) {
            System.out.println("BLOCKED " + e.getMessage());
            return 2;
        }
    }

    static void print(QueryWardenAgent.Result r) {
        int i = 1;
        for (QueryWardenAgent.Attempt a : r.attempts()) {
            if (!a.status().equals("ok")) System.out.println("  attempt " + i + ": " + a.status() + " " + a.detail());
            i++;
        }
        if (r.status() != QueryWardenAgent.Status.OK) {
            System.out.println("[" + r.status() + "] " + r.message());
            return;
        }
        if (r.interpretedQuestion() != null && !r.interpretedQuestion().isBlank()
                && !r.interpretedQuestion().equalsIgnoreCase(r.question()))
            System.out.println("Understood as: " + r.interpretedQuestion());
        System.out.println("SQL: " + r.sql());
        if (!r.explanation().isBlank()) System.out.println("-- " + r.explanation());
        System.out.println();
        System.out.println(table(r.result()));
        System.out.printf("%n(%d rows, %d ms)%n", r.result().rows().size(), r.result().elapsedMillis());
        r.warnings().forEach(w -> System.out.println("note: " + w));
    }

    static String table(SafeExecutor.QueryResult r) {
        List<List<String>> cells = new ArrayList<>();
        cells.add(r.columns());
        for (List<Object> row : r.rows().subList(0, Math.min(50, r.rows().size()))) {
            cells.add(row.stream().map(v -> {
                String s = v == null ? "NULL"
                        : v instanceof java.math.BigDecimal bd ? bd.stripTrailingZeros().toPlainString() : v.toString();
                return s.length() > 28 ? s.substring(0, 27) + "…" : s;
            }).toList());
        }
        int[] w = new int[r.columns().size()];
        for (List<String> row : cells) for (int i = 0; i < w.length; i++) w[i] = Math.max(w[i], row.get(i).length());
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < cells.size(); k++) {
            List<String> row = cells.get(k);
            for (int i = 0; i < w.length; i++) {
                if (i > 0) sb.append(" | ");
                sb.append(String.format("%-" + Math.max(1, w[i]) + "s", row.get(i)));
            }
            sb.append('\n');
            if (k == 0) {
                for (int i = 0; i < w.length; i++) sb.append(i > 0 ? "-+-" : "").append("-".repeat(w[i]));
                sb.append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }
}
