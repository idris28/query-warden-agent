# Plan: QueryWarden web page

Status: built and tested (2026-11-25). Second round added: examples from `policy.yaml` or Claude, live
progress, result summaries and suggested follow-ups, sorting, filtering and chart controls.
Next: access tokens for network use, follow-up questions with memory, keeping earlier answers on the page.

**Decision (2026-11-25):** first version is for one person on this Mac only. The server listens
on this computer only and refuses any other address. Access tokens and network use come later,
once the agent has been tested properly.

## Goal

A web page where someone types a question and gets the answer as a table and a chart, without a
terminal. It uses the same agent and guardrails as the command line. Nothing about the safety
model changes: the browser never talks to the database or to Claude directly.

```
browser ──HTTP──► QueryWarden server (new `serve` command)
                     └─► QueryWardenAgent ─► Claude
                                          └─► SqlValidator ─► SafeExecutor ─► Postgres (as qw_agent)
```

## What the first version includes

- A question box with a few example questions to click
- The answer as a table, with a chart when the data suits one
- The SQL and Claude's explanation, in a collapsible "How this was answered" section
- Clear messages when a question is declined or blocked, using the agent's own wording
- Warnings shown under the result (rows cut off, values masked)
- Download the result as CSV
- A "Data available" panel listing the tables and columns Claude can use
- Recent questions in a sidebar, kept only in that browser
- A loading state while Claude works (typically 4–6 seconds)

**Not in the first version** (planned later): follow-up questions ("now only Germany"), streaming
progress, logins and per-person permissions, saving or sharing results.

## Technology choices

| Need | Choice | Why | Alternatives considered |
|---|---|---|---|
| Web server | **Javalin 7.2.4** | Small library (embedded Jetty), a few lines per endpoint, fits the existing single-jar CLI. 7.2.4 is from October 2026; 7.3.0 is only days old | **Spring Boot 4**: far more framework than a few endpoints need, larger jar, slower start. **JDK built-in HttpServer**: no dependency, but you hand-write routing, static files and error handling |
| Page | **Plain HTML, CSS, JavaScript** (no build step) | One page with a form, a table and a chart. Served from inside the jar, so no Node.js or second project | **React/Vue**: a build toolchain and second project for one screen. Worth it only if the UI grows a lot |
| Charts | **Chart.js 4.6.1**, copied into the jar | Well-known, small, handles bar and line charts. Bundled so the page works offline and loads no third-party code | Plain SVG by hand: more code. ECharts: larger than needed |
| Database connections | **HikariCP 7.1.0** pool | Today every query opens a new connection. Fine for a terminal, slow and wasteful with several users | Keep `DriverManager`: acceptable for a single local user, but no limit on open connections |

## Server: endpoints

New command: `java -jar target/querywarden.jar serve [--port 8080]`

| Endpoint | Purpose |
|---|---|
| `GET /` | The page (and its CSS, JS and Chart.js) from `src/main/resources/web/` |
| `POST /api/ask` | Body `{"question": "..."}`. Runs the agent and returns the result as JSON (below) |
| `GET /api/schema` | Tables and columns Claude can see (same as the `schema` command), for the "Data available" panel |
| `GET /api/health` | Database reachable and policy loaded, for checking the server is up |

`POST /api/ask` response:

```json
{
  "status": "ok",
  "message": "",
  "sql": "SELECT status, COUNT(*) AS order_count FROM orders GROUP BY status ...",
  "explanation": "Counts the number of orders for each order status ...",
  "columns": [
    {"name": "status", "type": "text"},
    {"name": "order_count", "type": "number"}
  ],
  "rows": [["delivered", 326], ["shipped", 100], ["cancelled", 92], ["pending", 82]],
  "truncated": false,
  "warnings": [],
  "attempts": 1,
  "elapsed_ms": 4310
}
```

`status` is one of `ok`, `unanswerable`, `blocked`, `failed`. The page decides what to show from it.

## Changes needed in existing code

Found by reading the code paths the server would call:

1. **Typed result values** (`SafeExecutor`). Numbers are currently turned into text (to tidy
   `16360.50` into `16360.5`), and dates come back as Java date objects. A chart needs real
   numbers and readable dates. Change: keep numbers as numbers, convert dates and timestamps to
   ISO text (`2025-08-21`), and report each column's kind (`number`, `date`, `text`, `boolean`)
   from the database metadata. The CLI table keeps formatting them as it does now.
2. **Connection pool** (`Database`, `SafeExecutor`). Use one HikariCP pool for the agent user, with
   a small maximum (e.g. 5 connections). The per-query settings (read-only, timeout, rollback)
   stay exactly as they are.
