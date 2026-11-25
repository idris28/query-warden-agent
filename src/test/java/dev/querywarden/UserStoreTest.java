package dev.querywarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserStoreTest {

    @Test
    void storesOnlyAFingerprintAndAuthenticates(@TempDir Path dir) throws Exception {
        UserStore store = new UserStore(dir.resolve("users.json"));
        String token = store.add("alice");
        assertTrue(token.startsWith("qw_") && token.length() > 40);
        String file = Files.readString(dir.resolve("users.json"));
        assertFalse(file.contains(token), "the token itself is never written to disk");
        assertTrue(file.contains(UserStore.sha256(token)));
        assertEquals(Optional.of("alice"), store.authenticate(token));
        assertEquals(Optional.empty(), store.authenticate(token + "x"));
        assertEquals(Optional.empty(), store.authenticate(""));
        assertEquals(Optional.empty(), store.authenticate(null));
    }

    @Test
    void namesAreValidatedAndUnique(@TempDir Path dir) throws Exception {
        UserStore store = new UserStore(dir.resolve("users.json"));
        store.add("bob.smith");
        assertThrows(IllegalArgumentException.class, () -> store.add("bob.smith"));
        assertThrows(IllegalArgumentException.class, () -> store.add("Bob Smith"));
        assertThrows(IllegalArgumentException.class, () -> store.add("../etc"));
        assertThrows(IllegalArgumentException.class, () -> store.add(""));
    }

    @Test
    void removingSomeoneWorksImmediatelyEvenForAnotherProcess(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("users.json");
        UserStore server = new UserStore(f);
        UserStore cli = new UserStore(f);            // `querywarden users remove` runs as a separate process
        String token = cli.add("carol");
        assertEquals(Optional.of("carol"), server.authenticate(token));
        Thread.sleep(20);
        assertTrue(cli.remove("carol"));
        assertEquals(Optional.empty(), server.authenticate(token), "the running server notices the change");
        assertFalse(cli.remove("carol"));
    }

    @Test
    void tokensAreUniquePerPerson(@TempDir Path dir) throws Exception {
        UserStore store = new UserStore(dir.resolve("users.json"));
        String a = store.add("a"), b = store.add("b");
        assertFalse(a.equals(b));
        assertEquals(Optional.of("b"), store.authenticate(b));
        assertEquals(2, store.list().size());
    }
}
