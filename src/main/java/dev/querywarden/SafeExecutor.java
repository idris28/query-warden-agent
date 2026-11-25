package dev.querywarden;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs validated SQL as the least-privilege agent role. PostgreSQL enforces the policy on its own,
 * independently of {@link SqlValidator}:
 * <ul>
 *   <li>column-level GRANTs: reading api_keys, employees.salary or employees.ssn fails with 42501</li>
 *   <li>read-only transaction ({@code setReadOnly} plus the role default): any write fails with 25006</li>
 *   <li>{@code SET LOCAL statement_timeout}: long queries are cancelled (57014)</li>
 *   <li>{@code setMaxRows}: the driver never returns more than maxRows + 1 rows</li>
 *   <li>the transaction is always rolled back</li>
 * </ul>
 * Output is then scrubbed: masked columns become {@code ***}, and PII-looking values are redacted.
 */
public final class SafeExecutor {

    public static final class ExecutionException extends Exception {
        private final String code;

        public ExecutionException(String code, String message) {
            super("[" + code + "] " + message);
            this.code = code;
        }

        public String code() { return code; }
    }

    public record QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated,
                              long elapsedMillis, int redactions) {}

    private static final List<Object[]> PII = List.of(
            new Object[]{Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"), "<email>"},
            new Object[]{Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"), "<ssn>"},
            new Object[]{Pattern.compile("\\+\\d{1,3}[\\s.-]?\\(?\\d{2,4}\\)?[\\s.-]?\\d{3,4}(?:[\\s.-]?\\d{3,4})?"), "<phone>"},
            new Object[]{Pattern.compile("\\(?\\b\\d{3}\\)?[\\s.-]\\d{3}[\\s.-]\\d{4}\\b"), "<phone>"},
            new Object[]{Pattern.compile("\\bsk_(live|test)_\\w+"), "<secret>"});

    private final Database.Config config;
    private final Policy policy;

    public SafeExecutor(Database.Config config, Policy policy) {
        this.config = config;
        this.policy = policy;
    }

    public QueryResult execute(String sql) throws ExecutionException {
        try (Connection c = config.agent()) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL statement_timeout = " + policy.timeoutMillis());
                st.execute("SET LOCAL search_path = " + Database.ident(policy.schema()));
                st.setMaxRows(policy.maxRows() + 1);
                st.setQueryTimeout(Math.max(1, policy.timeoutMillis() / 1000 + 2)); // client-side backstop

                long start = System.nanoTime();
                List<String> columns = new ArrayList<>();
                List<List<Object>> rows = new ArrayList<>();
                int redactions = 0;
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    boolean[] masked = new boolean[md.getColumnCount()];
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        columns.add(md.getColumnLabel(i));
                        masked[i - 1] = policy.isMasked(md.getColumnLabel(i));
                    }
                    while (rs.next() && rows.size() <= policy.maxRows()) {
                        List<Object> row = new ArrayList<>(columns.size());
                        for (int i = 1; i <= columns.size(); i++) {
                            Object v = rs.getObject(i);
                            if (v instanceof BigDecimal bd) v = bd.stripTrailingZeros().toPlainString();
                            if (v instanceof String s) {
                                if (masked[i - 1]) {
                                    v = "***";
                                    redactions++;
                                } else {
                                    int[] count = {0};
                                    v = scrub(s, count);
                                    redactions += count[0];
                                }
                            }
                            row.add(v);
                        }
                        rows.add(row);
                    }
                }
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                boolean truncated = rows.size() > policy.maxRows();
                if (truncated) rows = rows.subList(0, policy.maxRows());
                return new QueryResult(columns, rows, truncated, elapsed, redactions);
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw classify(e);
        }
    }

    /** Redact PII-looking substrings; {@code count[0]} is incremented per redaction. */
    static String scrub(String s, int[] count) {
        for (Object[] p : PII) {
            Matcher m = ((Pattern) p[0]).matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement((String) p[1]));
                count[0]++;
            }
            m.appendTail(sb);
            s = sb.toString();
        }
        return s;
    }

    private ExecutionException classify(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        // Error text can echo data values (e.g. failed casts), so it is scrubbed too.
        String msg = scrub(e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse(""), new int[1]);
        return switch (state) {
            case "57014" -> new ExecutionException("TIMEOUT", "Query exceeded the " + policy.timeoutMillis() + " ms budget.");
            case "42501" -> new ExecutionException("ACCESS_DENIED", "Query touched data the policy does not allow.");
            case "25006" -> new ExecutionException("ACCESS_DENIED", "Writes are not allowed (read-only transaction).");
            case "08001", "08004", "08006", "28P01", "28000" ->
                    new ExecutionException("CONNECTION_ERROR", "Database connection failed: " + msg);
            default -> new ExecutionException("SQL_ERROR", msg);
        };
    }
}
