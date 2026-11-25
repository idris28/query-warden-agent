// QueryWarden web page.
// Security rule for this file: text from the server (questions, data, messages, SQL, summaries,
// suggestions) is only ever inserted with textContent or as text nodes, never as HTML.
"use strict";

(function () {
  const $ = (id) => document.getElementById(id);
  // Recent questions are kept per database (the server gives each one an anonymous id).
  let RECENT_KEY = "querywarden.recent";
  const RECENT_MAX = 12;

  const form = $("ask-form");
  const input = $("question");
  const button = $("ask-button");
  const statusEl = $("status");
  let chart = null;
  let busy = false;

  // The answer on screen and how it is being viewed.
  const view = {
    question: "",
    data: null,         // the result from the server
    shape: null,        // what can be charted (see analyse)
    mode: "table",      // "bar" | "line" | "table"
    plot: "all",        // "all" or a column index
    sortCol: -1,
    sortDir: 1,
    filter: "",
  };

  // ------------------------------------------------------------------ small DOM helpers
  function el(tag, opts = {}, children = []) {
    const node = document.createElement(tag);
    if (opts.className) node.className = opts.className;
    if (opts.text !== undefined) node.textContent = String(opts.text);
    if (opts.type) node.type = opts.type;
    for (const c of children) node.append(c);
    return node;
  }

  function show(node, visible) { node.hidden = !visible; }

  function setStatus(text, isError = false) {
    statusEl.textContent = text;
    statusEl.classList.toggle("is-error", isError);
  }

  // ------------------------------------------------------------------ sign-in (only when the page is shared)
  // The token lives in this tab's sessionStorage: it is forgotten when the tab closes.
  const TOKEN_KEY = "querywarden.token";
  let signedInHook = null;

  function token() {
    try { return sessionStorage.getItem(TOKEN_KEY); } catch { return null; }
  }

  function setToken(t) {
    try { if (t) sessionStorage.setItem(TOKEN_KEY, t); else sessionStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ }
  }

  // fetch() for the API: adds the access token, and asks for sign-in when the server says so.
  async function api(url, opts = {}) {
    const headers = Object.assign({}, opts.headers || {});
    const t = token();
    if (t) headers.Authorization = "Bearer " + t;
    const res = await fetch(url, Object.assign({}, opts, { headers }));
    if (res.status === 401) {
      let msg = "Sign in with your access token.";
      try { const j = await res.clone().json(); if (j && j.message) msg = j.message; } catch { /* ignore */ }
      showSignIn(t ? msg : "");
    }
    return res;
  }

  function showSignIn(message) {
    const d = $("signin");
    $("signin-error").textContent = message || "";
    show($("signin-error"), Boolean(message));
    if (!d.open) d.showModal();
    $("signin-token").focus();
  }

  $("signin").addEventListener("cancel", (e) => e.preventDefault());   // sign-in can't be skipped
  $("signin-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    setToken($("signin-token").value.trim());
    $("signin-token").value = "";
    const res = await api("/api/me");
    if (res.ok) {
      $("signin").close();
      if (signedInHook) signedInHook();
    }
  });

  $("sign-out").addEventListener("click", () => { setToken(null); location.reload(); });

  async function whoAmI() {
    try {
      const res = await api("/api/me");
      if (!res.ok) return false;
      const me = await res.json();
      canReview = Boolean(me.reviewer);
      feedbackOn = Boolean(me.feedback);
      show($("review-open"), canReview && feedbackOn);
      if (me.sharing && me.name) {
        $("whoami-name").textContent = me.name;
        show($("whoami"), true);
        show($("sign-out"), true);
      }
      return true;
    } catch { return false; }
  }

  // ------------------------------------------------------------------ recent questions (this browser only)
  function loadRecent() {
    try {
      const list = JSON.parse(localStorage.getItem(RECENT_KEY) || "[]");
      return Array.isArray(list) ? list.filter((q) => typeof q === "string") : [];
    } catch { return []; }
  }

  function saveRecent(list) {
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(list)); } catch { /* private mode: ignore */ }
  }

  function remember(question) {
    const list = loadRecent().filter((q) => q !== question);
    list.unshift(question);
    saveRecent(list.slice(0, RECENT_MAX));
    renderRecent();
  }

  function questionButton(q) {
    const b = el("button", { text: q, type: "button" });
    b.addEventListener("click", () => ask(q));
    return b;
  }

  function renderRecent() {
    const list = loadRecent();
    $("recent").replaceChildren(...list.map((q) => el("li", {}, [questionButton(q)])));
    show($("recent-empty"), list.length === 0);
    show($("recent-clear"), list.length > 0);
  }

  $("recent-clear").addEventListener("click", () => { saveRecent([]); renderRecent(); });

  // ------------------------------------------------------------------ available data + examples
  let schemaSnapshot = "";

  // Loads the tables and example questions. Called again every minute: when the server has picked up a
  // change to policy.yaml or the database, the page follows without a reload.
  async function loadSchema(quiet = false) {
    try {
      const res = await api("/api/schema", { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error();
      const data = await res.json();
      const snapshot = JSON.stringify([data.tables, data.examples, data.database_id]);
      if (snapshot === schemaSnapshot) return;
      const firstLoad = schemaSnapshot === "";
      schemaSnapshot = snapshot;
      renderTables(data.tables || []);
      renderExamples(data.examples || []);
      show($("more-examples"), Boolean(data.examples_refreshable));
      if (data.max_question_chars) input.maxLength = data.max_question_chars;
      if (data.database_id) {
        RECENT_KEY = "querywarden.recent." + data.database_id;
        try {   // one-time move of questions saved before recent lists were kept per database
          const legacy = localStorage.getItem("querywarden.recent");
          if (legacy && !localStorage.getItem(RECENT_KEY)) localStorage.setItem(RECENT_KEY, legacy);
          localStorage.removeItem("querywarden.recent");
        } catch { /* ignore */ }
        renderRecent();
      }
      if (!firstLoad && quiet) setStatus("The available data or example questions changed; the lists are up to date.");
    } catch {
      if (!quiet) $("tables").replaceChildren(el("p", { className: "quiet", text: "Couldn't load the list of tables. Is the database running?" }));
    }
  }

  setInterval(() => { if (!document.hidden && !busy) loadSchema(true); }, 60_000);

  $("more-examples").addEventListener("click", async () => {
    const b = $("more-examples");
    b.disabled = true;
    b.textContent = "Thinking of more…";
    try {
      const res = await api("/api/examples/refresh", { method: "POST", headers: { Accept: "application/json" } });
      const data = await res.json();
      if (res.ok && Array.isArray(data.examples)) renderExamples(data.examples);
      else setStatus(data.message || "Couldn't get more examples.", true);
    } catch {
      setStatus("Couldn't get more examples.", true);
    } finally {
      b.disabled = false;
      b.textContent = "More examples";
    }
  });

  function renderTables(tables) {
    const wrap = $("tables");
    if (tables.length === 0) {
      wrap.replaceChildren(el("p", { className: "quiet", text: "No tables are available. Check policy.yaml." }));
      return;
    }
    wrap.replaceChildren(...tables.map((t) => {
      const items = t.columns.map((c) => el("li", {}, [
        el("span", { text: c.name }),
        c.masked ? el("span", { className: "col-masked", text: "hidden in results" })
                 : el("span", { className: "col-type", text: friendlyType(c.type) }),
      ]));
      return el("details", {}, [el("summary", { text: t.name }), el("ul", {}, items)]);
    }));
  }

  function friendlyType(t) {
    if (/int|numeric|double|real|decimal/.test(t)) return "number";
    if (/timestamp/.test(t)) return "date and time";
    if (/date/.test(t)) return "date";
    if (/bool/.test(t)) return "yes/no";
    return "text";
  }

  function renderExamples(examples) {
    $("examples").replaceChildren(...examples.map((q) => el("li", {}, [questionButton(q)])));
    show($("examples-wrap"), examples.length > 0);
  }

  // ------------------------------------------------------------------ conversation memory and earlier answers
  // Answered questions of this conversation, sent with the next question so follow-ups like
  // "now only Germany" work. Kept in this page only; "New conversation" or a reload clears it.
  const conversation = [];
  const EARLIER_MAX = 10;

  function updateConversationUi() {
    show($("new-conversation"), conversation.length > 0);
    input.placeholder = conversation.length > 0
      ? "Ask a follow-up, e.g. “now only for 2024”, or a new question"
      : "Which customers spent the most last year?";
  }

  $("new-conversation").addEventListener("click", () => {
    conversation.length = 0;
    $("earlier-list").replaceChildren();
    show($("earlier"), false);
    show($("answer"), false);
    if (chart) { chart.destroy(); chart = null; }
    view.data = null;
    updateConversationUi();
    setStatus("Started a new conversation.");
    input.focus();
  });

  // Moves the answer on screen into "Earlier in this conversation" as a read-only copy.
  function archiveCurrentAnswer() {
    if (!view.data || $("answer").hidden) return;
    const copy = $("answer").cloneNode(true);
    // The chart becomes a picture (a copied canvas would be blank).
    const canvas = copy.querySelector("canvas");
    if (canvas) {
      if (chart && !$("chart-wrap").hidden) {
        const img = el("img", { className: "chart-image" });
        img.src = chart.toBase64Image();
        img.alt = $("chart-caption").textContent;
        canvas.closest("figure").replaceWith(img);
      } else {
        canvas.closest("figure").remove();
      }
    }
    // Controls only work on the current answer; ids must stay unique.
    for (const sel of [".chart-controls", ".table-tools", "#next", "#progress", "#feedback"]) {
      copy.querySelectorAll(sel).forEach((n) => n.remove());
    }
    copy.removeAttribute("id");
    copy.querySelectorAll("[id]").forEach((n) => n.removeAttribute("id"));
    copy.classList.remove("is-fresh");
    copy.removeAttribute("aria-live");

    const summary = el("summary", {}, [
      el("span", { className: "earlier-q", text: view.question }),
      el("span", { className: "earlier-meta", text: $("answer-summary").textContent }),
    ]);
    const item = el("li", {}, [el("details", {}, [summary, copy])]);
    $("earlier-list").prepend(item);
    while ($("earlier-list").children.length > EARLIER_MAX) $("earlier-list").lastElementChild.remove();
    show($("earlier"), true);
  }

  // ------------------------------------------------------------------ live progress
  const progress = {
    steps: [],
    start() { this.steps = []; this.render(); show($("progress"), true); },
    add(text) {
      for (const s of this.steps) if (s.state === "current") s.state = "done";
      this.steps.push({ text, state: "current" });
      this.render();
    },
    reject(reason) {
      const cur = this.steps.find((s) => s.state === "current");
      if (cur) cur.state = "rejected";
      this.steps.push({ text: "Rejected: " + reason, state: "rejected-note" });
      this.render();
    },
    finish() { show($("progress"), false); this.steps = []; },
    render() {
      $("progress").replaceChildren(...this.steps.map((s) => el("li", {
        text: s.text,
        className: s.state === "rejected-note" ? "is-rejected" : "is-" + s.state,
      })));
    },
  };

  // ------------------------------------------------------------------ asking
  form.addEventListener("submit", (e) => { e.preventDefault(); ask(input.value); });
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); ask(input.value); }
  });

  async function ask(raw) {
    const question = (raw || "").trim();
    if (!question || busy) return;
    busy = true;
    input.value = question;
    button.disabled = true;
    button.textContent = "Asking…";
    setStatus("");
    progress.start();

    let gotResult = false;
    try {
      const res = await api("/api/ask/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/x-ndjson" },
        body: JSON.stringify({ question, history: conversation.slice(-3) }),
      });
      const type = res.headers.get("Content-Type") || "";
      if (!res.ok || !type.includes("ndjson")) {
        // Refused before the agent ran (busy, rate limit, bad request): keep the previous answer.
        let msg = "Something went wrong (HTTP " + res.status + ").";
        try { const j = await res.json(); if (j && j.message) msg = j.message; } catch { /* not JSON */ }
        progress.finish();
        setStatus(msg, true);
        return;
      }
      await readEvents(res.body, (event) => {
        if (event.type === "progress") {
          if (event.stage === "rejected") progress.reject(event.text);
          else if (event.stage === "summarizing") showInsightPending(event.text);
          else progress.add(event.text);
        } else if (event.type === "result") {
          gotResult = true;
          progress.finish();
          remember(question);
          input.value = "";
          archiveCurrentAnswer();
          renderAnswer(question, event);
          if (event.status === "ok" && event.sql) {
            conversation.push({ question: event.interpreted_question || question, sql: event.sql });
            updateConversationUi();
          }
        } else if (event.type === "insights") {
          renderInsights(event);
        }
      });
      if (!gotResult) {
        progress.finish();
        setStatus("The answer was cut off before it arrived. Please try again.", true);
      }
    } catch {
      progress.finish();
      if (!gotResult) setStatus("Couldn't reach QueryWarden. Is `querywarden serve` still running?", true);
    } finally {
      busy = false;
      button.disabled = false;
      button.textContent = "Ask";
      clearInsightPending();
    }
  }

  // Reads newline-delimited JSON from a streamed response, calling onEvent for each line.
  async function readEvents(body, onEvent) {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let nl;
      while ((nl = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, nl).trim();
        buffer = buffer.slice(nl + 1);
        if (!line) continue;
        let event;
        try { event = JSON.parse(line); } catch { continue; }
        if (event && typeof event.type === "string") onEvent(event);
      }
    }
  }

  // ------------------------------------------------------------------ rendering an answer
  function renderAnswer(question, data) {
    Object.assign(view, { question, data, sortCol: -1, sortDir: 1, filter: "" });
    $("filter").value = "";
    const answer = $("answer");
    show(answer, true);   // visible before drawing, so the chart can measure its space
    $("answer-question").textContent = question;
    const understood = (data.interpreted_question || "").trim();
    const same = understood.toLowerCase().replace(/[?.!\s]+$/, "") === question.toLowerCase().replace(/[?.!\s]+$/, "");
    $("answer-interpreted").textContent = understood && !same ? "Understood as: " + understood : "";
    show($("answer-interpreted"), Boolean(understood) && !same);
    const msg = $("answer-message");
    const ok = data.status === "ok";

    if (ok) {
      const n = data.rows.length;
      $("answer-summary").textContent =
        (n === 1 ? "1 row" : n + " rows") + (data.truncated ? " (cut off at the row limit)" : "") +
        ", answered in " + (data.elapsed_ms / 1000).toFixed(1) + " seconds";
      show(msg, false);
    } else {
      $("answer-summary").textContent = data.status === "unanswerable" ? "Not answered"
        : data.status === "blocked" ? "Question blocked" : "Couldn't answer";
      msg.textContent = data.message || "";
      msg.classList.toggle("is-declined", data.status !== "unanswerable");
      show(msg, true);
    }

    show($("answer-insight"), false);
    show($("next"), false);
    view.shape = ok ? analyse(data) : null;
    view.mode = view.shape ? view.shape.defaultMode : "table";
    view.plot = view.shape ? defaultPlot(view.shape, data) : "all";
    renderControls();
    renderTable();
    renderChart();
    renderWarnings(ok ? data.warnings || [] : []);
    renderHow(data);
    renderVerifiedBadge(data);
    renderFeedback(data);

    answer.classList.remove("is-fresh");
    void answer.offsetWidth; // restart the one entrance animation
    answer.classList.add("is-fresh");
  }

  function showInsightPending(text) {
    const p = $("answer-insight");
    p.textContent = text + "…";
    p.classList.add("is-pending");
    show(p, true);
  }

  function clearInsightPending() {
    const p = $("answer-insight");
    if (p.classList.contains("is-pending")) { show(p, false); p.classList.remove("is-pending"); }
  }

  function renderInsights(event) {
    const p = $("answer-insight");
    p.classList.remove("is-pending");
    p.textContent = event.summary || "";
    show(p, Boolean(event.summary));
    const follow = Array.isArray(event.follow_ups) ? event.follow_ups.filter((q) => typeof q === "string") : [];
    $("next-list").replaceChildren(...follow.map((q) => el("li", {}, [questionButton(q)])));
    show($("next"), follow.length > 0);
  }

  function renderWarnings(warnings) {
    const ul = $("warnings");
    ul.replaceChildren(...warnings.map((w) => el("li", { text: w })));
    show(ul, warnings.length > 0);
  }

  // "How this was answered": the explanation is always shown; the SQL stays open or closed the way you
  // last left it (remembered in this browser only).
  const SHOW_SQL_KEY = "querywarden.showSql";

  function sqlOpenPreference() {
    try { return localStorage.getItem(SHOW_SQL_KEY) === "1"; } catch { return false; }
  }

  $("how-details").addEventListener("toggle", (e) => {
    try { localStorage.setItem(SHOW_SQL_KEY, e.target.open ? "1" : "0"); } catch { /* ignore */ }
  });

  function renderHow(data) {
    const how = $("how");
    const hasSql = Boolean(data.sql);
    const rejected = data.rejected_attempts || [];
    show(how, hasSql || rejected.length > 0 || Boolean(data.explanation));
    $("how-explanation").textContent = data.explanation || "";
    show($("how-explanation"), Boolean(data.explanation));
    $("how-corrected").textContent = rejected.length === 1
      ? "Claude's first query was rejected by the safety checks and corrected."
      : rejected.length + " earlier queries were rejected by the safety checks and corrected.";
    show($("how-corrected"), rejected.length > 0 && hasSql);
    $("how-sql").textContent = hasSql ? prettySql(data.sql) : "";
    show($("how-sql").parentElement, hasSql);
    $("how-rejected-list").replaceChildren(...rejected.map((a) =>
      el("li", {}, [el("span", { text: a.reason }), el("code", { text: a.sql })])));
    show($("how-rejected"), rejected.length > 0);
    const details = $("how-details");
    show(details, hasSql || rejected.length > 0);
    $("how-details").querySelector("summary").textContent = hasSql ? "SQL that was run" : "Rejected queries";
    details.open = sqlOpenPreference();   // re-saving the same value via "toggle" is harmless
  }

  // Line breaks before the main clauses so the SQL is readable. Text only; nothing is executed.
  function prettySql(sql) {
    return sql.replace(/\s+(FROM|WHERE|GROUP BY|HAVING|ORDER BY|LIMIT|JOIN|LEFT JOIN|RIGHT JOIN|INNER JOIN|UNION|WITH)\s/g, "\n$1 ");
  }

  // ------------------------------------------------------------------ formatting
  function isNumberType(t) { return t === "number"; }

  // One formatter per column, decided from all its values, so a column reads consistently:
  //   numbers: two decimals if any value has a fraction (money), otherwise whole numbers
  //   dates:   "Jan 2025" when every value is the first of a month, otherwise 2025-08-21
  function columnFormatters(columns, rows) {
    return columns.map((c, i) => {
      const values = rows.map((r) => r[i]).filter((v) => v !== null && v !== undefined);
      if (isNumberType(c.type)) {
        const fractional = values.some((v) => typeof v === "number" && !Number.isInteger(v));
        const opts = fractional ? { minimumFractionDigits: 2, maximumFractionDigits: 2 } : { maximumFractionDigits: 0 };
        return (v) => (typeof v === "number" ? v.toLocaleString(undefined, opts) : String(v));
      }
      if (c.type === "date" || c.type === "datetime") {
        const day = (v) => String(v).slice(0, 10);
        const midnight = values.every((v) => !/T/.test(String(v)) || /T00:00(:00)?$/.test(String(v)));
        const monthly = midnight && values.length > 0 && values.every((v) => /^\d{4}-\d{2}-01/.test(String(v)));
        if (monthly) {
          return (v) => new Date(day(v) + "T00:00:00Z").toLocaleDateString(undefined,
            { month: "short", year: "numeric", timeZone: "UTC" });
        }
        return midnight ? (v) => day(v) : (v) => String(v).replace("T", " ");
      }
      return (v) => String(v);
    }).map((f) => (v) => (v === null || v === undefined ? "—" : f(v)));
  }

  function formatNumber(v) {
    return typeof v === "number" ? v.toLocaleString(undefined, { maximumFractionDigits: 2 }) : String(v);
  }

  // ------------------------------------------------------------------ table: sort and filter
  function visibleRows() {
    const { data, sortCol, sortDir, filter } = view;
    const fmt = columnFormatters(data.columns, data.rows);
    let rows = data.rows;
    const f = filter.trim().toLowerCase();
    if (f) rows = rows.filter((r) => r.some((v, i) => fmt[i](v).toLowerCase().includes(f)));
    if (sortCol >= 0) {
      const numeric = isNumberType(data.columns[sortCol].type);
      rows = [...rows].sort((a, b) => {
        const x = a[sortCol], y = b[sortCol];
        if (x === null || x === undefined) return 1;      // empty values always last
        if (y === null || y === undefined) return -1;
        const c = numeric && typeof x === "number" && typeof y === "number"
          ? x - y : String(x).localeCompare(String(y), undefined, { numeric: true });
        return c * sortDir;
      });
    }
    return { rows, fmt };
  }

  function renderTable() {
    const { data } = view;
    const table = $("table");
    if (!data || data.status !== "ok" || data.columns.length === 0) {
      table.replaceChildren();
      show($("table-wrap"), false);
      show($("table-tools"), false);
      return;
    }
    const { rows, fmt } = visibleRows();
    const head = el("tr", {}, data.columns.map((c, i) => {
      const th = el("th", { className: isNumberType(c.type) ? "num" : "" });
      th.setAttribute("aria-sort", view.sortCol === i ? (view.sortDir > 0 ? "ascending" : "descending") : "none");
      const b = el("button", { text: c.name, type: "button", className: "sort" });
      b.title = "Sort by " + c.name;
      b.addEventListener("click", () => {
        if (view.sortCol === i) view.sortDir = -view.sortDir;
        else { view.sortCol = i; view.sortDir = isNumberType(c.type) ? -1 : 1; }  // numbers: biggest first
        renderTable();
        $("table").querySelectorAll("th button.sort")[i].focus();
      });
      th.append(b);
      return th;
    }));
    const body = rows.map((row) => el("tr", {}, row.map((v, i) => {
      const cls = [];
      if (isNumberType(data.columns[i].type)) cls.push("num");
      if (v === "***") cls.push("masked");
      if (v === null) cls.push("null");
      return el("td", { text: fmt[i](v), className: cls.join(" ") });
    })));
    table.replaceChildren(el("thead", {}, [head]), el("tbody", {}, body));

    const total = data.rows.length;
    show($("filter-wrap"), total > 5);
    $("row-count").textContent = view.filter.trim()
      ? "Showing " + rows.length + " of " + total + " rows" : "";
    show($("table-wrap"), true);
    show($("table-tools"), total > 0);
  }

  $("filter").addEventListener("input", (e) => {
    view.filter = e.target.value;
    renderTable();
  });

  // ------------------------------------------------------------------ charts
  // What a result can be charted as: one label column (a date, or text) against number columns.
  // ID columns (id, customer_id, …) are never plotted. The default:
  //   date label + 1–3 numbers           -> line chart
  //   text label + 1–3 numbers, ≤ 30 rows -> bar chart
  //   anything else                       -> table only (the chart buttons still let you choose)
  function analyse(data) {
    if (!data || data.rows.length < 2) return null;
    const cols = data.columns.map((c, i) => ({ ...c, i }));
    const isId = (name) => /(^id$|_id$)/i.test(name);
    const numbers = cols.filter((c) => c.type === "number" && !isId(c.name));
    const label = cols.find((c) => c.type === "date" || c.type === "datetime")
      || cols.find((c) => c.type === "text");
    if (!label || numbers.length === 0) return null;
    if (data.rows.some((r) => r[label.i] === "***")) return null;
    const isTime = label.type === "date" || label.type === "datetime";
    let defaultMode = "table";
    if (numbers.length <= 3) defaultMode = isTime ? "line" : data.rows.length <= 30 ? "bar" : "table";
    return { label, numbers, isTime, defaultMode };
  }

  // Plot every number column together only when they are of similar size; revenue (~100,000) next to
  // units (~100) on one axis would make the small one invisible, so start with the first column then.
  function defaultPlot(shape, data) {
    const cols = shape.numbers.slice(0, 3);
    if (cols.length < 2) return "all";
    const peaks = cols.map((c) => Math.max(...data.rows.map((r) => Math.abs(typeof r[c.i] === "number" ? r[c.i] : 0))));
    const lo = Math.min(...peaks), hi = Math.max(...peaks);
    if (!(lo > 0 && hi / lo > 20)) return "all";
    // Prefer the column the rows are already ordered by (usually what the question ranks by),
    // so the bars come out in order; otherwise the first number column.
    const ordered = (c) => {
      const v = data.rows.map((r) => r[c.i]).filter((x) => typeof x === "number");
      const up = v.every((x, k) => k === 0 || x >= v[k - 1]);
      const down = v.every((x, k) => k === 0 || x <= v[k - 1]);
      return v.length > 1 && (up || down);
    };
    return String((cols.find(ordered) || cols[0]).i);
  }

  function renderControls() {
    const shape = view.shape;
    show($("chart-controls"), Boolean(shape));
    if (!shape) return;
    for (const b of document.querySelectorAll(".segmented button")) {
      b.setAttribute("aria-pressed", String(b.dataset.view === view.mode));
    }
    const pick = $("plot-column");
    const opts = [];
    if (shape.numbers.length > 1) {
      const all = el("option", { text: shape.numbers.length > 3 ? "First 3 number columns" : "All number columns" });
      all.value = "all";
      opts.push(all);
    }
    for (const c of shape.numbers) {
      const o = el("option", { text: c.name });
      o.value = String(c.i);
      opts.push(o);
    }
    pick.replaceChildren(...opts);
    pick.value = shape.numbers.length > 1 ? view.plot : String(shape.numbers[0].i);
    show($("plot-pick"), shape.numbers.length > 1 && view.mode !== "table");
  }

  for (const b of document.querySelectorAll(".segmented button")) {
    b.addEventListener("click", () => { view.mode = b.dataset.view; renderControls(); renderChart(); });
  }
  $("plot-column").addEventListener("change", (e) => { view.plot = e.target.value; renderChart(); });

  function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

  function renderChart() {
    if (chart) { chart.destroy(); chart = null; }
    const wrap = $("chart-wrap");
    const shape = view.shape;
    if (!shape || view.mode === "table" || typeof window.Chart !== "function") { show(wrap, false); return; }

    const data = view.data;
    const kind = view.mode;
    const numbers = view.plot === "all" ? shape.numbers.slice(0, 3)
      : shape.numbers.filter((c) => String(c.i) === view.plot);
    const palette = [cssVar("--teal"), cssVar("--brass"), cssVar("--plum")];
    const ink = cssVar("--ink"), muted = cssVar("--muted"), rule = cssVar("--rule");
    let rows = data.rows;
    if (kind === "line" && shape.isTime) {
      rows = [...rows].sort((a, b) => String(a[shape.label.i]).localeCompare(String(b[shape.label.i])));
    }
    const horizontal = kind === "bar" && rows.length > 6;
    const fmt = columnFormatters(data.columns, rows);
    const labels = rows.map((r) => fmt[shape.label.i](r[shape.label.i]));
    const datasets = numbers.map((c, k) => ({
      label: c.name,
      data: rows.map((r) => (typeof r[c.i] === "number" ? r[c.i] : null)),
      backgroundColor: palette[k],
      borderColor: palette[k],
      borderWidth: kind === "line" ? 2 : 0,
      pointRadius: kind === "line" ? 3 : 0,
      tension: 0.2,
      maxBarThickness: 36,
    }));

    const valueAxis = { grid: { color: rule }, ticks: { color: muted }, border: { display: false }, beginAtZero: true };
    const labelAxis = { grid: { display: false }, ticks: { color: ink, autoSkip: true, maxRotation: 0 }, border: { color: rule } };
    wrap.classList.toggle("is-tall", horizontal && rows.length > 12);
    show(wrap, true);
    chart = new window.Chart($("chart"), {
      type: kind,
      data: { labels, datasets },
      options: {
        maintainAspectRatio: false,
        indexAxis: horizontal ? "y" : "x",
        animation: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? false : { duration: 300 },
        plugins: {
          legend: { display: datasets.length > 1, labels: { color: ink, boxWidth: 12 } },
          tooltip: { callbacks: { label: (ctx) => ctx.dataset.label + ": " + formatNumber(ctx.parsed[horizontal ? "x" : "y"]) } },
        },
        scales: horizontal ? { x: valueAxis, y: labelAxis } : { x: labelAxis, y: valueAxis },
      },
    });
    const caption = (kind === "line" ? "Line chart of " : "Bar chart of ") +
      numbers.map((c) => c.name).join(", ") + " by " + shape.label.name + ". The same data is in the table below.";
    $("chart-caption").textContent = caption;
    $("chart").setAttribute("aria-label", caption);
  }

  // Redraw with the right colours when the system switches between light and dark.
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
    if (view.data && view.data.status === "ok") renderChart();
  });

  // ------------------------------------------------------------------ CSV download (the rows as shown: filtered and sorted)
  $("download").addEventListener("click", () => {
    if (!view.data || view.data.status !== "ok") return;
    const esc = (v) => {
      let s = v === null || v === undefined ? "" : String(v);
      if (typeof v === "string" && /^[=+\-@\t\r]/.test(s)) s = "'" + s;   // stop spreadsheet formula injection
      return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    };
    const { rows } = visibleRows();
    const lines = [view.data.columns.map((c) => esc(c.name)).join(",")]
      .concat(rows.map((r) => r.map(esc).join(",")));
    const blob = new Blob([lines.join("\r\n")], { type: "text/csv;charset=utf-8" });
    const a = el("a");
    a.href = URL.createObjectURL(blob);
    a.download = "querywarden-result.csv";
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  });

  // ------------------------------------------------------------------ feedback and verified answers
  let canReview = false;
  let feedbackOn = false;
  let lastFeedbackId = "";   // feedback just given on the answer on screen

  function renderVerifiedBadge(data) {
    const p = $("answer-verified");
    const v = data.verified;
    if (!v || data.status !== "ok") { show(p, false); return; }
    const label = v.kind === "same" ? "Verified answer"
      : v.kind === "reused" ? "Matches a verified answer" : "Based on similar verified answers";
    p.textContent = label;
    p.title = "Verified question" + (v.questions.length > 1 ? "s" : "") + ": " + v.questions.join(" | ");
    p.classList.toggle("is-guided", v.kind === "guided");
    show(p, true);
  }

  function renderFeedback(data) {
    show($("feedback"), feedbackOn && Boolean(view.question));
    for (const id of ["fb-up", "fb-down"]) { $(id).disabled = false; $(id).setAttribute("aria-pressed", "false"); }
    show($("fb-comment-wrap"), false);
    $("fb-comment").value = "";
    show($("fb-done"), false);
    const v = data.verified;
    lastFeedbackId = "";
    show($("fb-verify"), canReview && data.status === "ok" && Boolean(data.sql) && !(v && v.kind === "same"));
    $("fb-verify").disabled = false;
  }

  async function sendFeedback(rating, comment) {
    const d = view.data;
    try {
      const res = await api("/api/feedback", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ question: view.question, interpreted_question: d.interpreted_question || "",
          sql: d.sql || "", rating, comment: comment || "" }),
      });
      const j = await res.json();
      if (res.ok && j.id) lastFeedbackId = j.id;
      $("fb-done").textContent = res.ok
        ? (rating === "up" ? "Thanks, noted." : "Thanks. A reviewer will look at it.") : (j.message || "Couldn't send feedback.");
    } catch {
      $("fb-done").textContent = "Couldn't send feedback.";
    }
    show($("fb-done"), true);
    for (const id of ["fb-up", "fb-down"]) $(id).disabled = true;
  }

  $("fb-up").addEventListener("click", () => { $("fb-up").setAttribute("aria-pressed", "true"); sendFeedback("up"); });
  $("fb-down").addEventListener("click", () => {
    $("fb-down").setAttribute("aria-pressed", "true");
    show($("fb-comment-wrap"), true);
    $("fb-comment").focus();
  });
  $("fb-send").addEventListener("click", () => { show($("fb-comment-wrap"), false); sendFeedback("down", $("fb-comment").value); });

  async function postJson(url, body) {
    const res = await api(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body),
    });
    let j = {};
    try { j = await res.json(); } catch { /* ignore */ }
    return { ok: res.ok && j.status === "ok", data: j };
  }

  $("fb-verify").addEventListener("click", async () => {
    const d = view.data;
    $("fb-verify").disabled = true;
    const r = await postJson("/api/review/verify", { question: d.interpreted_question || view.question, sql: d.sql, notes: "",
      feedback_id: lastFeedbackId });
    $("fb-done").textContent = r.ok ? "Saved as a verified answer. The same question will now get this answer straight away."
      : (r.data.message || "Couldn't save it.");
    show($("fb-done"), true);
  });

  // The review panel (reviewers only)
  const reviewDialog = $("review");
  $("review-open").addEventListener("click", () => { reviewDialog.showModal(); loadReview(); });
  $("review-close").addEventListener("click", () => reviewDialog.close());

  async function loadReview() {
    try {
      const res = await api("/api/review", { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error();
      renderReview(await res.json());
    } catch {
      $("review-feedback").replaceChildren(el("p", { className: "quiet", text: "Couldn't load the review list." }));
    }
  }

  function renderReview(r) {
    $("review-file").textContent = r.verified_file ? "(" + r.verified_file + ")" : "";
    $("review-open").textContent = r.feedback.length ? "Review (" + r.feedback.length + ")" : "Review";
    $("review-feedback").replaceChildren(...(r.feedback.length ? r.feedback.map(reviewItem)
      : [el("p", { className: "quiet", text: "Nothing to review. Feedback from “Correct” and “Wrong” appears here." })]));
    $("review-verified").replaceChildren(...(r.verified.length ? r.verified.map(verifiedItem)
      : [el("p", { className: "quiet", text: "None yet." })]));
  }

  function reviewItem(f) {
    const box = el("div", { className: "review-item" });
    const question = el("input", { className: "review-question" });
    question.value = f.interpreted_question || f.question;
    question.setAttribute("aria-label", "Question to save");
    const sql = el("textarea", {});
    sql.value = f.sql || "";
    sql.setAttribute("aria-label", "SQL");
    const result = el("p", { className: "review-result" });
    const tryBtn = el("button", { text: "Try", type: "button", className: "secondary" });
    const verifyBtn = el("button", { text: "Verify", type: "button", className: "secondary" });
    const dismissBtn = el("button", { text: "Dismiss", type: "button", className: "secondary" });
    tryBtn.addEventListener("click", async () => {
      const r = await postJson("/api/review/try", { sql: sql.value });
      result.classList.toggle("is-error", !r.ok);
      result.textContent = r.ok
        ? r.data.row_count + " row(s). First rows:\n" + r.data.columns.join(" | ") + "\n"
          + r.data.rows.slice(0, 5).map((row) => row.map((v) => (v === null ? "—" : String(v))).join(" | ")).join("\n")
        : (r.data.message || "Rejected.");
    });
    verifyBtn.addEventListener("click", async () => {
      const r = await postJson("/api/review/verify", { question: question.value, sql: sql.value, notes: f.comment || "", feedback_id: f.id });
      result.classList.toggle("is-error", !r.ok);
      result.textContent = r.ok ? "Saved as a verified answer." : (r.data.message || "Couldn't save it.");
      if (r.ok) setTimeout(loadReview, 700);
    });
    dismissBtn.addEventListener("click", async () => {
      const r = await postJson("/api/review/dismiss", { feedback_id: f.id });
      if (r.ok) loadReview();
    });
    box.append(
      el("p", { className: "review-q", text: f.question }),
      el("p", { className: "review-meta" }, [
        el("span", { className: f.rating === "down" ? "is-down" : "is-up", text: f.rating === "down" ? "Marked wrong" : "Marked correct" }),
        document.createTextNode(" by " + f.user + ", " + new Date(f.at).toLocaleString()),
      ]),
      ...(f.comment ? [el("p", { className: "review-comment", text: "“" + f.comment + "”" })] : []),
      question, sql,
      el("div", { className: "review-actions" }, [tryBtn, verifyBtn, dismissBtn]),
      result,
    );
    return box;
  }

  function verifiedItem(v) {
    const removeBtn = el("button", { text: "Remove", type: "button", className: "secondary" });
    removeBtn.addEventListener("click", async () => {
      const r = await postJson("/api/review/remove", { id: v.id });
      if (r.ok) loadReview();
    });
    return el("div", { className: "review-item" }, [
      el("p", { className: "review-q", text: v.question }),
      el("p", { className: "review-meta", text: "Verified by " + (v.verified_by || "someone") + ", " + new Date(v.verified_at).toLocaleString() }),
      el("pre", { text: v.sql }),
      el("div", { className: "review-actions" }, [removeBtn]),
    ]);
  }

  // ------------------------------------------------------------------ read-only settings
  const settingsDialog = $("settings");
  $("settings-open").addEventListener("click", async () => {
    settingsDialog.showModal();
    try {
      const res = await api("/api/settings", { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error();
      renderSettings(await res.json());
    } catch {
      $("settings-body").replaceChildren(el("p", { className: "quiet", text: "Couldn't load the settings." }));
    }
  });
  $("settings-close").addEventListener("click", () => settingsDialog.close());
  settingsDialog.addEventListener("click", (e) => { if (e.target === settingsDialog) settingsDialog.close(); });

  function renderSettings(s) {
    const p = s.policy, m = s.models, w = s.web, lim = p.limits;
    const rows = (pairs) => el("dl", {}, pairs.flatMap(([k, v, cls]) =>
      [el("dt", { text: k }), el("dd", { text: v, className: cls || "" })]));
    const list = (xs) => (xs.length ? xs.join(", ") : "none");
    const exampleSource = { policy: "policy file", ai: "suggested by Claude at startup", none: "none" }[p.examples_source] || p.examples_source;
    const blocked = p.blocked_columns.length
      ? p.blocked_columns.map((b) => b.table + ": " + b.count + (b.count === 1 ? " column" : " columns")).join("; ") +
        " (names are kept out of this page)"
      : "none";

    $("settings-body").replaceChildren(
      el("h3", { text: "Rules" }),
      rows([
        ["Policy file", p.source],
        ["Schema", p.schema],
        ["Tables Claude can use", list(p.allowed_tables)],
        ["Blocked columns", blocked],
        ["Hidden in results", list(p.masked_columns)],
        ["Blocked functions", p.blocked_functions + " dangerous PostgreSQL functions"],
        ["Limits", "up to " + lim.max_rows + " rows, " + lim.timeout_ms / 1000 + " s per query, " +
          lim.max_joins + " joins, " + lim.max_selects + " SELECT blocks"],
        ["Question length", "up to " + lim.max_question_chars + " characters"],
      ]),
      el("h3", { text: "AI" }),
      rows([
        ["Writes the SQL", m.sql_model + " (" + m.sql_effort + " effort)"],
        ["Examples, summaries, follow-ups", m.helper_enabled ? m.helper_model + " (" + m.helper_effort + " effort)" : "off"],
        ["Result rows shared with AI", p.share_results_with_ai ? "Yes: up to 50 rows per answer, after masking" : "No: results stay on this computer",
          p.share_results_with_ai ? "is-on" : ""],
        ["Category values shared with AI", p.share_category_values ? "Yes: short category lists such as country codes"
          : "No", p.share_category_values ? "is-on" : ""],
        ["Example questions from", exampleSource],
      ]),
      el("h3", { text: "Live updates" }),
      rows([
        ["Picks up changes", s.live && s.live.enabled
          ? "Yes: policy.yaml and the database are checked every 30 seconds" : "No: restart to apply changes"],
        ...(s.live && s.live.last_change ? [["Last change", new Date(s.live.last_change).toLocaleString() + " (" + s.live.last_change_reason + ")"]] : []),
        ...(s.live && s.live.last_error ? [["Problem", s.live.last_error, "is-on"]] : []),
        ["Refusal fallback", m.refusal_fallback_sql ? "on for SQL writing" : "off"],
      ]),
      el("h3", { text: "Business definitions" }),
      Object.keys(p.definitions || {}).length
        ? rows(Object.entries(p.definitions).map(([term, meaning]) => [term, meaning]))
        : el("p", { className: "quiet", text: "None. Add a definitions section to policy.yaml so terms like revenue always mean the same thing." }),
      el("h3", { text: "Web page" }),
      rows([
        ["Address", w.address + (w.local_only ? " (this computer only)"
          : " (shared: sign-in required, " + w.people + (w.people === 1 ? " person)" : " people)"))],
        ...(w.signed_in_as ? [["Signed in as", w.signed_in_as]] : []),
        ["Usage limits", w.questions_per_minute + " questions per minute, " + w.questions_at_once + " at once"],
      ]),
    );
    $("settings-how").textContent = s.how_to_change;
  }

  // ------------------------------------------------------------------ start
  renderRecent();
  async function startPage() {
    if (await whoAmI()) { loadSchema(); input.focus(); }
  }
  signedInHook = startPage;
  startPage();
})();
