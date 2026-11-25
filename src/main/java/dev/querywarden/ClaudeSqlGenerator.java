package dev.querywarden;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Calls Claude with structured outputs, so every reply is JSON matching {@link #SCHEMA}.
 * The conversation is append-only: the model's full turn is kept (via {@code toParam()}) and
 * error feedback is added as a new user turn.
 */
public final class ClaudeSqlGenerator implements SqlGenerator {

    public static final String MODEL = "claude-opus-5-5";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "answerable", Map.of("type", "boolean"),
                    "sql", Map.of("type", "string"),
                    "explanation", Map.of("type", "string"),
                    "reason", Map.of("type", "string")),
            "required", List.of("answerable", "sql", "explanation", "reason"),
            "additionalProperties", false);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnthropicClient client;
    private final String model;
    private final BetaOutputConfig.Effort effort;
    private final boolean useFallbacks;

    public ClaudeSqlGenerator(boolean useFallbacks) {
        this(AnthropicOkHttpClient.fromEnv(), MODEL, BetaOutputConfig.Effort.MEDIUM, useFallbacks);
    }

    public ClaudeSqlGenerator(AnthropicClient client, String model, BetaOutputConfig.Effort effort, boolean useFallbacks) {
        this.client = client;
        this.model = model;
        this.effort = effort;
        this.useFallbacks = useFallbacks;
    }

    @Override
    public Conversation open(String systemPrompt) {
        List<BetaMessageParam> history = new ArrayList<>();
        BetaJsonOutputFormat.Schema.Builder schema = BetaJsonOutputFormat.Schema.builder();
        SCHEMA.forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
        BetaOutputConfig outputConfig = BetaOutputConfig.builder()
                .effort(effort)
                .format(BetaJsonOutputFormat.builder().schema(schema.build()).build())
                .build();
        List<BetaTextBlockParam> system = List.of(BetaTextBlockParam.builder()
                .text(systemPrompt)
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .build());

        return userText -> {
            history.add(BetaMessageParam.ofUser(userText));
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(16000L)
                    .systemOfBetaTextBlockParams(system)
                    .outputConfig(outputConfig)
                    .messages(history);
            if (useFallbacks) {
                // On a safety-classifier decline, the API re-runs the request on Anthropic's
                // recommended fallback model inside the same call.
                params.addBeta("server-side-fallback-2026-07-01").fallbacks(BetaFallbacksParam.ofDefault());
            }
            BetaMessage resp = client.beta().messages().create(params.build());
            history.add(resp.toParam());

            BetaStopReason stop = resp.stopReason().orElse(null);
            if (BetaStopReason.REFUSAL.equals(stop)) {
                return new Output(false, "", "", "The model declined this request.");
            }
            if (BetaStopReason.MAX_TOKENS.equals(stop)) {
                throw new IllegalStateException("Model output was truncated (max_tokens).");
            }
            String text = resp.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(t -> t.text())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Model returned no text block."));
            try {
                JsonNode n = JSON.readTree(text);
                return new Output(n.path("answerable").asBoolean(false), n.path("sql").asText(""),
                        n.path("explanation").asText(""), n.path("reason").asText(""));
            } catch (Exception e) {
                throw new IllegalStateException("Model returned invalid JSON: " + e.getMessage(), e);
            }
        };
    }
}
