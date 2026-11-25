package dev.querywarden;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * "Correct" / "Wrong" feedback on answers, for reviewers. Append-only JSON lines in
 * {@code feedback.jsonl} (or {@code QW_FEEDBACK_FILE}): new feedback is one line, and resolving it
 * (verified or dismissed) adds another line, so concurrent writes never lose anything.
 */
public final class FeedbackStore {

    public record Item(String id, String at, String user, String question, String interpreted, String sql,
                       String rating, String comment, String status, String resolvedBy) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int MAX_COMMENT_CHARS = 1000;

    private final Path file;

    public FeedbackStore(Path file) {
        this.file = file;
    }

    public static FeedbackStore fromEnv() {
        String f = System.getenv("QW_FEEDBACK_FILE");
        return new FeedbackStore(Path.of(f == null || f.isBlank() ? "feedback.jsonl" : f));
    }

    public synchronized Item add(String user, String question, String interpreted, String sql, String rating,
                                 String comment) throws IOException {
        if (!"up".equals(rating) && !"down".equals(rating)) throw new IllegalArgumentException("rating must be up or down");
        byte[] raw = new byte[5];
        RANDOM.nextBytes(raw);
        String c = comment == null ? "" : comment.strip();
        if (c.length() > MAX_COMMENT_CHARS) c = c.substring(0, MAX_COMMENT_CHARS);
        Item item = new Item("f_" + HexFormat.of().formatHex(raw), Instant.now().toString(), user, question,
                interpreted == null ? "" : interpreted, sql == null ? "" : sql, rating, c, "open", "");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", item.id());
        line.put("at", item.at());
        line.put("user", user);
        line.put("question", question);
        line.put("interpreted", item.interpreted());
        line.put("sql", item.sql());
        line.put("rating", rating);
        line.put("comment", c);
        append(line);
        return item;
    }

    /** Marks feedback as dealt with: "verified" or "dismissed". */
    public synchronized boolean resolve(String id, String status, String by) throws IOException {
        if (all().stream().noneMatch(i -> i.id().equals(id))) return false;
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("id", id);
        line.put("at", Instant.now().toString());
        line.put("status", status);
        line.put("by", by);
        append(line);
        return true;
    }

    /** Every item with its latest status, newest first. */
    public synchronized List<Item> all() throws IOException {
        Map<String, Item> items = new LinkedHashMap<>();
        if (Files.exists(file)) {
            for (String l : Files.readAllLines(file)) {
                if (l.isBlank()) continue;
                JsonNode n = JSON.readTree(l);
                String id = n.path("id").asText();
                if (n.has("rating")) {
                    items.put(id, new Item(id, n.path("at").asText(), n.path("user").asText(), n.path("question").asText(),
                            n.path("interpreted").asText(""), n.path("sql").asText(""), n.path("rating").asText(),
                            n.path("comment").asText(""), "open", ""));
                } else if (items.containsKey(id)) {
                    Item i = items.get(id);
                    items.put(id, new Item(i.id(), i.at(), i.user(), i.question(), i.interpreted(), i.sql(), i.rating(),
                            i.comment(), n.path("status").asText(), n.path("by").asText("")));
                }
            }
        }
        List<Item> out = new ArrayList<>(items.values());
        out.sort(Comparator.comparing(Item::at).reversed());
        return out;
    }

    /** Open items for reviewers: "wrong" first, then "correct" (candidates to verify), newest first. */
    public synchronized List<Item> open() throws IOException {
        return all().stream().filter(i -> i.status().equals("open"))
                .sorted(Comparator.comparing((Item i) -> i.rating().equals("down") ? 0 : 1)
                        .thenComparing(Item::at, Comparator.reverseOrder()))
                .toList();
    }

    private void append(Map<String, Object> line) throws IOException {
        Files.writeString(file, JSON.writeValueAsString(line) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
