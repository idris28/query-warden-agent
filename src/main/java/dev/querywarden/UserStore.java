package dev.querywarden;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * People allowed to use the shared web page, each with their own access token.
 *
 * <p>Only a SHA-256 fingerprint of each token is stored (in {@code users.json}, or the file in
 * {@code QW_USERS_FILE}); the token itself is shown once, when the person is added. The file is
 * re-read when it changes, so removing someone takes effect immediately, without a restart.
 */
public final class UserStore {

    /** {@code reviewer}: may verify answers and handle feedback on the shared page. */
    public record User(String name, String tokenSha256, String created, boolean reviewer) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,39}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private List<User> users = List.of();
    private long loadedModified = -1;

    public UserStore(Path file) {
        this.file = file;
    }

    public static UserStore fromEnv() {
        String f = System.getenv("QW_USERS_FILE");
        return new UserStore(Path.of(f == null || f.isBlank() ? "users.json" : f));
    }

    public Path file() {
        return file;
    }

    public synchronized List<User> list() throws IOException {
        reloadIfChanged();
        return users;
    }

    /** Adds a person and returns their new token. The token is not stored and can't be shown again. */
    public synchronized String add(String name) throws IOException {
        return add(name, false);
    }

    public synchronized boolean isReviewer(String name) {
        try {
            reloadIfChanged();
        } catch (IOException e) {
            return false;
        }
        return users.stream().anyMatch(u -> u.name().equals(name) && u.reviewer());
    }

    public synchronized String add(String name, boolean reviewer) throws IOException {
        reloadIfChanged();
        if (!NAME.matcher(name).matches())
            throw new IllegalArgumentException("Use 1–40 lowercase letters, digits, '.', '_' or '-' for the name.");
        if (users.stream().anyMatch(u -> u.name().equals(name)))
            throw new IllegalArgumentException("'" + name + "' already exists. Remove them first to issue a new token.");
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "qw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        List<User> next = new ArrayList<>(users);
        next.add(new User(name, sha256(token), Instant.now().toString(), reviewer));
        save(next);
        return token;
    }

    public synchronized boolean remove(String name) throws IOException {
        reloadIfChanged();
        List<User> next = users.stream().filter(u -> !u.name().equals(name)).toList();
        if (next.size() == users.size()) return false;
        save(next);
        return true;
    }

    /** The person a token belongs to. Compares fingerprints in constant time. */
    public synchronized Optional<String> authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > 200) return Optional.empty();
        try {
            reloadIfChanged();
        } catch (IOException e) {
            return Optional.empty();   // unreadable file: nobody gets in
        }
        byte[] given = sha256(token).getBytes(StandardCharsets.US_ASCII);
        String match = null;
        for (User u : users) {
            if (MessageDigest.isEqual(given, u.tokenSha256().getBytes(StandardCharsets.US_ASCII))) match = u.name();
        }
        return Optional.ofNullable(match);
    }

    private void reloadIfChanged() throws IOException {
        if (!Files.exists(file)) {
            users = List.of();
            loadedModified = -1;
            return;
        }
        long modified = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
        if (modified == loadedModified) return;
        JsonNode root = JSON.readTree(file.toFile());
        List<User> out = new ArrayList<>();
        for (JsonNode u : root.path("users")) {
            out.add(new User(u.path("name").asText(), u.path("token_sha256").asText(), u.path("created").asText(),
                    u.path("reviewer").asBoolean(false)));
        }
        users = List.copyOf(out);
        loadedModified = modified;
    }

    private void save(List<User> next) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (User u : next) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", u.name());
            m.put("token_sha256", u.tokenSha256());
            m.put("created", u.created());
            m.put("reviewer", u.reviewer());
            rows.add(m);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("users", rows)));
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system (Windows): fine
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        users = List.copyOf(next);
        loadedModified = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
