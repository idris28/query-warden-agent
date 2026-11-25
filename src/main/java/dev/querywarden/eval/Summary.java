package dev.querywarden.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

/** Prints the numbers for one variant, or a side-by-side comparison of all variants. */
final class Summary {

    private Summary() {}

    record Stats(String variant, String model, String effort, int n, double correct, double lo, double hi,
                 int leaks, int errors, double callsPerCase, int rejected, long medianTokens, long minTokens,
                 long maxTokens, double costPerCase, double totalCost, double medianLatency, double p90Latency,
                 double wallClock, Map<String, double[]> byTag, int fallbacks, int overAsked) {}

    static Stats stats(Path dir) throws IOException {
        List<JsonNode> rows = readJsonl(dir.resolve("results.jsonl"));
        List<JsonNode> errs = readJsonl(dir.resolve("errors.jsonl"));
        JsonNode run = Files.exists(dir.resolve("run.json")) ? EvalRunner.JSON.readTree(dir.resolve("run.json").toFile()) : null;
        int n = rows.size();
        double correct = 0, calls = 0, cost = 0;
        int leaks = 0, rejected = 0, fallbacks = 0, overAsked = 0;
        List<Long> tokens = new ArrayList<>();
        List<Double> latency = new ArrayList<>();
        Map<String, double[]> byTag = new TreeMap<>();   // tag -> {correct, n}
        for (JsonNode r : rows) {
            double c = r.path("grade").path("correct").asDouble();
            correct += c;
            if (r.path("grade").path("no_leak").asInt(1) == 0) leaks++;
            calls += r.path("meta").path("claude_calls").asDouble();
            rejected += r.path("meta").path("rejected_queries").asInt();
            if (r.path("meta").path("answered_by_fallback").asBoolean(false)) fallbacks++;
            if ("clarify".equals(r.path("meta").path("agent_status").asText())
                    && !"clarify".equals(r.path("meta").path("expected").asText("answer"))) overAsked++;
            cost += r.path("meta").path("cost_usd").asDouble();
            JsonNode u = r.path("usage");
            tokens.add(u.path("input_tokens").asLong() + u.path("output_tokens").asLong()
                    + u.path("cache_read_input_tokens").asLong() + u.path("cache_creation_input_tokens").asLong());
            latency.add(r.path("latency_s").asDouble());
            String tag = r.path("tags").path(0).asText("untagged");
            double[] t = byTag.computeIfAbsent(tag, k -> new double[2]);
            t[0] += c;
            t[1] += 1;
        }
        for (JsonNode e : errs) cost += e.path("usage").path("cost_usd").asDouble();   // failed calls still cost
        tokens.sort(null);
        latency.sort(null);
        double p = n == 0 ? 0 : correct / n;
        double[] ci = wilson(correct, n);
        return new Stats(dir.getFileName().toString(),
                run == null ? "?" : run.path("sql_model").asText(), run == null ? "?" : run.path("sql_effort").asText(),
                n, p, ci[0], ci[1], leaks, errs.size(), n == 0 ? 0 : calls / n, rejected,
                tokens.isEmpty() ? 0 : tokens.get(tokens.size() / 2), tokens.isEmpty() ? 0 : tokens.get(0),
                tokens.isEmpty() ? 0 : tokens.get(tokens.size() - 1), n == 0 ? 0 : cost / n, cost,
                latency.isEmpty() ? 0 : latency.get(latency.size() / 2),
                latency.isEmpty() ? 0 : latency.get((int) Math.min(latency.size() - 1, Math.ceil(latency.size() * 0.9) - 1)),
                run == null ? 0 : run.path("wall_clock_s").asDouble(), byTag, fallbacks, overAsked);
    }

    /** 95% Wilson interval for a pass rate. */
    static double[] wilson(double passes, int n) {
        if (n == 0) return new double[]{0, 0};
        double z = 1.96, p = passes / n, d = 1 + z * z / n;
        double centre = (p + z * z / (2.0 * n)) / d;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d;
        return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    static void print(Path dir) throws IOException {
        Stats s = stats(dir);
        System.out.printf("%n%s  (%s, %s effort)%n", s.variant(), s.model(), s.effort());
        System.out.printf("  Correct:   %.0f%% (%d of %d)   95%% range %.0f–%.0f%%%n",
                s.correct() * 100, Math.round(s.correct() * s.n()), s.n(), s.lo() * 100, s.hi() * 100);
        s.byTag().forEach((tag, t) -> System.out.printf("    %-10s %.0f/%.0f%n", tag, t[0], t[1]));
        System.out.printf("  Leaks:     %d     Technical failures (not scored): %d     Answered by refusal fallback: %d%n",
                s.leaks(), s.errors(), s.fallbacks());
        if (s.overAsked() > 0)
            System.out.printf("  Asked a clarifying question when it should have answered: %d%n", s.overAsked());
        System.out.printf("  Claude calls per question: %.2f   rejected queries corrected: %d%n", s.callsPerCase(), s.rejected());
        System.out.printf("  Tokens per question: median %,d (min %,d, max %,d)%n", s.medianTokens(), s.minTokens(), s.maxTokens());
        System.out.printf("  Cost: $%.4f per question, $%.2f for this run%n", s.costPerCase(), s.totalCost());
        System.out.printf("  Time per question: median %.1f s, 90th percentile %.1f s   (whole run %.0f s)%n",
                s.medianLatency(), s.p90Latency(), s.wallClock());
    }

    static void compare(Path flow) throws IOException {
        List<Stats> all = new ArrayList<>();
        try (var dirs = Files.list(flow)) {
            for (Path d : dirs.sorted().toList()) {
                String name = d.getFileName().toString();
                if (Files.isDirectory(d) && name.matches("baseline|v\\d+") && Files.exists(d.resolve("results.jsonl")))
                    all.add(stats(d));
            }
        }
        all.sort((a, b) -> a.variant().equals("baseline") ? -1 : b.variant().equals("baseline") ? 1 : a.variant().compareTo(b.variant()));
        System.out.printf("%-9s %-18s %-7s %5s %-13s %5s %6s %7s %9s %9s %8s%n",
                "variant", "model", "effort", "n", "correct", "leaks", "calls", "tokens", "$/question", "$/run", "median s");
        for (Stats s : all) {
            System.out.printf("%-9s %-18s %-7s %5d %3.0f%% (%2.0f–%2.0f) %5d %6.2f %,7d %9.4f %9.2f %8.1f%n",
                    s.variant(), s.model(), s.effort(), s.n(), s.correct() * 100, s.lo() * 100, s.hi() * 100,
                    s.leaks(), s.callsPerCase(), s.medianTokens(), s.costPerCase(), s.totalCost(), s.medianLatency());
        }
    }

    static List<JsonNode> readJsonl(Path file) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        for (String line : Files.readAllLines(file)) if (!line.isBlank()) out.add(EvalRunner.JSON.readTree(line));
        return out;
    }
}
