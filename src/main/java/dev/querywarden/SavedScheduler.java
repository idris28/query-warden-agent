package dev.querywarden;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs saved questions when their schedule says so (no Claude calls), records each run, and raises an
 * alert when a question's alert condition starts to hold. Alerts go to {@code alerts.jsonl}, to the web
 * page, and, if {@code QW_ALERT_WEBHOOK} is set, to that URL as {"text": "…"} (a Slack or Teams incoming
 * webhook, for example).
 */
public final class SavedScheduler {

    public record AlertEvent(String at, String savedId, String name, String message, String value) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final SavedQuestions store;
    private final Supplier<QueryWardenAgent> agent;
    private final Path alertLog;
    private final String webhook;
    private final ZoneId zone;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ScheduledExecutorService timer;

    public SavedScheduler(SavedQuestions store, Supplier<QueryWardenAgent> agent, Path alertLog, String webhook, ZoneId zone) {
        this.store = store;
        this.agent = agent;
        this.alertLog = alertLog;
        this.webhook = webhook == null || webhook.isBlank() ? null : webhook.strip();
        this.zone = zone;
    }

    public static SavedScheduler fromEnv(SavedQuestions store, Supplier<QueryWardenAgent> agent) {
        return new SavedScheduler(store, agent, Path.of("alerts.jsonl"), System.getenv("QW_ALERT_WEBHOOK"), ZoneId.systemDefault());
    }

    public SavedQuestions store() {
        return store;
    }

    public boolean webhookConfigured() {
        return webhook != null;
    }

    /** Checks for due questions every minute. */
    public SavedScheduler start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "querywarden-schedule");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                tick(Instant.now());
            } catch (RuntimeException e) {
                System.err.println("schedule: " + e);
            }
        }, 20, 60, TimeUnit.SECONDS);
        return this;
    }

    public void stop() {
        if (timer != null) timer.shutdownNow();
    }

    /** Runs every due saved question. Returns how many ran. */
    public int tick(Instant now) {
        int n = 0;
        for (SavedQuestions.Saved s : store.due(now, zone)) {
            run(s, now);
            n++;
        }
        return n;
    }

    /** Runs one saved question as a scheduled run: records the result and raises its alert if it starts to hold. */
    public QueryWardenAgent.Result run(SavedQuestions.Saved s, Instant now) {
        QueryWardenAgent.Result r = agent.get().runSaved(s.question(), s.sql(), "schedule");
        String at = now.toString();
        try {
            if (r.status() != QueryWardenAgent.Status.OK) {
                store.update(s.id(), x -> x.withRun(at, "failed", x.lastValue(), x.alerting(), r.message()));
                return r;
            }
            SavedQuestions.Check c = SavedQuestions.check(s, r.result());
            boolean starts = c.fires() && (!s.alerting() || s.alert().op().equals("changes"));
            store.update(s.id(), x -> x.withRun(at, "ok", c.value(), c.fires(), c.fires() ? c.message() : ""));
            if (starts) raise(new AlertEvent(at, s.id(), s.name(), c.message(), c.value()));
        } catch (IOException e) {
            System.err.println("schedule: can't record the run of " + s.id() + ": " + e.getMessage());
        }
        return r;
    }

    private synchronized void raise(AlertEvent e) throws IOException {
        System.out.println("ALERT " + e.message());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", e.at());
        m.put("saved_id", e.savedId());
        m.put("name", e.name());
        m.put("message", e.message());
        m.put("value", e.value());
        Files.writeString(alertLog, JSON.writeValueAsString(m) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (webhook != null) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("text", "QueryWarden alert: " + e.message()))))
                        .build();
                HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() >= 300) System.err.println("schedule: the alert webhook answered " + resp.statusCode());
            } catch (Exception ex) {
                System.err.println("schedule: couldn't send the alert to the webhook: " + ex.getMessage());
            }
        }
    }

    /** The most recent alerts, newest first. */
    public synchronized List<AlertEvent> recentAlerts(int limit) {
        List<AlertEvent> out = new ArrayList<>();
        if (!Files.exists(alertLog)) return out;
        try {
            List<String> lines = Files.readAllLines(alertLog);
            for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
                if (lines.get(i).isBlank()) continue;
                JsonNode n = JSON.readTree(lines.get(i));
                out.add(new AlertEvent(n.path("at").asText(), n.path("saved_id").asText(), n.path("name").asText(),
                        n.path("message").asText(), n.path("value").asText()));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("schedule: can't read " + alertLog + ": " + e.getMessage());
        }
        return out;
    }
}
