package dev.querywarden;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Changes {@code policy.yaml} from the web page. Only the top-level sections that change are rewritten
 * (everything else, comments included, stays as it was), the result must pass the same checks as a
 * hand-edited file, every save keeps a backup that "Undo" restores, and each change is logged.
 */
public final class PolicyEditor {

    /** Sections reviewers may change: what terms mean, examples, how answers behave, limits. */
    public static final Set<String> CONTENT_KEYS = Set.of("definitions", "metrics", "examples", "ask_clarifying_questions", "limits");
    /** Sections that decide what data can be seen: only from the computer the server runs on. */
    public static final Set<String> RULE_KEYS = Set.of("schema", "allowed_tables", "blocked_columns", "masked_columns",
            "blocked_functions", "share_results_with_ai", "share_category_values");
    static final List<String> LIMIT_KEYS = List.of("max_rows", "timeout_ms", "max_joins", "max_selects",
            "max_question_chars", "max_sql_chars", "max_tables_in_prompt");

    /** One change, in words. {@code widens}: it lets more data be seen or leave this computer. */
    public record Change(String key, String text, boolean widens) {}

    /** The new file text and policy, and what changed. */
    public record Edit(String yaml, Policy policy, List<Change> changes) {
        public boolean widens() {
            return changes.stream().anyMatch(Change::widens);
        }