3. **Audit log safe for parallel requests** (`QueryWardenAgent.audit`). Two requests finishing at
   the same moment could interleave lines. Make the write synchronized, and add the client address
   and total time to each entry.
4. **One agent, shared by all requests.** The agent and Claude client hold no per-question state
   (each question opens its own conversation), so one instance can serve concurrent requests. A
   test will cover parallel questions.

## Page layout

```
┌──────────────────────────────────────────────────────────────────────┐
│ QueryWarden                                         Data available ▸ │
├───────────────┬──────────────────────────────────────────────────────┤
│ Recent        │  ┌────────────────────────────────────────┐ ┌─────┐ │
│ • Orders per  │  │ Ask a question about your data…        │ │ Ask │ │
│   status      │  └────────────────────────────────────────┘ └─────┘ │
│ • Top 5       │  Try: [Orders per status] [Monthly revenue 2025] …   │
│   customers   │                                                      │
│               │  ┌───────────── chart (bar / line) ─────────────┐    │
│               │  │  ▇▇▇▇▇▇▇▇▇▇                                  │    │
│               │  │  ▇▇▇▇                                        │    │
│               │  └──────────────────────────────────────────────┘    │
│               │  status    │ order_count            [Download CSV]   │
│               │  delivered │ 326                                     │
│               │  …                                                   │
│               │  ▸ How this was answered (SQL + explanation)         │
│               │  note: 2 sensitive values hidden                     │
└───────────────┴──────────────────────────────────────────────────────┘
```

Works on a phone (sidebar folds away), and in light and dark mode.

### When to draw a chart

Only when it clearly helps; otherwise just the table:

| Result shape | Chart |
|---|---|
| A date/month column + 1–3 number columns | Line chart over time |
| A text column + 1–3 number columns, at most 30 rows | Bar chart |
| A single row, a single value, more than 3 number columns, or anything else | Table only |

## Security

The page is a door to the database and to paid API calls, so it gets its own protections on top
of the existing guardrails:

| Risk | Protection |
|---|---|
| Someone else on the network uses it | Listens on `127.0.0.1` only, and refuses to start on any other address (first version). Requests whose `Host` header isn't `localhost`/`127.0.0.1` are rejected, which also blocks DNS-rebinding tricks. Network access with a token comes later |
| Large bills or overload | Rate limit per client (e.g. 10 questions/minute) and at most 4 questions in progress at once; extra requests get a clear "busy, try again" message |
| Malicious content in questions or data (XSS) | All text is inserted with `textContent`, never as HTML. A strict Content-Security-Policy (`default-src 'self'`) blocks any injected or outside script |
| Other websites calling the API from a visitor's browser | No cross-origin access (no CORS headers); the API accepts only JSON bodies |
| Oversized requests | Request body capped at a few KB; question length already limited by `policy.yaml` |
| Leaking internals | Errors return the agent's user-facing message only, never stack traces. The API key stays on the server |
| Traceability | Audit log records the client address for each question |

## Testing

- **API tests (JUnit, Testcontainers Postgres, fake Claude):** each status (`ok`, `unanswerable`,
  `blocked`, `failed`); typed numbers and ISO dates in JSON; masking still applied; oversize and
  non-JSON bodies rejected; rate limit and busy limit; non-local `Host` headers and cross-site `Origin`s rejected;
  security headers present; several questions in parallel.
- **XSS test:** a question and a database value containing `<script>` and `<img onerror>` come back
  as plain text.
- **Chart rules:** kept in one small JavaScript function and checked in the browser step below.
  The Maven build has no JavaScript test runner, and adding one isn't worth it for one function.
- **In the browser:** load the page against the demo database, ask the example questions, check
  the table, chart, declined messages, CSV download, phone width and dark mode, with screenshots.
- **Live Claude check:** a handful of real questions once everything else passes.

## Steps and rough size

| # | Step | Size |
|---|---|---|
| 1 | Typed result values, connection pool, safe audit log (with tests) | Medium |
| 2 | `serve` command with Javalin: endpoints, JSON, security headers, limits, token (with API tests) | Medium |
| 3 | The page: question box, examples, table, SQL section, messages, CSV, recent questions, data panel | Medium |
| 4 | Charts with Chart.js and the rules above | Small |
| 5 | Browser check, live Claude check, README section, commit | Small |

Each step ends with all tests passing and can be committed on its own.

## Open questions

1. ~~Who will use it?~~ Decided: only you on this Mac for now (see top).
2. **Port:** 8080 by default, changeable with `--port`.
3. **Example questions:** demo-data examples for now; moving them into `policy.yaml` can come later.
