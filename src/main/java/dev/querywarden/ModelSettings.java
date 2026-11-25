package dev.querywarden;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.anthropic.models.beta.messages.BetaOutputConfig;

/**
 * Which Claude model and effort level each job uses. Read from environment variables, so they can be
 * changed without rebuilding:
 * <ul>
 *   <li>{@code QW_SQL_MODEL} / {@code QW_SQL_EFFORT}: writing the SQL (default Claude Sonnet 5.5, medium);
 *       accuracy matters most here, so check any change with the evaluation</li>
 *   <li>{@code QW_HELPER_MODEL} / {@code QW_HELPER_EFFORT}: example questions, summaries and follow-ups
 *       (default Claude Haiku 5.5, low); simple jobs where speed and cost matter more</li>
 * </ul>
 * Model choice isn't a guardrail, so it lives here rather than in policy.yaml.
 */
public record ModelSettings(String sqlModel, String sqlEffort, String helperModel, String helperEffort) {

    /**
     * Claude Sonnet 5.5: in the 2026-11-25 evaluation it matched Claude Opus 5.5 on all 34 questions
     * at less than half the cost per question. Set QW_SQL_MODEL=claude-opus-5-5 to go back.
     */
    public static final String DEFAULT_SQL_MODEL = "claude-sonnet-5-5";
    public static final String DEFAULT_HELPER_MODEL = "claude-haiku-5-5";
    static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");
    private static final Pattern MODEL_ID = Pattern.compile("claude-[a-z0-9][a-z0-9.-]*");

    /**
     * Models that accept the server-side refusal fallback ({@code fallbacks: "default"}). Others, such as
     * Claude Haiku 5.5, have none and must not be sent the parameter.
     */
    static final Set<String> SERVER_FALLBACK_MODELS = Set.of(
            "claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");

    public static ModelSettings defaults() {
        return new ModelSettings(DEFAULT_SQL_MODEL, "medium", DEFAULT_HELPER_MODEL, "low");
    }

    public static ModelSettings fromEnv() {
        return from(System::getenv);
    }

    /** Reads the four settings through {@code env}; blank or missing values keep the defaults. */
    static ModelSettings from(Function<String, String> env) {
        ModelSettings d = defaults();
        return new ModelSettings(
                model(env, "QW_SQL_MODEL", d.sqlModel()),
                effort(env, "QW_SQL_EFFORT", d.sqlEffort()),
                model(env, "QW_HELPER_MODEL", d.helperModel()),
                effort(env, "QW_HELPER_EFFORT", d.helperEffort()));
    }

    public static ModelSettings fromMap(Map<String, String> values) {
        return from(values::get);
    }

    private static String model(Function<String, String> env, String key, String def) {
        String v = env.apply(key);
        if (v == null || v.isBlank()) return def;
        v = v.strip();
        if (!MODEL_ID.matcher(v).matches())
            throw new IllegalArgumentException(key + "='" + v + "' doesn't look like a Claude model id (e.g. "
                    + DEFAULT_SQL_MODEL + ").");
        return v;
    }

    private static String effort(Function<String, String> env, String key, String def) {
        String v = env.apply(key);
        if (v == null || v.isBlank()) return def;
        v = v.strip().toLowerCase(Locale.ROOT);
        if (!EFFORTS.contains(v))
            throw new IllegalArgumentException(key + "='" + v + "' must be one of low, medium, high, xhigh, max.");
        return v;
    }

    public static boolean supportsServerFallback(String model) {
        return SERVER_FALLBACK_MODELS.contains(model);
    }

    static BetaOutputConfig.Effort effortValue(String effort) {
        return switch (effort) {
            case "low" -> BetaOutputConfig.Effort.LOW;
            case "high" -> BetaOutputConfig.Effort.HIGH;
            case "xhigh" -> BetaOutputConfig.Effort.XHIGH;
            case "max" -> BetaOutputConfig.Effort.MAX;
            default -> BetaOutputConfig.Effort.MEDIUM;
        };
    }
}