        /** Changes the database user's permissions should follow (tables and blocked columns). */
        public boolean touchesRules() {
            return changes.stream().anyMatch(c -> Set.of("schema", "allowed_tables", "blocked_columns").contains(c.key()));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private final Path file;
    private final Path backups;
    private final Path log;

    public PolicyEditor(Path policyFile, Path stateDir) {
        this.file = policyFile;
        this.backups = stateDir.resolve("policy-backups");
        this.log = stateDir.resolve("policy-changes.jsonl");
    }

    public Path file() {
        return file;
    }

    // ------------------------------------------------------------------ current values, as the page edits them

    /** The editable values of a policy, in the shape the page sends back. */
    public static ObjectNode values(Policy p) {
        ObjectNode o = JSON.createObjectNode();
        o.put("schema", p.schema());
        o.set("allowed_tables", sortedArray(p.allowedTables()));
        ObjectNode blocked = o.putObject("blocked_columns");
        new TreeSet<>(p.blockedColumns().keySet()).forEach(t -> blocked.set(t, sortedArray(p.blockedColumns().get(t))));
        o.set("masked_columns", sortedArray(p.maskedColumns()));
        Set<String> extra = new TreeSet<>(p.blockedFunctions());
        extra.removeAll(Policy.BUILT_IN_BLOCKED_FUNCTIONS);
        o.set("blocked_functions", sortedArray(extra));
        o.put("share_results_with_ai", p.shareResultsWithAi());
        o.put("share_category_values", p.shareCategoryValues());
        o.put("ask_clarifying_questions", p.askClarifyingQuestions());
        ArrayNode ex = o.putArray("examples");
        p.examples().forEach(ex::add);
        ObjectNode defs = o.putObject("definitions");
        p.definitions().forEach(defs::put);
        ObjectNode metrics = o.putObject("metrics");
        p.metrics().forEach((name, m) -> {
            ObjectNode mo = metrics.putObject(name);
            mo.put("description", m.description());
            mo.put("sql", m.sql());
            mo.put("from", m.from());
            mo.put("where", m.where());
            mo.put("time_column", m.timeColumn());
        });
        ObjectNode lim = o.putObject("limits");
        lim.put("max_rows", p.maxRows());
        lim.put("timeout_ms", p.timeoutMillis());
        lim.put("max_joins", p.maxJoins());
        lim.put("max_selects", p.maxSelects());
        lim.put("max_question_chars", p.maxQuestionChars());
        lim.put("max_sql_chars", p.maxSqlChars());
        lim.put("max_tables_in_prompt", p.maxTablesInPrompt());
        return o;
    }

    private static ArrayNode sortedArray(Set<String> s) {
        ArrayNode a = JSON.createArrayNode();
        new TreeSet<>(s).forEach(a::add);
        return a;
    }

    // ------------------------------------------------------------------ preview and save

    /**
     * Applies {@code edits} (any subset of {@link #values}) to the current file text. Sections whose value
     * didn't change are left alone. Throws {@link Policy.PolicyException} if the result isn't a valid
     * policy and {@link IllegalArgumentException} for a section {@code allowed} doesn't include.
     */
    public static Edit apply(String currentYaml, Policy current, JsonNode edits, Set<String> allowed)
            throws Policy.PolicyException {
        if (edits == null || !edits.isObject()) throw new IllegalArgumentException("Send the settings as an object.");
        ObjectNode now = values(current);
        String text = currentYaml;
        for (Iterator<String> it = edits.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!now.has(key)) throw new IllegalArgumentException("'" + key + "' isn't a setting that can be changed here.");
            JsonNode want = normalise(key, edits.get(key));
            if (want.equals(normalise(key, now.get(key)))) continue;
            if (!allowed.contains(key))
                throw new IllegalArgumentException("'" + key + "' can only be changed on the computer QueryWarden runs on.");
            if (key.equals("limits")) { text = setLimits(text, want); continue; }   // keeps each line's comment
            boolean flow = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*:\\s*\\[").matcher(text).find();
            text = replaceSection(text, key, render(key, want, flow));
        }
        Policy next = Policy.parse(text, "the edited policy");
        return new Edit(text, next, describe(current, next));
    }

    /** Writes the edit: backs up the current file first, then replaces it atomically, then logs it. */
    public synchronized String save(Edit edit, String who) throws IOException {
        Files.createDirectories(backups);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(java.time.LocalDateTime.now());
        Path backup = backups.resolve(file.getFileName().toString().replaceFirst("\\.ya?ml$", "") + "-" + stamp + ".yaml");
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        write(edit.yaml());
        logChange(who, "edit", edit.changes(), backup.getFileName().toString());
        return backup.getFileName().toString();
    }

    /** Restores the most recent backup (and removes it, so a second Undo goes one further back). */
    public synchronized List<Change> undo(String who, Policy current) throws IOException, Policy.PolicyException {
        Path last = latestBackup();
        if (last == null) throw new IllegalStateException("There is no earlier version to go back to.");
        String text = Files.readString(last);
        Policy restored = Policy.parse(text, last.getFileName().toString());
        write(text);
        Files.delete(last);
        List<Change> changes = describe(current, restored);
        logChange(who, "undo", changes, last.getFileName().toString());
        return changes;
    }

    public synchronized int backupCount() {
        try (var s = Files.exists(backups) ? Files.list(backups) : java.util.stream.Stream.<Path>empty()) {
            return (int) s.filter(this::isMine).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private Path latestBackup() throws IOException {
        if (!Files.exists(backups)) return null;
        try (var s = Files.list(backups)) {
            return s.filter(this::isMine).max(java.util.Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        }
    }

    private boolean isMine(Path p) {
        String base = file.getFileName().toString().replaceFirst("\\.ya?ml$", "");
        return p.getFileName().toString().matches(Pattern.quote(base) + "-\\d{8}-\\d{6}-\\d{3}\\.yaml");
    }

    private void write(String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void logChange(String who, String action, List<Change> changes, String backup) throws IOException {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("at", Instant.now().toString());
        e.put("by", who);
        e.put("action", action);
        e.put("file", file.getFileName().toString());
        e.put("backup", backup);
        e.put("changes", changes.stream().map(c -> Map.of("key", c.key(), "text", c.text(), "widens", c.widens())).toList());
        Files.writeString(log, JSON.writeValueAsString(e) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ------------------------------------------------------------------ the file text

    /**
     * Changes the numbers in the {@code limits} section in place, so comments at the end of its lines stay;
     * limits that aren't in the file yet are added at the end of the section.
     */
    static String setLimits(String text, JsonNode want) {
        if (!Pattern.compile("(?m)^limits\\s*:").matcher(text).find()) return replaceSection(text, "limits", render("limits", want));
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        int start = 0;
        while (!lines.get(start).matches("limits\\s*:.*")) start++;
        int end = start + 1;
        while (end < lines.size() && (lines.get(end).isEmpty() || Character.isWhitespace(lines.get(end).charAt(0)))) end++;
        while (end - 1 > start && lines.get(end - 1).isBlank()) end--;
        Set<String> seen = new LinkedHashSet<>();
        for (int i = start + 1; i < end; i++) {
            var m = Pattern.compile("^(\\s+)([a-z_]+)(\\s*:\\s*)(\\S+)(.*)$").matcher(lines.get(i));
            if (m.matches() && want.has(m.group(2))) {
                lines.set(i, m.group(1) + m.group(2) + m.group(3) + want.get(m.group(2)).asLong() + m.group(5));
                seen.add(m.group(2));
            }
        }
        List<String> missing = new ArrayList<>();
        want.fieldNames().forEachRemaining(k -> { if (!seen.contains(k)) missing.add("  " + k + ": " + want.get(k).asLong()); });
        lines.addAll(end, missing);
        return String.join("\n", lines);
    }

    /** Replaces the top-level section {@code key} (or appends it), keeping every other line as it is. */
    static String replaceSection(String text, String key, String rendered) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches(Pattern.quote(key) + "\\s*:.*")) { start = i; break; }
        }
        List<String> add = List.of(rendered.split("\n"));
        if (start < 0) {
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines.remove(lines.size() - 1);
            lines.add("");
            lines.addAll(add);
            lines.add("");
            return String.join("\n", lines);
        }
        int end = start + 1;   // the section runs until the next line at column 0 (a key or a comment)
        while (end < lines.size() && (lines.get(end).isEmpty() || Character.isWhitespace(lines.get(end).charAt(0)))) end++;
        while (end - 1 > start && lines.get(end - 1).isBlank()) end--;   // keep the blank lines between sections
        List<String> out = new ArrayList<>(lines.subList(0, start));
        out.addAll(add);
        out.addAll(lines.subList(end, lines.size()));
        return String.join("\n", out);
    }

    /** Puts a value from the page into a canonical form, so "no change" is recognised. */
    static JsonNode normalise(String key, JsonNode v) {
        if (v == null || v.isNull()) throw new IllegalArgumentException("'" + key + "' needs a value.");
        switch (key) {
            case "allowed_tables", "masked_columns", "blocked_functions" -> {
                return sortedArray(names(v, key));
            }
            case "blocked_columns" -> {
                if (!v.isObject()) throw new IllegalArgumentException("blocked_columns must map a table to its columns.");
                ObjectNode o = JSON.createObjectNode();
                List<String> tables = new ArrayList<>();
                v.fieldNames().forEachRemaining(tables::add);
                tables.stream().sorted().forEach(t -> {
                    Set<String> cols = names(v.get(t), "blocked_columns." + t);
                    if (!cols.isEmpty()) o.set(t.strip().toLowerCase(java.util.Locale.ROOT), sortedArray(cols));
                });
                return o;
            }
            case "examples" -> {
                if (!v.isArray()) throw new IllegalArgumentException("examples must be a list of questions.");
                ArrayNode a = JSON.createArrayNode();
                for (JsonNode q : v) if (q.isTextual() && !q.asText().isBlank()) a.add(q.asText().strip());
                return a;
            }
            case "definitions" -> {
                if (!v.isObject()) throw new IllegalArgumentException("definitions must map a term to its meaning.");
                ObjectNode o = JSON.createObjectNode();
                v.fields().forEachRemaining(e -> {
                    String term = e.getKey().strip();
                    if (!term.isEmpty()) o.put(term, e.getValue().asText("").strip());
                });
                return o;
            }
            case "metrics" -> {
                if (!v.isObject()) throw new IllegalArgumentException("metrics must map a name to its definition.");
                ObjectNode o = JSON.createObjectNode();
                v.fields().forEachRemaining(e -> {
                    ObjectNode m = o.putObject(e.getKey().strip().toLowerCase(java.util.Locale.ROOT));
                    for (String f : List.of("description", "sql", "from", "where", "time_column"))
                        m.put(f, e.getValue().path(f).asText("").strip());
                });
                return o;
            }
            case "limits" -> {
                if (!v.isObject()) throw new IllegalArgumentException("limits must be a set of numbers.");
                ObjectNode o = JSON.createObjectNode();
                for (String k : LIMIT_KEYS) {
                    JsonNode n = v.get(k);
                    if (n == null || !n.canConvertToLong() && !(n.isTextual() && n.asText().matches("\\d+")))
                        throw new IllegalArgumentException("limits." + k + " must be a whole number.");
                    o.put(k, n.isTextual() ? Long.parseLong(n.asText()) : n.asLong());
                }
                return o;
            }
            case "schema" -> {
                if (!v.isTextual()) throw new IllegalArgumentException("schema must be a name.");
                return JSON.getNodeFactory().textNode(v.asText().strip().toLowerCase(java.util.Locale.ROOT));
            }
            default -> {
                if (!v.isBoolean()) throw new IllegalArgumentException(key + " must be true or false.");
                return v;
            }
        }
    }

    private static Set<String> names(JsonNode v, String key) {
        if (!v.isArray()) throw new IllegalArgumentException(key + " must be a list of names.");
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode n : v) {
            String s = n.asText("").strip().toLowerCase(java.util.Locale.ROOT);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** The YAML text of one section. Free text is quoted as JSON strings, which YAML reads as they are. */
    static String render(String key, JsonNode v) {
        return render(key, v, false);
    }

    /** {@code flow}: write name lists as [a, b] (the file already did), otherwise one per line. */
    static String render(String key, JsonNode v, boolean flow) {
        StringBuilder sb = new StringBuilder(key).append(':');
        switch (key) {
            case "allowed_tables", "masked_columns", "blocked_functions" -> {
                if (v.isEmpty()) return sb.append(" []").toString();
                if (flow) {
                    List<String> items = new ArrayList<>();
                    v.forEach(n -> items.add(q(n.asText())));
                    return sb.append(" [").append(String.join(", ", items)).append(']').toString();
                }
                for (JsonNode n : v) sb.append("\n  - ").append(q(n.asText()));
            }
            case "blocked_columns" -> {
                if (v.isEmpty()) return sb.append(" {}").toString();
                v.fields().forEachRemaining(e -> {
                    List<String> cols = new ArrayList<>();
                    e.getValue().forEach(c -> cols.add(q(c.asText())));
                    sb.append("\n  ").append(q(e.getKey())).append(": [").append(String.join(", ", cols)).append(']');
                });
            }
            case "examples" -> {
                if (v.isEmpty()) return sb.append(" []").toString();
                for (JsonNode n : v) sb.append("\n  - ").append(scalar(n.asText(), 4));
            }
            case "definitions" -> {
                if (v.isEmpty()) return sb.append(" {}").toString();
                v.fields().forEachRemaining(e -> sb.append("\n  ").append(key(e.getKey())).append(": ").append(scalar(e.getValue().asText(), 4)));
            }
            case "metrics" -> {
                if (v.isEmpty()) return sb.append(" {}").toString();
                v.fields().forEachRemaining(e -> {
                    sb.append("\n  ").append(q(e.getKey())).append(':');
                    for (String f : List.of("description", "sql", "from", "where", "time_column")) {
                        String val = e.getValue().path(f).asText("");
                        if (!val.isEmpty()) sb.append("\n    ").append(f).append(": ").append(scalar(val, 6));
                    }
                });
            }
            case "limits" -> v.fields().forEachRemaining(e -> sb.append("\n  ").append(e.getKey()).append(": ").append(e.getValue().asLong()));
            case "schema" -> sb.append(' ').append(q(v.asText()));
            default -> sb.append(' ').append(v.asBoolean());
        }
        return sb.toString();
    }

    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9 _-]*[A-Za-z0-9]|[A-Za-z]");
    private static final Set<String> YAML_WORDS = Set.of("true", "false", "yes", "no", "on", "off", "null", "y", "n", "~");
    static final int WIDTH = 100;

    /** A mapping key, unquoted when YAML reads it as the same text. */
    private static String key(String k) {
        return PLAIN_KEY.matcher(k).matches() && !YAML_WORDS.contains(k.toLowerCase(java.util.Locale.ROOT)) ? k : json(k);
    }

    /**
     * Text as a person would write it: plain when YAML reads it back unchanged, wrapped as a folded block
     * ({@code >-}) when it is long, JSON-quoted otherwise. Every form reads back as exactly {@code s}.
     */
    static String scalar(String s, int indent) {
        boolean oneLine = !s.contains("\n") && s.equals(s.strip());
        boolean plainOk = oneLine && !s.isEmpty() && !s.contains(": ") && !s.contains(" #") && !s.endsWith(":")
                && "-?:,[]{}#&*!|>'\"%@`".indexOf(s.charAt(0)) < 0 && !YAML_WORDS.contains(s.toLowerCase(java.util.Locale.ROOT))
                && !s.matches("[-+]?[0-9.]+([eE][-+]?[0-9]+)?");
        if (plainOk && s.length() + indent <= WIDTH) return s;
        if (oneLine && !s.contains("  ") && !s.isEmpty()) {
            StringBuilder out = new StringBuilder(">-");
            String pad = " ".repeat(indent);
            StringBuilder line = new StringBuilder();
            for (String word : s.split(" ")) {
                if (line.length() > 0 && indent + line.length() + 1 + word.length() > WIDTH) {
                    out.append('\n').append(pad).append(line);
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            return out.append('\n').append(pad).append(line).toString();
        }
        return json(s);
    }

    private static String q(String s) {
        return PLAIN.matcher(s).matches() ? s : json(s);
    }

    private static String json(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ what changed, in words

    static List<Change> describe(Policy a, Policy b) {
        List<Change> out = new ArrayList<>();
        if (!a.schema().equals(b.schema()))
            out.add(new Change("schema", "Schema: " + a.schema() + " → " + b.schema(), true));
        diffSet(out, "allowed_tables", "Tables Claude can use", a.allowedTables(), b.allowedTables(), true);
        Set<String> tables = new TreeSet<>(a.blockedColumns().keySet());
        tables.addAll(b.blockedColumns().keySet());
        for (String t : tables)
            diffSet(out, "blocked_columns", "Blocked columns in " + t, a.blockedColumns().getOrDefault(t, Set.of()),
                    b.blockedColumns().getOrDefault(t, Set.of()), false);
        diffSet(out, "masked_columns", "Hidden in results", a.maskedColumns(), b.maskedColumns(), false);
        diffSet(out, "blocked_functions", "Extra blocked functions", a.blockedFunctions(), b.blockedFunctions(), false);
        flag(out, "share_results_with_ai", "Result rows shared with AI", a.shareResultsWithAi(), b.shareResultsWithAi(), true);
        flag(out, "share_category_values", "Category values shared with AI", a.shareCategoryValues(), b.shareCategoryValues(), true);
        flag(out, "ask_clarifying_questions", "Clarifying questions", a.askClarifyingQuestions(), b.askClarifyingQuestions(), false);
        if (!a.examples().equals(b.examples()))
            out.add(new Change("examples", b.examples().isEmpty() ? "Example questions: written by Claude"
                    : "Example questions: " + b.examples().size() + " set in the policy", false));
        diffMap(out, "definitions", "Definition", a.definitions(), b.definitions());
        Map<String, String> ma = new LinkedHashMap<>(), mb = new LinkedHashMap<>();
        a.metrics().forEach((n, m) -> ma.put(n, m.query(n) + " | " + m.description() + " | " + m.timeColumn()));
        b.metrics().forEach((n, m) -> mb.put(n, m.query(n) + " | " + m.description() + " | " + m.timeColumn()));
        diffMap(out, "metrics", "Metric", ma, mb);
        ObjectNode la = (ObjectNode) values(a).get("limits"), lb = (ObjectNode) values(b).get("limits");
        for (String k : LIMIT_KEYS)
            if (la.get(k).asLong() != lb.get(k).asLong())
                out.add(new Change("limits", "Limit " + k + ": " + la.get(k).asLong() + " → " + lb.get(k).asLong(), false));
        return out;
    }

    /** {@code widenOnAdd}: adding opens data up (allowed tables); otherwise removing does (blocked, masked). */
    private static void diffSet(List<Change> out, String key, String label, Set<String> a, Set<String> b, boolean widenOnAdd) {
        Set<String> added = new TreeSet<>(b), removed = new TreeSet<>(a);
        added.removeAll(a);
        removed.removeAll(b);
        if (!added.isEmpty()) out.add(new Change(key, label + ": added " + String.join(", ", added), widenOnAdd));
        if (!removed.isEmpty()) out.add(new Change(key, label + ": removed " + String.join(", ", removed), !widenOnAdd));
    }

    private static void flag(List<Change> out, String key, String label, boolean a, boolean b, boolean widenOnTrue) {
        if (a != b) out.add(new Change(key, label + ": " + (b ? "on" : "off"), widenOnTrue && b));
    }

    private static void diffMap(List<Change> out, String key, String label, Map<String, String> a, Map<String, String> b) {
        for (String k : b.keySet()) {
            if (!a.containsKey(k)) out.add(new Change(key, label + " added: " + k, false));
            else if (!a.get(k).equals(b.get(k))) out.add(new Change(key, label + " changed: " + k, false));
        }
        for (String k : a.keySet()) if (!b.containsKey(k)) out.add(new Change(key, label + " removed: " + k, false));
    }
}
