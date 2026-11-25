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
        if (!List.of("setup", "schema", "check", "ask", "repl", "policy", "serve").contains(cmd)) {
            System.out.println(USAGE);
            return;
        }

        Database.Config cfg = Database.Config.fromEnv();
        Policy policy;
        try {
            Policy.Loaded loaded = Policy.resolve(policyPath);
            policy = loaded.policy();
            System.err.println("policy: " + loaded.source());
        } catch (Policy.PolicyException e) {
            System.err.println("Invalid policy: " + e.getMessage());
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
                QueryWardenAgent agent = agent(cfg, policy, fallbacks);
                QueryWardenAgent.Result r = agent.ask(rest);
                print(r);
                System.exit(r.status() == QueryWardenAgent.Status.OK ? 0 : 1);
            }
            case "serve" -> {
                WebServer.Options opts = WebServer.Options.defaults();
                if (!rest.isBlank()) {
                    String[] parts = rest.trim().split("\\s+");
                    if (parts.length != 2 || !parts[0].equals("--port") || !parts[1].matches("\\d{2,5}")) {
                        System.err.println("usage: querywarden serve [--port N]");
                        System.exit(2);
                    }
                    opts = opts.withPort(Integer.parseInt(parts[1]));
                }
                com.zaxxer.hikari.HikariDataSource pool = cfg.agentPool(opts.maxInFlight() + 1);
                QueryWardenAgent agent = new QueryWardenAgent(cfg, pool, policy, new ClaudeSqlGenerator(fallbacks),
                        3, Path.of("audit.jsonl"));
                List<Database.TableInfo> tables = Database.schemaInfo(cfg, policy);
                AiHelper ai = new ClaudeAiHelper(fallbacks);
                if (policy.examples().isEmpty()) System.out.println("No examples in the policy; asking Claude to suggest some…");
                WebServer.Examples examples = WebServer.resolveExamples(policy, ai, Database.formatSchema(tables));
                System.out.println("Example questions: " + examples.questions().size() + " (from " + examples.source() + ")");
                System.out.println("Result summaries: " + (policy.shareResultsWithAi()
                        ? "on (up to 50 masked result rows are sent to Claude per answer)"
                        : "off (results stay on this computer; Claude only suggests follow-ups)"));
                new WebServer(agent, policy, pool, () -> tables, ai, examples, opts).start();
                System.out.println("QueryWarden is running at http://localhost:" + opts.port()
                        + "  (only reachable from this computer; Ctrl-C to stop)");
            }
            case "repl" -> {
                QueryWardenAgent agent = agent(cfg, policy, fallbacks);
                System.out.println("Ask a question about the database (Ctrl-D to exit).");
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                while (true) {
                    System.out.print("\n> ");
                    String line = in.readLine();
                    if (line == null) break;
                    if (!line.isBlank()) print(agent.ask(line.strip()));
                }
            }
            default -> System.out.println(USAGE);
        }
    }

    private static final String USAGE = """
            usage: querywarden <setup|schema|policy|ask|repl|check|serve> [text] [--policy FILE] [--no-fallbacks]
                   querywarden serve [--port N]    web page at http://localhost:8080
            env:   QW_DB_URL, QW_ADMIN_USER, QW_ADMIN_PASSWORD, QW_AGENT_USER, QW_AGENT_PASSWORD,
                   QW_POLICY, ANTHROPIC_API_KEY""";

    private static QueryWardenAgent agent(Database.Config cfg, Policy policy, boolean fallbacks) throws Exception {
        return new QueryWardenAgent(cfg, policy, new ClaudeSqlGenerator(fallbacks), 3, Path.of("audit.jsonl"));
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
