package dev.querywarden.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compares QueryWarden's result rows with a reference result, by value.
 *
 * <ul>
 *   <li>Column names don't matter, and extra columns in the answer are fine (e.g. an id next to a
 *       name). Every reference column must be found among the answer's columns.</li>
 *   <li>Row count must be equal, and the rows must match as a whole (not column by column).</li>
 *   <li>Numbers are compared rounded to 2 decimals; dates ignore a midnight time part; a "2025-03"
 *       month is read as 2025-03-01.</li>
 *   <li>{@code ordered}: the rows must also come in the same order. Ties in the ranking are allowed
 *       to swap places as long as every number and date column reads the same top to bottom.</li>
 * </ul>
 */
public final class ResultMatcher {

    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern MIDNIGHT = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})[T ]00:00(:00)?(\\.0+)?");

    private ResultMatcher() {}

    public record Outcome(boolean match, String why) {}

    public static Outcome compare(List<List<Object>> got, List<List<Object>> ref, boolean ordered) {
        if (got.size() != ref.size())
            return new Outcome(false, "expected " + ref.size() + " row(s), got " + got.size());
        if (ref.isEmpty()) return new Outcome(true, "both empty");
        int refCols = ref.get(0).size();
        int gotCols = got.get(0).size();
        if (gotCols < refCols)
            return new Outcome(false, "expected at least " + refCols + " column(s), got " + gotCols);

        List<List<String>> g = normalise(got), r = normalise(ref);
        // For each reference column, the answer columns holding the same multiset of values.
        List<List<Integer>> candidates = new ArrayList<>();
        for (int j = 0; j < refCols; j++) {
            List<String> want = sortedColumn(r, j);
            List<Integer> c = new ArrayList<>();
            for (int i = 0; i < gotCols; i++) if (sortedColumn(g, i).equals(want)) c.add(i);
            if (c.isEmpty())
                return new Outcome(false, "no answer column has the values of reference column " + (j + 1)
                        + " (e.g. " + r.get(0).get(j) + ")");
            candidates.add(c);
        }
        Outcome best = assign(candidates, 0, new int[refCols], new boolean[gotCols], g, r, ordered);
        return best != null ? best : new Outcome(false, "the values are there but not in the same rows");
    }

    private static Outcome assign(List<List<Integer>> candidates, int j, int[] pick, boolean[] used,
                                  List<List<String>> g, List<List<String>> r, boolean ordered) {
        if (j == pick.length) return check(pick, g, r, ordered);
        Outcome partial = null;
        for (int i : candidates.get(j)) {
            if (used[i]) continue;
            used[i] = true;
            pick[j] = i;
            Outcome o = assign(candidates, j + 1, pick, used, g, r, ordered);
            used[i] = false;
            if (o != null && o.match()) return o;
            if (o != null) partial = o;
        }
        return partial;
    }

    private static Outcome check(int[] pick, List<List<String>> g, List<List<String>> r, boolean ordered) {
        List<List<String>> projected = new ArrayList<>();
        for (List<String> row : g) {
            List<String> p = new ArrayList<>();
            for (int i : pick) p.add(row.get(i));
            projected.add(p);
        }
        if (!multiset(projected).equals(multiset(r))) return null;
        if (!ordered || projected.equals(r)) return new Outcome(true, ordered ? "same rows, same order" : "same rows");
        // Ties may swap places: accept if every numeric/date column reads the same top to bottom.
        for (int j = 0; j < r.get(0).size(); j++) {
            if (!isRankable(r, j)) continue;
            for (int k = 0; k < r.size(); k++) {
                if (!projected.get(k).get(j).equals(r.get(k).get(j)))
                    return new Outcome(false, "same rows but in a different order");
            }
        }
        return new Outcome(true, "same rows, same order (ties swapped)");
    }

    private static boolean isRankable(List<List<String>> rows, int j) {
        String v = rows.get(0).get(j);
        return v.matches("-?\\d+(\\.\\d+)?") || v.matches("\\d{4}-\\d{2}-\\d{2}.*");
    }

    private static Map<List<String>, Integer> multiset(List<List<String>> rows) {
        Map<List<String>, Integer> m = new HashMap<>();
        for (List<String> row : rows) m.merge(row, 1, Integer::sum);
        return m;
    }

    private static List<String> sortedColumn(List<List<String>> rows, int j) {
        List<String> col = new ArrayList<>();
        for (List<String> row : rows) col.add(row.get(j));
        col.sort(null);
        return col;
    }

    static List<List<String>> normalise(List<List<Object>> rows) {
        List<List<String>> out = new ArrayList<>();
        for (List<Object> row : rows) {
            List<String> n = new ArrayList<>();
            for (Object v : row) n.add(norm(v));
            out.add(n);
        }
        return out;
    }

    static String norm(Object v) {
        if (v == null) return "∅";
        if (v instanceof Number num) return number(new BigDecimal(num.toString()));
        String s = v.toString().strip();
        if (MONTH.matcher(s).matches()) return s + "-01";
        var m = MIDNIGHT.matcher(s);
        if (m.matches()) return m.group(1);
        if (s.matches("-?\\d+(\\.\\d+)?")) return number(new BigDecimal(s));
        return s;
    }

    private static String number(BigDecimal d) {
        BigDecimal r = d.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (r.signum() == 0) return "0";
        return r.toPlainString();
    }
}
