package dev.querywarden;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Saved questions: a question with the SQL that answered it, run again on demand or on a schedule
 * without calling Claude, optionally with an alert ("tell me when open orders go above 20").
 *
 * <p>Kept in {@code <policy>.saved.json} next to the policy file. The file is re-read when it changes,
 * so the command line and the web server can both edit it.
 */
public final class SavedQuestions {

    /**
     * Fires when the value in {@code column} (or the first number column, if blank) matches: {@code op} is
     * one of {@code > >= < <= = !=} (compared with {@code value}, on any row) or {@code changes} (the
     * first row's value differs from the previous run).
     */
    public record Alert(String column, String op, Double value) {
        static final List<String> OPS = List.of(">", ">=", "<", "<=", "=", "!=", "changes");

        public Alert {
            column = column == null ? "" : column.strip();
            if (!OPS.contains(op)) throw new IllegalArgumentException("The alert needs one of " + OPS + ".");
            if (!op.equals("changes") && value == null) throw new IllegalArgumentException("The alert needs a number to compare with.");
        }

        public String describe() {
            String col = column.isEmpty() ? "the value" : column;
            return op.equals("changes") ? col + " changes" : col + " " + op + " " + fmt(value);
        }
    }

    public record Saved(String id, String name, String question, String sql, String owner, String created,
                        String schedule, Alert alert, String lastRun, String lastStatus, String lastValue,
                        boolean alerting, String lastMessage) {
        Saved withRun(String at, String status, String value, boolean alerting, String message) {
            return new Saved(id, name, question, sql, owner, created, schedule, alert, at, status, value, alerting, message);
        }
    }

    public static final int MAX_SAVED = 200;
    public static final int MAX_NAME_CHARS = 80;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private List<Saved> saved = List.of();
    private String loadedStamp = "";

    public SavedQuestions(Path file) {
        this.file = file;
    }

    /** {@code policy.yaml} → {@code policy.saved.json} in the same folder; no policy file (demo setup) → saved.json. */
    public static SavedQuestions forPolicy(Path policyFile) {
        if (policyFile == null) return new SavedQuestions(Path.of("saved.json"));
        String name = policyFile.getFileName().toString().replaceFirst("\\.ya?ml$", "");
        return new SavedQuestions(policyFile.toAbsolutePath().getParent().resolve(name + ".saved.json"));
    }

    public Path file() {
        return file;
    }

    public synchronized List<Saved> list() {
        reload();
        return saved;
    }

