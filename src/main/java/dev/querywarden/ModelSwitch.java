package dev.querywarden;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The models in use while the web server runs, changeable from the Settings panel without a restart.
 * Choices made on the page are kept in {@code .querywarden/models.json} and win over the {@code QW_*}
 * environment variables; "Use the defaults" removes the file. It is both the SQL generator and the AI
 * helper the rest of the server sees, handing each call to the current model.
 */
public final class ModelSwitch implements SqlGenerator, AiHelper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final ModelSettings fromEnv;
    private final Function<ModelSettings, SqlGenerator> sqlFactory;
    private final Function<ModelSettings, AiHelper> helperFactory;
    private volatile ModelSettings current;
    private volatile SqlGenerator sql;
    private volatile AiHelper helper;

    public ModelSwitch(Path file, ModelSettings fromEnv, Function<ModelSettings, SqlGenerator> sqlFactory,
                       Function<ModelSettings, AiHelper> helperFactory) {
        this.file = file;
        this.fromEnv = fromEnv;
        this.sqlFactory = sqlFactory;
        this.helperFactory = helperFactory;
        use(withSaved(fromEnv, file));
    }

    /** The environment's settings with the page's saved choices on top (a broken file is ignored). */
    public static ModelSettings withSaved(ModelSettings env, Path file) {
        if (file == null || !Files.exists(file)) return env;
        try {
            Map<String, String> saved = JSON.readValue(file.toFile(), new TypeReference<Map<String, String>>() { });
            Map<String, String> m = new LinkedHashMap<>();
            m.put("QW_SQL_MODEL", saved.getOrDefault("sql_model", env.sqlModel()));
            m.put("QW_SQL_EFFORT", saved.getOrDefault("sql_effort", env.sqlEffort()));
            m.put("QW_HELPER_MODEL", saved.getOrDefault("helper_model", env.helperModel()));
            m.put("QW_HELPER_EFFORT", saved.getOrDefault("helper_effort", env.helperEffort()));
            return ModelSettings.fromMap(m);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("warning: ignoring " + file + ": " + e.getMessage());
            return env;
        }
    }

    public ModelSettings current() {
        return current;
    }

    public boolean savedOnPage() {
        return file != null && Files.exists(file);
    }

    /** Checks, saves and switches to new settings. Throws IllegalArgumentException for an invalid model or effort. */
    public synchronized ModelSettings save(Map<String, String> values) throws IOException {
        ModelSettings next = ModelSettings.fromMap(Map.of(
                "QW_SQL_MODEL", values.getOrDefault("sql_model", current.sqlModel()),
                "QW_SQL_EFFORT", values.getOrDefault("sql_effort", current.sqlEffort()),
                "QW_HELPER_MODEL", values.getOrDefault("helper_model", current.helperModel()),
                "QW_HELPER_EFFORT", values.getOrDefault("helper_effort", current.helperEffort())));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "sql_model", next.sqlModel(), "sql_effort", next.sqlEffort(),
                "helper_model", next.helperModel(), "helper_effort", next.helperEffort())));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        use(next);
        return next;
    }

    /** Forgets the page's choices: back to the QW_* variables (or the built-in defaults). */
    public synchronized ModelSettings reset() throws IOException {
        if (file != null) Files.deleteIfExists(file);
        use(fromEnv);
        return fromEnv;
    }

    private void use(ModelSettings m) {
        sql = sqlFactory.apply(m);
        helper = helperFactory.apply(m);
        current = m;
    }

    @Override
    public Conversation open(String systemPrompt) {
        return sql.open(systemPrompt);
    }

    @Override
    public List<String> suggestExamples(String schemaText, int count) {
        return helper.suggestExamples(schemaText, count);
    }

    @Override
    public List<String> suggestExamples(String schemaText, int count, List<String> avoid) {
        return helper.suggestExamples(schemaText, count, avoid);
    }

    @Override
    public Insight describe(String schemaText, String question, String sql, List<String> columns, List<List<Object>> rows) {
        return helper.describe(schemaText, question, sql, columns, rows);
    }
}
