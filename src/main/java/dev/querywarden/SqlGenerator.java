package dev.querywarden;

/** Produces SQL for a question. One {@link Conversation} per question so repairs keep context. */
public interface SqlGenerator {

    /** What the model returns for each turn (structured output). */
    /** One model turn. {@code usage} is the tokens and cost of that call ({@link Usage#NONE} if unknown). */
    record Output(boolean answerable, String sql, String explanation, String reason, Usage usage,
                  String interpretedQuestion) {
        public Output {
            if (usage == null) usage = Usage.NONE;
            if (interpretedQuestion == null) interpretedQuestion = "";
        }

        public Output(boolean answerable, String sql, String explanation, String reason) {
            this(answerable, sql, explanation, reason, Usage.NONE, "");
        }

        public Output(boolean answerable, String sql, String explanation, String reason, Usage usage) {
            this(answerable, sql, explanation, reason, usage, "");
        }
    }

    /**
     * The SQL writer failed (network, authentication, rate limit, …). {@link #userMessage()} is safe
     * to show to anyone; the full detail is in {@link #getMessage()} and goes only to the audit log.
     */
    final class GeneratorException extends RuntimeException {
        private final String userMessage;

        public GeneratorException(String userMessage, String detail, Throwable cause) {
            super(detail, cause);
            this.userMessage = userMessage;
        }

        public String userMessage() { return userMessage; }
    }

    interface Conversation {
        /** Send the next user turn (the question, or error feedback) and get the model's answer. */
        Output send(String userText);
    }

    Conversation open(String systemPrompt);
}
