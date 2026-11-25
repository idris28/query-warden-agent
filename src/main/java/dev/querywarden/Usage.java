package dev.querywarden;

import java.util.LinkedHashMap;
import java.util.Map;

import com.anthropic.models.beta.messages.BetaMessage;

/**
 * Tokens and cost of one or more Claude calls. Cost is computed per call from the model that actually
 * answered (taken from the response, not from the configuration), so a fallback to another model is
 * priced correctly. {@code costUsd} is null when a model's price isn't known.
 */
public record Usage(String model, long inputTokens, long outputTokens, long cacheReadTokens,
                    long cacheWriteTokens, int calls, Double costUsd) {

    public static final Usage NONE = new Usage("", 0, 0, 0, 0, 0, 0.0);

    /** USD per million tokens {input, output}, first-party API, as of 2026-11. */
    static final Map<String, double[]> PRICES = Map.of(
            "claude-fable-5-2", new double[]{8.00, 40.00},
            "claude-opus-5-5", new double[]{4.00, 20.00},
            "claude-opus-5", new double[]{5.00, 25.00},
            "claude-opus-4-8", new double[]{5.00, 25.00},
            "claude-sonnet-5-5", new double[]{2.00, 10.00},
            "claude-sonnet-5", new double[]{2.00, 10.00},
            "claude-haiku-5-5", new double[]{0.10, 0.50});   // prompts up to 100K tokens

    public static Usage of(BetaMessage resp) {
        String model = resp.model().asString();
        var u = resp.usage();
        long in = u.inputTokens(), out = u.outputTokens();
        long read = u.cacheReadInputTokens().orElse(0L), write = u.cacheCreationInputTokens().orElse(0L);
        return new Usage(model, in, out, read, write, 1, cost(model, in, out, read, write));
    }

    static Double cost(String model, long in, long out, long read, long write) {
        double[] p = PRICES.get(model);
        if (p == null) return null;
        return (in * p[0] + write * p[0] * 1.25 + read * p[0] * 0.10 + out * p[1]) / 1_000_000.0;
    }

    /** Adds another call. The model reads "a+b" if calls were answered by different models. */
    public Usage plus(Usage o) {
        if (o == null || o.calls == 0) return this;
        if (calls == 0) return o;
        String m = model.equals(o.model) || model.contains(o.model) ? model : model + "+" + o.model;
        Double c = costUsd == null || o.costUsd == null ? null : costUsd + o.costUsd;
        return new Usage(m, inputTokens + o.inputTokens, outputTokens + o.outputTokens,
                cacheReadTokens + o.cacheReadTokens, cacheWriteTokens + o.cacheWriteTokens, calls + o.calls, c);
    }

    /** For the audit log and eval results. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("calls", calls);
        m.put("input_tokens", inputTokens);
        m.put("output_tokens", outputTokens);
        m.put("cache_read_input_tokens", cacheReadTokens);
        m.put("cache_creation_input_tokens", cacheWriteTokens);
        m.put("cost_usd", costUsd == null ? null : Math.round(costUsd * 1_000_000) / 1_000_000.0);
        return m;
    }
}
