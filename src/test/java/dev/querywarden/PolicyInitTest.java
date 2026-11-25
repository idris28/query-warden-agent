package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** `querywarden init`: a first policy for any database, with nothing about the demo built in. */
class PolicyInitTest {

    static Map<String, List<String>> schema() {
        Map<String, List<String>> t = new LinkedHashMap<>();
        t.put("patients", List.of("id", "full_name", "date_of_birth", "email", "ward"));
        t.put("visits", List.of("id", "patient_id", "visited_at", "cost"));
        t.put("staff", List.of("id", "name", "salary", "home_address", "mobile_phone"));
        t.put("user_sessions", List.of("id", "token"));
        t.put("api_keys", List.of("id", "secret"));
        t.put("schema_migrations", List.of("version"));
        return t;
    }

    @Test
    void guessesFromNamesForAnyKindOfData() throws Exception {
        var d = PolicyInit.draft("clinic", schema());
        assertEquals(List.of("patients", "visits", "staff"), d.allowed().stream().map(PolicyInit.Table::name).toList());
        assertEquals(List.of("user_sessions", "api_keys", "schema_migrations"), d.leftOut());
        assertEquals(Map.of("patients", List.of("date_of_birth"), "staff", List.of("salary")), d.blocked());
        assertEquals(Set.of("email", "home_address", "mobile_phone"), d.masked());

        String yaml = PolicyInit.toYaml(d, "schema 'clinic' of localhost/clinic");
        Policy p = Policy.parse(yaml, "init");   // what init writes is a valid policy
        assertEquals("clinic", p.schema());
        assertEquals(Set.of("patients", "visits", "staff"), p.allowedTables());
        assertFalse(p.columnAllowed("staff", "salary"));
        assertTrue(p.isMasked("email"));
        assertTrue(yaml.contains("#  - api_keys"), "left-out tables are listed, so they can be added back");
    }

    @Test
    void partialNamesDontTrigger() {
        var d = PolicyInit.draft("x", Map.of("products", List.of("passenger_count", "tokenized", "dobby", "pass")));
        assertTrue(d.blocked().isEmpty(), d.blocked().toString());
        assertTrue(d.masked().isEmpty());
    }

    @Test
    void nothingAboutTheDemoIsBuiltIn() {
        Policy d = Policy.defaults();
        assertTrue(d.allowedTables().isEmpty());
        assertTrue(d.blockedColumns().isEmpty());
        assertTrue(d.maskedColumns().isEmpty());
        assertEquals("public", d.schema());
        var e = assertThrows(Policy.PolicyException.class, () -> Policy.parse("share_results_with_ai: false", "t"));
        assertTrue(e.getMessage().contains("allowed_tables must list"), e.getMessage());
        assertEquals(Set.of("orders"), assertDoesNotThrow(() -> Policy.parse("allowed_tables: [orders]", "t")).allowedTables());
    }

    static <T> T assertDoesNotThrow(org.junit.jupiter.api.function.ThrowingSupplier<T> s) {
        return org.junit.jupiter.api.Assertions.assertDoesNotThrow(s);
    }
}
