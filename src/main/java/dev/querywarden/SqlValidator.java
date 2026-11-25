package dev.querywarden;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Static SQL guardrails. The generated SQL is parsed into an AST and checked against the
 * {@link Policy} before it reaches the database:
 * <ol>
 *   <li>size limit, exactly one statement</li>
 *   <li>read-only: a plain query with no SELECT INTO or row locks (FOR UPDATE / SHARE)</li>
 *   <li>allowed tables only, in the policy schema (no pg_catalog, information_schema, other schemas)</li>
 *   <li>no blocked columns; no {@code *} over a table that has blocked columns</li>
 *   <li>no dangerous functions and no table functions</li>
 *   <li>complexity: max joins, max SELECT blocks</li>
 *   <li>LIMIT added or lowered to {@code policy.maxRows()}</li>
 * </ol>
 */
public final class SqlValidator {

    public static final class ValidationException extends Exception {
        private final String code;

        public ValidationException(String code, String message) {
            super("[" + code + "] " + message);
            this.code = code;
        }

        public String code() { return code; }
    }

    public record ValidatedQuery(String sql, Set<String> tables, List<String> warnings) {}

    private final Policy policy;

    public SqlValidator(Policy policy) {
        this.policy = policy;
    }

    public ValidatedQuery validate(String raw) throws ValidationException {
        String sql = raw == null ? "" : raw.strip();
        while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).strip();
        if (sql.isEmpty()) throw new ValidationException("EMPTY", "No SQL was produced.");
        if (sql.length() > policy.maxSqlChars())
            throw new ValidationException("TOO_LONG", "SQL exceeds " + policy.maxSqlChars() + " characters.");

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(raw.strip());
        } catch (JSQLParserException e) {
            String msg = e.getMessage() == null ? "syntax error" : e.getMessage().lines().findFirst().orElse("");
            throw new ValidationException("PARSE_ERROR", "SQL could not be parsed: " + msg);
        }
        List<Statement> list = statements.stream().filter(s -> s != null).toList();
        if (list.size() != 1) throw new ValidationException("MULTI_STATEMENT", "Exactly one SQL statement is allowed.");
        if (!(list.get(0) instanceof Select select))
            throw new ValidationException("NOT_READ_ONLY",
                    "Only SELECT queries are allowed (got " + list.get(0).getClass().getSimpleName() + ").");

        Inspector inspector = new Inspector();
        inspector.getTables((Statement) select);
        inspector.rethrow();

        if (inspector.realTables.isEmpty())
            throw new ValidationException("NO_TABLES", "Query must read from at least one allowed table.");

        // Columns: resolve the qualifier (alias or table name) to a real table when possible.
        Set<String> restricted = new HashSet<>();
        for (String t : inspector.realTables) if (policy.hasBlockedColumns(t)) restricted.add(t);
        for (Column col : inspector.columns) {
            String name = Policy.norm(col.getColumnName());
            Table qual = col.getTable();
            String q = qual == null || qual.getName() == null ? "" : Policy.norm(qual.getName());
            Set<String> candidates = q.isEmpty() ? restricted
                    : inspector.aliasToTable.containsKey(q) ? Set.of(inspector.aliasToTable.get(q)) : Set.of();
            for (String t : candidates) {
                if (!policy.columnAllowed(t, name))
                    throw new ValidationException("COLUMN_NOT_ALLOWED", "Column '" + t + "." + name + "' is restricted.");
            }
        }
        for (String qualifier : inspector.stars) {
            Set<String> hit = qualifier.isEmpty() ? restricted
                    : inspector.aliasToTable.containsKey(qualifier) ? Set.of(inspector.aliasToTable.get(qualifier)) : Set.of();
            if (hit.stream().anyMatch(policy::hasBlockedColumns))
                throw new ValidationException("STAR_ON_RESTRICTED",
                        "SELECT * is not allowed on tables with restricted columns; list columns explicitly.");
        }

        // Token-level backstop: the AST visitor is table-oriented and may skip clauses such as
        // ORDER BY, so every identifier in the SQL text is checked as well (strings/comments removed).
        List<String[]> tokens = identifiers(sql);
        for (String[] tok : tokens) {
            String id = tok[0];
            if (tok[1].equals("call") && policy.functionBlocked(id))
                throw new ValidationException("FUNCTION_NOT_ALLOWED", "Function '" + id + "' is not allowed.");
            for (String t : restricted) {
                if (!policy.columnAllowed(t, id))
                    throw new ValidationException("COLUMN_NOT_ALLOWED", "Column '" + t + "." + id + "' is restricted.");
            }
            if (!policy.tableAllowed(id) && KNOWN_SENSITIVE.contains(id) && !inspector.cteNames.contains(id))
                throw new ValidationException("TABLE_NOT_ALLOWED", "Table '" + id + "' is not accessible.");
        }

        if (inspector.joins > policy.maxJoins())
            throw new ValidationException("TOO_COMPLEX", "Query uses " + inspector.joins + " joins (max " + policy.maxJoins() + ").");
        if (inspector.selects > policy.maxSelects())
            throw new ValidationException("TOO_COMPLEX", "Query has " + inspector.selects + " SELECT blocks (max " + policy.maxSelects() + ").");

        // LIMIT: clamp on a top-level plain SELECT, otherwise wrap the whole query.
        List<String> warnings = new ArrayList<>();
        String out;
        if (select instanceof PlainSelect ps && ps.getFetch() == null && ps.getOffset() == null) {
            Long current = null;
            Limit limit = ps.getLimit();
            if (limit != null && limit.getRowCount() instanceof LongValue lv && limit.getOffset() == null) current = lv.getValue();
            if (current == null || current > policy.maxRows()) {
                if (limit != null && limit.getOffset() != null) {
                    out = wrap(ps.toString(), warnings);
                } else {
                    Limit l = new Limit();
                    l.setRowCount(new LongValue(policy.maxRows()));
                    ps.setLimit(l);
                    warnings.add("LIMIT set to " + policy.maxRows() + ".");
                    out = ps.toString();
                }
            } else {
                out = ps.toString();
            }
        } else {
            out = wrap(select.toString(), warnings);
        }
        return new ValidatedQuery(out, new TreeSet<>(inspector.realTables), warnings);
    }

    /** Relation names that must never appear, even where the AST walk might not look. */
    private static final Set<String> KNOWN_SENSITIVE = Set.of(
            "api_keys", "pg_authid", "pg_shadow", "pg_user", "pg_roles", "pg_settings", "pg_stat_activity",
            "pg_file_settings", "pg_hba_file_rules", "pg_largeobject", "pg_user_mappings", "information_schema");

    private static final java.util.regex.Pattern NOISE = java.util.regex.Pattern.compile(
            "'(?:[^']|'')*'|\\$([A-Za-z_]*)\\$.*?\\$\\1\\$|--[^\\n]*|/\\*.*?\\*/", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern IDENT = java.util.regex.Pattern.compile(
            "\"((?:[^\"]|\"\")+)\"|([A-Za-z_][A-Za-z0-9_$]*)");

    /** Identifiers in the SQL text as {name, "call"|"ref"}, with literals and comments removed. */
    static List<String[]> identifiers(String sql) {
        String clean = NOISE.matcher(sql).replaceAll(" ");
        List<String[]> out = new ArrayList<>();
        var m = IDENT.matcher(clean);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1).replace("\"\"", "\"") : m.group(2);
            int i = m.end();
            while (i < clean.length() && Character.isWhitespace(clean.charAt(i))) i++;
            boolean call = i < clean.length() && clean.charAt(i) == '(';
            out.add(new String[]{Policy.norm(name), call ? "call" : "ref"});
        }
        return out;
    }

    private String wrap(String sql, List<String> warnings) {
        warnings.add("Query wrapped with LIMIT " + policy.maxRows() + ".");
        return "SELECT * FROM (" + sql + ") AS _q LIMIT " + policy.maxRows();
    }

    /** Walks the whole AST once, collecting tables, columns, stars, functions and complexity. */
    private final class Inspector extends TablesNamesFinder<Void> {
        final Set<String> cteNames = new HashSet<>();
        final Set<String> realTables = new HashSet<>();
        final Map<String, String> aliasToTable = new HashMap<>();
        final List<Column> columns = new ArrayList<>();
        final Set<String> stars = new HashSet<>();
        int joins;
        int selects;
        ValidationException error;

        Inspector() {
            init(false);
        }

        void fail(String code, String message) {
            if (error == null) error = new ValidationException(code, message);
        }

        void rethrow() throws ValidationException {
            if (error != null) throw error;
        }

        private void checkSelect(Select s) {
            if (s.getWithItemsList() != null) {
                for (WithItem<?> w : s.getWithItemsList()) {
                    if (w.getAlias() != null) cteNames.add(Policy.norm(w.getAlias().getName()));
                }
            }
            if (s.getForMode() != null || s.getForUpdateTable() != null || s.getForClause() != null)
                fail("NOT_READ_ONLY", "Row-locking clauses (FOR UPDATE/SHARE) are not allowed.");
        }

        @Override
        public <S> Void visit(PlainSelect ps, S context) {
            selects++;
            checkSelect(ps);
            if (ps.getIntoTables() != null && !ps.getIntoTables().isEmpty())
                fail("NOT_READ_ONLY", "SELECT INTO is not allowed.");
            if (ps.getJoins() != null) joins += ps.getJoins().size();
            // `*` / `t.*` as a select item (COUNT(*) is a function argument, not a select item).
            if (ps.getSelectItems() != null) {
                for (var item : ps.getSelectItems()) {
                    if (item.getExpression() instanceof AllTableColumns atc)
                        stars.add(atc.getTable() == null ? "" : Policy.norm(atc.getTable().getName()));
                    else if (item.getExpression() instanceof AllColumns) stars.add("");
                }
            }
            return super.visit(ps, context);
        }

        @Override
        public <S> Void visit(ParenthesedSelect ps, S context) {
            checkSelect(ps);
            return super.visit(ps, context);
        }

        @Override
        public <S> Void visit(net.sf.jsqlparser.statement.select.SetOperationList list, S context) {
            checkSelect(list);
            return super.visit(list, context);
        }

        @Override
        public <S> Void visit(Table table, S context) {
            String name = Policy.norm(table.getName());
            String schema = Policy.norm(table.getSchemaName());
            boolean qualified = !schema.isEmpty() || table.getDatabase() != null && table.getDatabase().getDatabaseName() != null;
            if (!qualified && cteNames.contains(name)) {
                if (table.getAlias() != null) aliasToTable.put(Policy.norm(table.getAlias().getName()), "cte:" + name);
                return super.visit(table, context);
            }
            if (qualified && !schema.equals(policy.schema()))
                fail("TABLE_NOT_ALLOWED", "Table '" + table.getFullyQualifiedName() + "' is not accessible.");
            else if (!policy.tableAllowed(name))
                fail("TABLE_NOT_ALLOWED", "Table '" + name + "' is not accessible.");
            realTables.add(name);
            aliasToTable.put(name, name);
            if (table.getAlias() != null) aliasToTable.put(Policy.norm(table.getAlias().getName()), name);
            return super.visit(table, context);
        }

        @Override
        public <S> Void visit(Column column, S context) {
            columns.add(column);
            return super.visit(column, context);
        }

        @Override
        public <S> Void visit(Function function, S context) {
            String name = function.getName() == null ? "" : function.getName();
            if (policy.functionBlocked(name)) fail("FUNCTION_NOT_ALLOWED", "Function '" + name + "' is not allowed.");
            return super.visit(function, context);
        }

        @Override
        public <S> Void visit(TableFunction tableFunction, S context) {
            fail("TABLE_NOT_ALLOWED", "Table functions are not allowed in FROM.");
            return super.visit(tableFunction, context);
        }
    }
}
