package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Static guardrails only: no database, no LLM. */
class SqlValidatorTest {

    private final Policy policy = Policy.defaults();
    private final SqlValidator validator = new SqlValidator(policy);

    @ParameterizedTest(name = "{1}: {0}")
    @CsvSource(delimiter = '|', value = {
            "DROP TABLE customers | NOT_READ_ONLY",
            "DELETE FROM orders | NOT_READ_ONLY",
            "UPDATE products SET price = 0 | NOT_READ_ONLY",
            "INSERT INTO orders VALUES (1,1,'2024-01-01','pending') | NOT_READ_ONLY",
            "CREATE TABLE x (a int) | NOT_READ_ONLY",
            "SET statement_timeout = 0 | NOT_READ_ONLY",
            "SELECT name INTO stolen FROM customers | NOT_READ_ONLY",
            "SELECT name FROM customers FOR UPDATE | NOT_READ_ONLY",
            "SELECT 1 FROM customers; DROP TABLE customers | MULTI_STATEMENT",
            "SELECT name FROM customers; SELECT name FROM products | MULTI_STATEMENT",
            "SELECT secret FROM api_keys | TABLE_NOT_ALLOWED",
            "SELECT rolname FROM pg_catalog.pg_roles | TABLE_NOT_ALLOWED",
            "SELECT rolname FROM pg_authid | TABLE_NOT_ALLOWED",
            "SELECT table_name FROM information_schema.tables | TABLE_NOT_ALLOWED",
            "SELECT name FROM public.customers | TABLE_NOT_ALLOWED",
            "SELECT c.name FROM customers c JOIN api_keys k ON k.id = c.id | TABLE_NOT_ALLOWED",
            "SELECT name FROM customers UNION SELECT secret FROM api_keys | TABLE_NOT_ALLOWED",
            "SELECT name FROM customers WHERE id IN (SELECT id FROM api_keys) | TABLE_NOT_ALLOWED",
            "SELECT * FROM pg_ls_dir('.') | TABLE_NOT_ALLOWED",
            "SELECT name, salary FROM employees | COLUMN_NOT_ALLOWED",
            "SELECT e.name FROM employees e WHERE e.ssn LIKE '1%' | COLUMN_NOT_ALLOWED",
            "SELECT AVG(salary) FROM employees | COLUMN_NOT_ALLOWED",
            "SELECT employees.name FROM employees ORDER BY employees.salary | COLUMN_NOT_ALLOWED",
            "WITH x AS (SELECT ssn AS s FROM employees) SELECT s FROM x | COLUMN_NOT_ALLOWED",
            "SELECT * FROM employees | STAR_ON_RESTRICTED",
            "SELECT e.* FROM employees e | STAR_ON_RESTRICTED",
            "SELECT pg_sleep(10) FROM customers | FUNCTION_NOT_ALLOWED",
            "SELECT pg_read_file('/etc/passwd') FROM customers | FUNCTION_NOT_ALLOWED",
            "SELECT set_config('statement_timeout', '0', false) FROM customers | FUNCTION_NOT_ALLOWED",
            "SELECT query_to_xml('select * from api_keys', true, true, '') FROM customers | FUNCTION_NOT_ALLOWED",
            "SELECT pg_catalog.pg_sleep(1) FROM customers | FUNCTION_NOT_ALLOWED",
            "SELECT name FROM customers ORDER BY pg_sleep(10) | FUNCTION_NOT_ALLOWED",
            "SELECT name FROM customers ORDER BY (SELECT max(secret) FROM api_keys) | TABLE_NOT_ALLOWED",
            "SELECT department FROM employees GROUP BY department HAVING max(salary) > 1 | COLUMN_NOT_ALLOWED",
            "SELECT department FROM employees ORDER BY \"salary\" | COLUMN_NOT_ALLOWED",
            "SELECT 1 | NO_TABLES",
            "SELEC name FROM customers | PARSE_ERROR",
    })
    void blocks(String sql, String code) {
        SqlValidator.ValidationException e = assertThrows(SqlValidator.ValidationException.class,
                () -> validator.validate(sql));
        assertEquals(code, e.code(), e.getMessage());
    }

    @Test
    void emptyIsBlocked() {
        assertEquals("EMPTY", assertThrows(SqlValidator.ValidationException.class, () -> validator.validate("  ")).code());
    }

    @Test
    void addsLimitWhenMissing() throws Exception {
        var vq = validator.validate("SELECT name, country FROM customers");
        assertTrue(vq.sql().endsWith("LIMIT " + policy.maxRows()), vq.sql());
        assertEquals(java.util.Set.of("customers"), vq.tables());
    }

    @Test
    void clampsLargeLimitButKeepsSmallOne() throws Exception {
        assertTrue(validator.validate("SELECT name FROM products LIMIT 100000").sql().endsWith("LIMIT " + policy.maxRows()));
        assertTrue(validator.validate("SELECT name FROM products LIMIT 5").sql().endsWith("LIMIT 5"));
    }

    @Test
    void allowsSafeQueries() throws Exception {
        validator.validate("SELECT department, COUNT(*) AS n FROM employees GROUP BY department");
        validator.validate("SELECT * FROM customers");
        validator.validate("SELECT name FROM shop.products");
        validator.validate("SELECT name FROM customers WHERE name <> 'salary'");  // literal, not a column
        validator.validate("""
                WITH spend AS (
                  SELECT o.customer_id, SUM(oi.quantity * oi.unit_price) AS total
                  FROM orders o JOIN order_items oi ON oi.order_id = o.id GROUP BY o.customer_id)
                SELECT c.name, s.total FROM spend s JOIN customers c ON c.id = s.customer_id
                ORDER BY s.total DESC LIMIT 5""");
    }

    @Test
    void wrapsSetOperations() throws Exception {
        var vq = validator.validate("SELECT name FROM customers UNION SELECT name FROM products");
        assertTrue(vq.sql().startsWith("SELECT * FROM (") && vq.sql().endsWith("LIMIT " + policy.maxRows()), vq.sql());
    }

    @Test
    void enforcesJoinLimit() {
        StringBuilder sql = new StringBuilder("SELECT c.name FROM customers c");
        for (int i = 0; i < 6; i++) sql.append(" JOIN orders o").append(i).append(" ON o").append(i).append(".customer_id = c.id");
        assertEquals("TOO_COMPLEX",
                assertThrows(SqlValidator.ValidationException.class, () -> validator.validate(sql.toString())).code());
    }
}