    public synchronized Optional<Saved> get(String id) {
        reload();
        return saved.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public synchronized Saved add(String name, String question, String sql, String owner, String schedule, Alert alert)
            throws IOException {
        reload();
        if (question == null || question.isBlank() || sql == null || sql.isBlank())
            throw new IllegalArgumentException("A saved question needs a question and its SQL.");
        String n = name == null || name.isBlank() ? question.strip() : name.strip();
        if (n.length() > MAX_NAME_CHARS) n = n.substring(0, MAX_NAME_CHARS - 1) + "…";
        String sched = Schedule.normalise(schedule);
        if (saved.size() >= MAX_SAVED) throw new IllegalArgumentException("At most " + MAX_SAVED + " saved questions.");
        byte[] raw = new byte[5];
        RANDOM.nextBytes(raw);
        Saved s = new Saved("s_" + HexFormat.of().formatHex(raw), n, question.strip(), sql.strip(),
                owner == null ? "" : owner, Instant.now().toString(), sched, alert, "", "", "", false, "");
        List<Saved> next = new ArrayList<>(saved);
        next.add(s);
        save(next);
        return s;
    }

    public synchronized boolean remove(String id) throws IOException {
        reload();
        List<Saved> next = saved.stream().filter(s -> !s.id().equals(id)).toList();
        if (next.size() == saved.size()) return false;
        save(next);
        return true;
    }

    /** Applies a change to one saved question (e.g. recording a run). False if it's gone. */
    public synchronized boolean update(String id, UnaryOperator<Saved> change) throws IOException {
        reload();
        List<Saved> next = new ArrayList<>();
        boolean found = false;
        for (Saved s : saved) {
            if (s.id().equals(id)) { next.add(change.apply(s)); found = true; }
            else next.add(s);
        }
        if (found) save(next);
        return found;
    }

    /** Saved questions whose schedule says they should run at {@code now}. */
    public synchronized List<Saved> due(Instant now, ZoneId zone) {
        reload();
        List<Saved> out = new ArrayList<>();
        for (Saved s : saved) {
            if (s.schedule().isEmpty()) continue;
            Instant from = Instant.parse(s.lastRun().isEmpty() ? s.created() : s.lastRun());
            if (!Schedule.next(s.schedule(), from, zone).isAfter(now)) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ alerts

    /** What an alert check found: the value shown, and whether the alert condition holds. */
    public record Check(String value, boolean fires, String message) {}

    /** Checks a result against a saved question's alert (or just reports its value when there is none). */
    public static Check check(Saved s, SafeExecutor.QueryResult r) {
        int col = column(s.alert() == null ? "" : s.alert().column(), r);
        String value = r.rows().isEmpty() ? "(no rows)" : col < 0 ? r.rows().size() + " row(s)" : text(r.rows().get(0).get(col));
        Alert a = s.alert();
        if (a == null) return new Check(value, false, "");
        if (col < 0) return new Check(value, false, "The alert's column isn't in the result.");
        if (a.op().equals("changes")) {
            boolean changed = !s.lastValue().isEmpty() && !s.lastValue().equals(value);
            return new Check(value, changed, changed ? s.name() + ": " + s.lastValue() + " → " + value : "");
        }
        int hits = 0;
        for (List<Object> row : r.rows()) {
            if (row.get(col) instanceof Number n && compare(n.doubleValue(), a.op(), a.value())) hits++;
        }
        String msg = hits == 0 ? "" : r.rows().size() == 1
                ? s.name() + ": " + value + " (" + a.describe() + ")"
                : s.name() + ": " + hits + " row(s) where " + a.describe();
        return new Check(value, hits > 0, msg);
    }

    static boolean compare(double x, String op, double v) {
        return switch (op) {
            case ">" -> x > v;
            case ">=" -> x >= v;
            case "<" -> x < v;
            case "<=" -> x <= v;
            case "=" -> Math.abs(x - v) < 1e-9;
            case "!=" -> Math.abs(x - v) >= 1e-9;
            default -> false;
        };
    }

    /** The named column, or the first number column when no name is given; -1 if none. */
    static int column(String name, SafeExecutor.QueryResult r) {
        if (name != null && !name.isBlank()) {
            for (int i = 0; i < r.columns().size(); i++)
                if (r.columns().get(i).equalsIgnoreCase(name.strip())) return i;
            return -1;
        }
        for (int i = 0; i < r.columnTypes().size(); i++) if ("number".equals(r.columnTypes().get(i))) return i;
        return -1;
    }

    static String text(Object v) {
        if (v == null) return "null";
        if (v instanceof Number n) return fmt(n.doubleValue());
        return v.toString();
    }

    static String fmt(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return new java.math.BigDecimal(d).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ schedules

    /**
     * Schedules, in local time: {@code every 15m}, {@code every 2h}, {@code hourly}, {@code daily 08:00},
     * {@code weekdays 08:00}, {@code weekly mon 08:00}, or empty / {@code off}.
     */
    public static final class Schedule {
        private static final Pattern EVERY = Pattern.compile("every (\\d{1,4}) ?(m|min|minutes?|h|hours?)");
        private static final Pattern AT = Pattern.compile("(daily|weekdays) ([01]?\\d|2[0-3]):([0-5]\\d)");
        private static final Pattern WEEKLY = Pattern.compile("weekly (mon|tue|wed|thu|fri|sat|sun)[a-z]* ([01]?\\d|2[0-3]):([0-5]\\d)");
        public static final int MIN_MINUTES = 5;

        private Schedule() {}

        /** The canonical form ("" for none). Throws with a readable message if it isn't one of the forms. */
        public static String normalise(String s) {
            String t = s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (t.isEmpty() || t.equals("off") || t.equals("none")) return "";
            if (t.equals("hourly")) return "every 1h";
            Matcher m = EVERY.matcher(t);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                boolean hours = m.group(2).startsWith("h");
                if ((hours ? n * 60 : n) < MIN_MINUTES)
                    throw new IllegalArgumentException("Run it at most every " + MIN_MINUTES + " minutes.");
                return "every " + n + (hours ? "h" : "m");
            }
            m = AT.matcher(t);
            if (m.matches()) return m.group(1) + " " + String.format("%02d:%s", Integer.parseInt(m.group(2)), m.group(3));
            m = WEEKLY.matcher(t);
            if (m.matches())
                return "weekly " + m.group(1) + " " + String.format("%02d:%s", Integer.parseInt(m.group(2)), m.group(3));
            throw new IllegalArgumentException("Use a schedule like 'every 15m', 'hourly', 'daily 08:00', "
                    + "'weekdays 08:00' or 'weekly mon 08:00' (or leave it empty).");
        }

        /** The first run time strictly after {@code after}. */
        public static Instant next(String schedule, Instant after, ZoneId zone) {
            Matcher m = EVERY.matcher(schedule);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                return after.plus(m.group(2).startsWith("h") ? Duration.ofHours(n) : Duration.ofMinutes(n));
            }
            String[] p = schedule.split(" ");
            LocalTime at = LocalTime.parse(p[p.length - 1]);
            ZonedDateTime t = after.atZone(zone).with(at).withSecond(0).withNano(0);
            if (!t.toInstant().isAfter(after)) t = t.plusDays(1);
            for (int i = 0; i < 8; i++) {
                DayOfWeek d = t.getDayOfWeek();
                boolean ok = switch (p[0]) {
                    case "weekdays" -> d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
                    case "weekly" -> d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT).equals(p[1]);
                    default -> true;
                };
                if (ok) return t.toInstant();
                t = t.plusDays(1);
            }
            throw new IllegalStateException("bad schedule " + schedule);
        }
    }

    // ------------------------------------------------------------------ file

    private void reload() {
        try {
            if (!Files.exists(file)) {
                saved = List.of();
                loadedStamp = "";
                return;
            }
            String stamp = UserStore.stamp(file);
            if (stamp.equals(loadedStamp)) return;
            List<Saved> out = new ArrayList<>();
            for (JsonNode n : JSON.readTree(file.toFile()).path("saved")) {
                Alert alert = null;
                JsonNode a = n.path("alert");
                if (a.isObject())
                    alert = new Alert(a.path("column").asText(""), a.path("op").asText(),
                            a.path("value").isNumber() ? a.path("value").asDouble() : null);
                out.add(new Saved(n.path("id").asText(), n.path("name").asText(), n.path("question").asText(),
                        n.path("sql").asText(), n.path("owner").asText(""), n.path("created").asText(),
                        Schedule.normalise(n.path("schedule").asText("")), alert, n.path("last_run").asText(""),
                        n.path("last_status").asText(""), n.path("last_value").asText(""),
                        n.path("alerting").asBoolean(false), n.path("last_message").asText("")));
            }
            saved = List.copyOf(out);
            loadedStamp = stamp;
        } catch (IOException | RuntimeException e) {
            System.err.println("saved questions: can't read " + file + ": " + e.getMessage());
        }
    }

    private void save(List<Saved> next) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Saved s : next) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("name", s.name());
            m.put("question", s.question());
            m.put("sql", s.sql());
            m.put("owner", s.owner());
            m.put("created", s.created());
            m.put("schedule", s.schedule());
            if (s.alert() != null) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("column", s.alert().column());
                a.put("op", s.alert().op());
                if (s.alert().value() != null) a.put("value", s.alert().value());
                m.put("alert", a);
            }
            m.put("last_run", s.lastRun());
            m.put("last_status", s.lastStatus());
            m.put("last_value", s.lastValue());
            m.put("alerting", s.alerting());
            m.put("last_message", s.lastMessage());
            rows.add(m);
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("saved", rows)));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        saved = List.copyOf(next);
        loadedStamp = UserStore.stamp(file);
    }
}
