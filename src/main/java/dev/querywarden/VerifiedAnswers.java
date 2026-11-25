package dev.querywarden;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Question-and-SQL pairs that a reviewer has checked. Kept in a JSON file next to the policy
 * ({@code policy.yaml} → {@code policy.verified.json}), meant to be committed, and re-read when it
 * changes, so additions and removals apply at once.
 *
 * <p>Matching is by wording, offline: questions are reduced to their meaningful words (lower case,
 * filler words dropped, simple plural folding) and compared by TF-IDF cosine similarity. "Same question"
 * means the same meaningful words in the same order.
 */
public final class VerifiedAnswers {

    public record Answer(String id, String question, String sql, String verifiedBy, String verifiedAt, String notes) {}

    /** A verified answer and how close it is to the question asked (0..1). */
    public record Match(Answer answer, double score, boolean same) {}

    /** Similar enough to show Claude as an example. */
    public static final double EXAMPLE_THRESHOLD = 0.35;
    public static final int MAX_EXAMPLES = 3;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> FILLER = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "do", "does", "did", "of", "for", "to",
            "in", "on", "at", "by", "with", "and", "or", "me", "us", "we", "our", "you", "your", "it", "its",
            "there", "what", "whats", "which", "who", "how", "please", "show", "give", "list", "tell", "can",
            "could", "would", "i", "my", "this", "that", "these", "those", "all", "any", "have", "has", "had");

    private final Path file;
    private List<Answer> answers = List.of();
    private long loadedStamp = -1;

    public VerifiedAnswers(Path file) {
        this.file = file;
    }

    /** {@code policy.yaml} → {@code policy.verified.json} in the same folder; built-in defaults → verified.json. */
    public static VerifiedAnswers forPolicy(Path policyFile) {
        if (policyFile == null) return new VerifiedAnswers(Path.of("verified.json"));
        String name = policyFile.getFileName().toString().replaceFirst("\\.ya?ml$", "");
        Path dir = policyFile.toAbsolutePath().getParent();
        return new VerifiedAnswers(dir.resolve(name + ".verified.json"));
    }

    public Path file() {
        return file;
    }

    public synchronized List<Answer> list() {
        reload();
        return answers;
    }

    /** Best matches for a question, most similar first, at most {@code limit}. */
    public synchronized List<Match> find(String question, int limit) {
        reload();
        if (answers.isEmpty()) return List.of();
        List<String> q = words(question);
        if (q.isEmpty()) return List.of();
        // document frequency over the library, for TF-IDF
        Map<String, Integer> df = new HashMap<>();
        List<List<String>> docs = new ArrayList<>();
        for (Answer a : answers) {
            List<String> w = words(a.question());
            docs.add(w);
            for (String t : Set.copyOf(w)) df.merge(t, 1, Integer::sum);
        }
        int n = answers.size();
        Map<String, Double> qv = vector(q, df, n);
        List<Match> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double score = cosine(qv, vector(docs.get(i), df, n));
            boolean same = docs.get(i).equals(q);
            if (same) score = 1.0;
            out.add(new Match(answers.get(i), score, same));
        }
        out.sort(Comparator.comparingDouble(Match::score).reversed());
        return List.copyOf(out.subList(0, Math.min(limit, out.size())));
    }

    public synchronized Answer add(String question, String sql, String by, String notes) throws IOException {
        reload();
        if (question == null || question.isBlank() || sql == null || sql.isBlank())
            throw new IllegalArgumentException("A verified answer needs a question and its SQL.");
        byte[] raw = new byte[5];
        RANDOM.nextBytes(raw);
        Answer a = new Answer("v_" + HexFormat.of().formatHex(raw), question.strip(), sql.strip(),
                by == null ? "" : by, Instant.now().toString(), notes == null ? "" : notes.strip());
        List<Answer> next = new ArrayList<>();
        // the same question verified again replaces the older answer
        List<String> w = words(question);
        for (Answer old : answers) if (!words(old.question()).equals(w)) next.add(old);
        next.add(a);
        save(next);
        return a;
    }

    public synchronized boolean remove(String id) throws IOException {
        reload();
        List<Answer> next = answers.stream().filter(a -> !a.id().equals(id)).toList();
        if (next.size() == answers.size()) return false;
        save(next);
        return true;
    }

    // ------------------------------------------------------------------ text similarity
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.isEmpty() || FILLER.contains(t)) continue;
            if (t.length() > 4 && t.endsWith("ies")) t = t.substring(0, t.length() - 3) + "y";
            else if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) t = t.substring(0, t.length() - 1);
            out.add(t);
        }
        return out;
    }

    private static Map<String, Double> vector(List<String> words, Map<String, Integer> df, int n) {
        Map<String, Double> v = new HashMap<>();
        for (String w : words) v.merge(w, 1.0, Double::sum);
        v.replaceAll((w, tf) -> tf * (1.0 + Math.log((1.0 + n) / (1.0 + df.getOrDefault(w, 0)))));
        return v;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (var e : a.entrySet()) {
            na += e.getValue() * e.getValue();
            Double o = b.get(e.getKey());
            if (o != null) dot += e.getValue() * o;
        }
        for (double x : b.values()) nb += x * x;
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    /** SQL compared ignoring case, spacing and a trailing semicolon or LIMIT added by the checks. */
    public static String normaliseSql(String sql) {
        if (sql == null) return "";
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll(";\\s*$", "")
                .replaceAll("\\s+limit \\d+\\s*$", "").strip();
    }

    // ------------------------------------------------------------------ file
    private void reload() {
        try {
            if (!Files.exists(file)) {
                answers = List.of();
                loadedStamp = -1;
                return;
            }
            long stamp = Files.getLastModifiedTime(file).toMillis() ^ (Files.size(file) << 20);
            if (stamp == loadedStamp) return;
            JsonNode root = JSON.readTree(file.toFile());
            List<Answer> out = new ArrayList<>();
            for (JsonNode a : root.path("answers")) {
                out.add(new Answer(a.path("id").asText(), a.path("question").asText(), a.path("sql").asText(),
                        a.path("verified_by").asText(""), a.path("verified_at").asText(""), a.path("notes").asText("")));
            }
            answers = List.copyOf(out);
            loadedStamp = stamp;
        } catch (IOException e) {
            System.err.println("warning: couldn't read " + file + ": " + e.getMessage());
        }
    }

    private void save(List<Answer> next) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Answer a : next) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("question", a.question());
            m.put("sql", a.sql());
            m.put("verified_by", a.verifiedBy());
            m.put("verified_at", a.verifiedAt());
            m.put("notes", a.notes());
            rows.add(m);
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("answers", rows)) + "\n");
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        answers = List.copyOf(next);
        loadedStamp = Files.getLastModifiedTime(file).toMillis() ^ (Files.size(file) << 20);
    }
}
