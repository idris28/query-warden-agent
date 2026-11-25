package dev.querywarden;

import java.util.List;

/**
 * Extra AI help around the main question → SQL loop, used by the web page. None of it can run SQL:
 * suggested questions only become queries if the user clicks them, and then they go through every
 * guardrail like any other question.
 *
 * <p>Implementations must never throw: on any problem they return an empty result, so a failure here
 * never breaks an answer.
 */
public interface AiHelper {

    /** A one-sentence summary (empty when results weren't shared) and suggested next questions. */
    record Insight(String summary, List<String> followUps, Usage usage) {
        public static final Insight NONE = new Insight("", List.of(), Usage.NONE);

        public Insight {
            if (usage == null) usage = Usage.NONE;
        }

        public Insight(String summary, List<String> followUps) {
            this(summary, followUps, Usage.NONE);
        }
    }

    /** Example questions that fit the schema the agent can see. */
    List<String> suggestExamples(String schemaText, int count);

    /**
     * Summary and follow-up questions for an answer.
     *
     * @param rows the result rows (already masked), or {@code null} when the policy doesn't allow
     *             sharing results; then only follow-ups are suggested, from the question, SQL and columns
     */
    Insight describe(String schemaText, String question, String sql, List<String> columns, List<List<Object>> rows);

    /** An AiHelper that does nothing (tests, or when AI help is turned off). */
    AiHelper NONE = new AiHelper() {
        @Override
        public List<String> suggestExamples(String schemaText, int count) {
            return List.of();
        }

        @Override
        public Insight describe(String schemaText, String question, String sql, List<String> columns,
                                List<List<Object>> rows) {
            return Insight.NONE;
        }
    };
}
