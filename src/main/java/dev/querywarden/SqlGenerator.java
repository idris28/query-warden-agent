package dev.querywarden;

/** Produces SQL for a question. One {@link Conversation} per question so repairs keep context. */
public interface SqlGenerator {

    /** What the model returns for each turn (structured output). */
    record Output(boolean answerable, String sql, String explanation, String reason) {}

    interface Conversation {
        /** Send the next user turn (the question, or error feedback) and get the model's answer. */
        Output send(String userText);
    }

    Conversation open(String systemPrompt);
}
