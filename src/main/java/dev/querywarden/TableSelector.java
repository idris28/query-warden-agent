package dev.querywarden;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the tables worth showing Claude for one question when the database has too many to send
 * them all. Works locally (no extra Claude call): it scores tables by how well their names, column
 * names, comments and category values match the question's words, always keeps the tables used by
 * the conversation so far, verified examples and any metric the question mentions, then adds the
 * tables that link the chosen ones (foreign keys), up to the limit.
 */
final class TableSelector {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP = Set.of("the", "a", "an", "of", "in", "on", "for", "to", "and", "or", "by",
            "with", "per", "each", "every", "all", "how", "many", "much", "what", "which", "who", "is", "are", "was",
            "were", "show", "me", "list", "give", "find", "get", "our", "we", "do", "does", "did", "have", "has",
            "from", "than", "that", "this", "these", "those", "there", "top", "most", "least", "last", "first",
            "id", "name", "date", "number", "count", "total", "average", "sum", "year", "month", "day");

    private final List<Database.TableInfo> tables;
    private final Map<String, Database.TableInfo> byName = new HashMap<>();
    private final Map<String, Set<String>> neighbours = new HashMap<>();

    TableSelector(List<Database.TableInfo> tables) {
        this.tables = List.copyOf(tables);
        for (Database.TableInfo t : tables) {
            byName.put(t.name(), t);
            neighbours.computeIfAbsent(t.name(), k -> new LinkedHashSet<>());
        }
        for (Database.TableInfo t : tables) {
            for (String fk : t.foreignKeys()) {
                // "customer_id -> customers.id"
                int arrow = fk.indexOf("-> ");
                if (arrow < 0) continue;
                String target = fk.substring(arrow + 3, fk.lastIndexOf('.'));
                if (!byName.containsKey(target)) continue;
                neighbours.get(t.name()).add(target);
                neighbours.get(target).add(t.name());
            }
        }
    }

    List<Database.TableInfo> all() {
        return tables;
    }

    Database.TableInfo table(String name) {
        return byName.get(name);
    }

    /**
     * The chosen tables, best first.
     *
     * @param mustHave text (SQL of earlier turns and verified examples, metric definitions the question
     *                 mentions) whose table names are always included
     */
    List<Database.TableInfo> select(String question, Collection<String> mustHave, int max) {
        Set<String> words = words(question);
        Map<String, Double> score = new HashMap<>();
        for (Database.TableInfo t : tables) score.put(t.name(), score(t, words));

        LinkedHashSet<String> chosen = new LinkedHashSet<>();
        for (String text : mustHave) chosen.addAll(tablesIn(text));
        List<String> ranked = tables.stream().map(Database.TableInfo::name)
                .filter(n -> score.get(n) > 0)
                .sorted(Comparator.comparingDouble((String n) -> -score.get(n)).thenComparing(n -> n))
                .toList();
        // the strongest matches first, keeping room for the tables that join them
        int direct = Math.max(1, (int) Math.ceil(max * 0.6));
        for (String n : ranked) {
            if (chosen.size() >= direct) break;
            chosen.add(n);
        }
        // tables linking two chosen ones (e.g. order_items between orders and products), then other neighbours
        List<String> bridges = new ArrayList<>(), others = new ArrayList<>();
        for (Database.TableInfo t : tables) {
            String n = t.name();
            if (chosen.contains(n)) continue;
            long links = neighbours.get(n).stream().filter(chosen::contains).count();
            if (links >= 2) bridges.add(n);
            else if (links == 1) others.add(n);
        }
        Comparator<String> byScore = Comparator.comparingDouble((String n) -> -score.get(n)).thenComparing(n -> n);
        bridges.sort(byScore);
        others.sort(byScore);
        for (String n : bridges) if (chosen.size() < max) chosen.add(n);
        for (String n : ranked) if (chosen.size() < max) chosen.add(n);
        for (String n : others) if (chosen.size() < max) chosen.add(n);
        if (chosen.isEmpty()) tables.stream().limit(max).forEach(t -> chosen.add(t.name()));
        return chosen.stream().limit(Math.max(max, mustHaveCount(mustHave))).map(byName::get).toList();
    }

    private int mustHaveCount(Collection<String> mustHave) {
        Set<String> s = new LinkedHashSet<>();
        for (String text : mustHave) s.addAll(tablesIn(text));
        return s.size();
    }

    /** Known table names that appear as words in {@code text} (SQL or prose). */
    Set<String> tablesIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*").matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) if (byName.containsKey(m.group())) out.add(m.group());
        return out;
    }

    static double score(Database.TableInfo t, Set<String> words) {
        double s = 0;
        for (String part : t.name().split("_")) if (matches(part, words)) s += 3;
        if (matches(t.name(), words)) s += 3;
        for (Database.ColumnInfo c : t.columns()) {
            for (String part : c.name().split("_")) if (!STOP.contains(part) && matches(part, words)) s += 1;
            for (String v : c.values()) if (words.contains(v.toLowerCase(Locale.ROOT))) s += 2;
            if (c.note() != null) for (String w : words(c.note())) if (words.contains(w)) s += 0.5;
        }
        return s;
    }

    private static boolean matches(String part, Set<String> words) {
        String p = stem(part.toLowerCase(Locale.ROOT));
        return p.length() > 1 && words.contains(p);
    }

    static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (STOP.contains(w)) continue;
            out.add(w);
            out.add(stem(w));
        }
        return out;
    }

    /** Crude singular form, enough to match "customers" with "customer" and "categories" with "category". */
    static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && (w.endsWith("ses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes")))
            return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }
}
