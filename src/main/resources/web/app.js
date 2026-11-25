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
      canEditSettings = Boolean(me.can_edit_settings);
      feedbackOn = Boolean(me.feedback);
      show($("review-open"), canReview && feedbackOn);
      savedOn = Boolean(me.saved);
      show($("dashboard-open"), savedOn);
      if (savedOn) refreshAlertCount();
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
    b.addEventListener("click", () => ask(q, { fresh: true }));
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

  // The empty question box shows one of this database's own example questions.
  let defaultPlaceholder = "Ask a question about your data";

  function renderExamples(examples) {
    $("examples").replaceChildren(...examples.map((q) => el("li", {}, [questionButton(q)])));
    show($("examples-wrap"), examples.length > 0 && conversation.length === 0);
    defaultPlaceholder = examples.length ? examples[0] : "Ask a question about your data";
    if (conversation.length === 0 && !pendingClarify) input.placeholder = defaultPlaceholder;
  }

  // ------------------------------------------------------------------ conversation memory and earlier answers
  // Answered questions of this conversation, sent with the next question so follow-ups like
  // "now only for X" work. Kept in this page only; "New conversation" or a reload clears it.
  const conversation = [];
  const EARLIER_MAX = 10;

  function updateConversationUi() {
    show($("new-conversation"), conversation.length > 0);
    // examples are for starting out; once a conversation is going, follow-ups take their place
    show($("examples-wrap"), conversation.length === 0 && $("examples").children.length > 0);
    input.placeholder = conversation.length > 0
      ? "Ask a follow-up, e.g. “now only last year”, or a new question"
      : defaultPlaceholder;
  }

  $("new-conversation").addEventListener("click", () => {
    conversation.length = 0;
    pendingClarify = null;
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
    for (const sel of [".chart-controls", ".table-tools", "#next", ".answer-actions", "#clarify"]) {
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

  // Set while a clarifying question is on screen: the next question answers it.
  let pendingClarify = null;

  async function ask(raw, opts = {}) {
    let question = (raw || "").trim();
    if (!question || busy) return;
    let clarified = Boolean(opts.clarified);
    if (pendingClarify && !clarified && !opts.fresh) {
      // typed answer to the clarifying question: combine it with the original question
      question = pendingClarify + " \u2014 " + question;
      clarified = true;
    }
    pendingClarify = null;
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
        body: JSON.stringify({ question, history: conversation.slice(-3), clarified }),
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
    } else if (data.status === "clarify") {
      $("answer-summary").textContent = "A quick question first";
      msg.textContent = data.message || "";
      msg.classList.remove("is-declined");
      show(msg, true);
    } else {
      $("answer-summary").textContent = data.status === "unanswerable" ? "Not answered"
        : data.status === "blocked" ? "Question blocked" : "Couldn't answer";
      msg.textContent = data.message || "";
      msg.classList.toggle("is-declined", data.status !== "unanswerable");
      show(msg, true);
    }
    renderClarify(question, data);

    show($("answer-insight"), false);
    show($("next"), false);
    view.shape = ok ? analyse(data) : null;
    view.mode = view.shape ? view.shape.defaultMode : "table";
    view.plot = view.shape ? defaultPlot(view.shape, data) : "all";
    renderControls();
    renderTable();
    renderChart();
    renderKpis(ok ? data : null);
    // "LIMIT set to N" only matters when rows were actually cut off ("Result truncated to …" says so)
    renderWarnings(ok ? (data.warnings || []).filter((w) => !/^(LIMIT set to|Query wrapped with LIMIT)/.test(w)) : []);
    renderHow(data);
    renderVerifiedBadge(data);
    renderFeedback(data);
    renderSave(data);

    answer.classList.remove("is-fresh");
    void answer.offsetWidth; // restart the one entrance animation
    answer.classList.add("is-fresh");
  }

  function renderClarify(question, data) {
    const options = data.status === "clarify" && Array.isArray(data.clarify_options) ? data.clarify_options : [];
    $("clarify-options").replaceChildren(...options.map((o) => {
      const b = el("button", { text: o, type: "button", className: "secondary" });
      b.addEventListener("click", () => ask(question + " \u2014 " + o, { clarified: true }));
      return el("li", {}, [b]);
    }));
    show($("clarify"), options.length > 0);
    pendingClarify = options.length > 0 ? question : null;
    if (pendingClarify) {
      const first = (data.clarify_options || [])[0];
      input.placeholder = first ? "Or type what you meant, e.g. \u201c" + first.toLowerCase() + "\u201d" : "Or type what you meant";
    }
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
    show(details, hasSql || rejected.length > 0 || Boolean(data.explanation));
    $("how-details").querySelector("summary").textContent = hasSql ? "How it was answered, with the SQL" : "How it was answered";
    details.open = sqlOpenPreference();   // re-saving the same value via "toggle" is harmless
  }

  // Line breaks before the main clauses so the SQL is readable. Text only; nothing is executed.
  function prettySql(sql) {
    return sql.replace(/\s+(FROM|WHERE|GROUP BY|HAVING|ORDER BY|LIMIT|JOIN|LEFT JOIN|RIGHT JOIN|INNER JOIN|UNION|WITH)\s/g, "\n$1 ");
  }

  // One row of a few numbers ("total revenue", "orders this month"): show them big instead of a one-line table.
  function renderKpis(data) {
    const box = $("answer-kpis");
    const fits = data && data.rows.length === 1 && data.columns.length <= 4
      && data.columns.some((c) => isNumberType(c.type));
    show(box, Boolean(fits));
    if (!fits) { box.replaceChildren(); return; }
    const fmt = columnFormatters(data.columns, data.rows);
    box.replaceChildren(...data.columns.map((c, i) =>
      el("div", { className: "kpi" }, [el("strong", { text: fmt[i](data.rows[0][i]) }), el("span", { text: c.name })])));
    show($("table-wrap"), false);
    show($("filter-wrap"), false);
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
    const palette = [cssVar("--chart-1"), cssVar("--chart-2"), cssVar("--chart-3")];
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
    show($("feedback"), feedbackOn && Boolean(view.question) && data.status !== "clarify");
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

  // ------------------------------------------------------------------ saved questions and the dashboard
  let savedOn = false;
  let dashCharts = [];
  const SCHEDULE_LABELS = {
    "every 15m": "every 15 minutes", "every 1h": "every hour", "daily 08:00": "every day at 08:00",
    "weekdays 08:00": "weekdays at 08:00", "weekly mon 08:00": "Mondays at 08:00",
  };
  const scheduleLabel = (s) => SCHEDULE_LABELS[s] || s;

  function renderSave(data) {
    show($("save"), savedOn && data.status === "ok" && Boolean(data.sql) && Boolean(view.question));
    show($("save-open"), true);
    show($("save-form"), false);
    show($("save-done"), false);
  }

  function saveMessage(text) {
    $("save-done").textContent = text;
    show($("save-done"), true);
  }

  $("save-open").addEventListener("click", () => {
    const d = view.data;
    $("save-name").value = (d.interpreted_question || view.question).slice(0, 80);
    const nums = d.columns.filter((c) => c.type === "number");
    $("save-alert-col").replaceChildren(...nums.map((c) => {
      const o = el("option", { text: c.name });
      o.value = c.name;
      return o;
    }));
    show($("save-alert-wrap"), nums.length > 0);
    $("save-alert").checked = false;
    $("save-alert-value").value = "";
    show($("save-open"), false);
    show($("save-done"), false);
    show($("save-form"), true);
    $("save-name").focus();
  });
  $("save-cancel").addEventListener("click", () => { show($("save-form"), false); show($("save-open"), true); });
  $("save-alert-op").addEventListener("change", () => show($("save-alert-value"), $("save-alert-op").value !== "changes"));

  $("save-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const d = view.data;
    const schedule = $("save-schedule").value;
    let alert = null;
    if ($("save-alert").checked) {
      const op = $("save-alert-op").value;
      const raw = $("save-alert-value").value;
      if (!schedule) { saveMessage("Alerts are checked on scheduled runs: choose when it should run."); return; }
      if (op !== "changes" && raw === "") { saveMessage("Enter the number to compare with."); return; }
      alert = { column: $("save-alert-col").value, op, value: op === "changes" ? null : Number(raw) };
    }
    const r = await postJson("/api/saved", { name: $("save-name").value, question: d.interpreted_question || view.question,
      sql: d.sql, schedule, alert });
    if (r.ok) {
      show($("save-form"), false);
      saveMessage("Saved to the dashboard" + (schedule ? "; it runs " + scheduleLabel(r.data.schedule) + "." : "."));
      refreshAlertCount();
    } else {
      saveMessage(r.data.message || "Couldn't save it.");
    }
  });

  async function fetchSaved() {
    const res = await api("/api/saved", { headers: { Accept: "application/json" } });
    const j = await res.json();
    if (!res.ok) throw new Error(j.message || "failed");
    return j;
  }

  async function refreshAlertCount(list) {
    try {
      const saved = list || (await fetchSaved()).saved;
      const n = saved.filter((s) => s.alerting).length;
      renderRailSaved(saved);
      $("alert-count").textContent = String(n);
      $("alert-count").title = n + (n === 1 ? " saved question is" : " saved questions are") + " alerting";
      show($("alert-count"), n > 0);
    } catch { /* ignore */ }
  }
  setInterval(() => { if (savedOn) refreshAlertCount(); }, 120000);

  // Saved questions in the side rail: click one to ask it again.
  function renderRailSaved(saved) {
    $("rail-saved").replaceChildren(...saved.slice(0, 12).map((s) => {
      const b = el("button", { text: s.name, type: "button", className: s.alerting ? "is-alerting" : "" });
      b.title = s.alerting && s.last_message ? "Alert: " + s.last_message : s.question;
      b.addEventListener("click", () => ask(s.question, { fresh: true }));
      return el("li", {}, [b]);
    }));
    show($("rail-saved-block"), saved.length > 0);
  }

  const dashboard = $("dashboard");
  $("dashboard-open").addEventListener("click", () => { dashboard.showModal(); loadDashboard(); });
  $("dashboard-close").addEventListener("click", () => dashboard.close());
  $("dashboard-refresh").addEventListener("click", () => loadDashboard());
  dashboard.addEventListener("close", () => { dashCharts.forEach((c) => c.destroy()); dashCharts = []; });

  async function loadDashboard() {
    dashCharts.forEach((c) => c.destroy());
    dashCharts = [];
    const grid = $("dash-grid");
    grid.replaceChildren(el("p", { className: "quiet", text: "Loading…" }));
    let j;
    try {
      j = await fetchSaved();
    } catch {
      grid.replaceChildren(el("p", { className: "quiet", text: "Couldn't load the saved questions." }));
      return;
    }
    refreshAlertCount(j.saved);
    const alerts = j.alerts || [];
    $("dash-alert-list").replaceChildren(...alerts.slice(0, 8).map((a) => {
      const t = el("time", { text: new Date(a.at).toLocaleString() });
      t.dateTime = a.at;
      return el("li", {}, [t, document.createTextNode(a.message)]);
    }));
    show($("dash-alerts"), alerts.length > 0);
    if (!j.saved.length) {
      grid.replaceChildren(el("p", { className: "quiet",
        text: "Nothing saved yet. Ask a question, then choose \u201cSave to dashboard\u201d under the answer." }));
      return;
    }
    const cards = j.saved.map(dashCard);
    grid.replaceChildren(...cards.map((c) => c.node));
    for (const c of cards) await runCard(c);   // one at a time: gentle on the database
  }

  function dashCard(s) {
    const body = el("div", { className: "dash-body" }, [el("p", { className: "quiet", text: "Running…" })]);
    const meta = [];
    meta.push(s.schedule ? "Runs " + scheduleLabel(s.schedule) : "Runs when you open the dashboard");
    if (s.alert) meta.push("alert when " + s.alert);
    const children = [el("h3", { text: s.name }), el("p", { className: "dash-meta", text: meta.join(", ") })];
    if (s.alerting && s.last_message) children.push(el("p", { className: "dash-alert-msg", text: "Alert: " + s.last_message }));
    if (s.last_status === "failed" && s.last_message)
      children.push(el("p", { className: "dash-alert-msg", text: "Last scheduled run failed: " + s.last_message }));
    children.push(body);
    const again = el("button", { text: "Ask again", type: "button", className: "secondary" });
    again.title = s.question;
    again.addEventListener("click", () => { dashboard.close(); ask(s.question, { fresh: true }); });
    const actions = [again];
    if (s.can_remove) {
      const rm = el("button", { text: "Remove", type: "button", className: "secondary" });
      rm.addEventListener("click", async () => {
        if (!confirm("Remove \u201c" + s.name + "\u201d from the dashboard?")) return;
        const r = await postJson("/api/saved/remove", { id: s.id });
        if (r.ok) loadDashboard(); else alert(r.data.message || "Couldn't remove it.");
      });
      actions.push(rm);
    }
    children.push(el("div", { className: "dash-actions" }, actions));
    const node = el("article", { className: "dash-card" + (s.alerting ? " is-alerting" : "") }, children);
    return { s, node, body };
  }

  async function runCard(c) {
    let data;
    try {
      const res = await api("/api/saved/run", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ id: c.s.id }),
      });
      data = await res.json();
    } catch {
      data = { status: "failed", message: "Couldn't run it." };
    }
    if (data.status !== "ok") {
      c.body.replaceChildren(el("p", { className: "dash-alert-msg", text: data.message || "Couldn't run it." }));
      return;
    }
    c.body.replaceChildren(dashResult(data));
  }

  function dashResult(data) {
    const fmt = columnFormatters(data.columns, data.rows);
    if (data.rows.length === 0) return el("p", { className: "quiet", text: "No rows." });
    // one row of numbers: show them big
    if (data.rows.length === 1 && data.columns.length <= 4) {
      return el("div", { className: "dash-big" }, data.columns.map((c, i) =>
        el("div", {}, [el("strong", { text: fmt[i](data.rows[0][i]) }), el("span", { text: c.name })])));
    }
    const shape = analyse(data);
    if (shape && typeof window.Chart === "function" && (shape.isTime || data.rows.length <= 15)) {
      const canvas = el("canvas");
      const figure = el("figure", { className: "dash-figure" }, [canvas]);
      const num = shape.numbers[0];
      let rows = data.rows;
      if (shape.isTime) rows = [...rows].sort((a, b) => String(a[shape.label.i]).localeCompare(String(b[shape.label.i])));
      const colour = cssVar("--chart-1"), muted = cssVar("--muted"), rule = cssVar("--rule"), ink = cssVar("--ink");
      canvas.setAttribute("role", "img");
      canvas.setAttribute("aria-label", (shape.isTime ? "Line" : "Bar") + " chart of " + num.name + " by " + shape.label.name);
      requestAnimationFrame(() => {
        dashCharts.push(new window.Chart(canvas, {
          type: shape.isTime ? "line" : "bar",
          data: {
            labels: rows.map((r) => fmt[shape.label.i](r[shape.label.i])),
            datasets: [{ label: num.name, data: rows.map((r) => (typeof r[num.i] === "number" ? r[num.i] : null)),
              backgroundColor: colour, borderColor: colour, borderWidth: shape.isTime ? 2 : 0, pointRadius: 0,
              tension: 0.2, maxBarThickness: 28 }],
          },
          options: {
            maintainAspectRatio: false,
            animation: false,
            plugins: { legend: { display: false },
              tooltip: { callbacks: { label: (ctx) => num.name + ": " + formatNumber(ctx.parsed.y) } } },
            scales: {
              x: { grid: { display: false }, ticks: { color: ink, autoSkip: true, maxRotation: 0 }, border: { color: rule } },
              y: { grid: { color: rule }, ticks: { color: muted }, border: { display: false }, beginAtZero: true },
            },
          },
        }));
      });
      return figure;
    }
    const shown = data.rows.slice(0, 6);
    const head = el("tr", {}, data.columns.map((c) => el("th", { text: c.name })));
    const body = shown.map((r) => el("tr", {}, r.map((v, i) =>
      el("td", { text: fmt[i](v), className: data.columns[i].type === "number" ? "num" : "" }))));
    const table = el("table", { className: "dash-table" }, [el("thead", {}, [head]), el("tbody", {}, body)]);
    if (data.rows.length <= shown.length) return table;
    return el("div", {}, [table, el("p", { className: "quiet", text: (data.rows.length - shown.length) + " more rows" })]);
  }

  // ------------------------------------------------------------------ colour theme (auto follows the system)
  const THEME_KEY = "querywarden.theme";
  const THEMES = ["auto", "light", "dark"];
  function applyTheme(t) {
    if (t === "light" || t === "dark") document.documentElement.dataset.theme = t;
    else delete document.documentElement.dataset.theme;
    $("theme-toggle").textContent = "Theme: " + t;
    if (view.data && view.data.status === "ok") renderChart();
  }
  let theme = "auto";
  try { theme = THEMES.includes(localStorage.getItem(THEME_KEY)) ? localStorage.getItem(THEME_KEY) : "auto"; } catch { /* ignore */ }
  applyTheme(theme);
  $("theme-toggle").addEventListener("click", () => {
    theme = THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length];
    try { localStorage.setItem(THEME_KEY, theme); } catch { /* ignore */ }
    applyTheme(theme);
  });

  // ------------------------------------------------------------------ read-only settings
  const settingsDialog = $("settings");
  $("settings-open").addEventListener("click", () => { settingsDialog.showModal(); showSettings(); });

  async function showSettings(message) {
    show($("settings-editor"), false);
    show($("settings-body"), true);
    show($("settings-edit"), canEditSettings);
    show($("settings-msg"), Boolean(message));
    $("settings-msg").textContent = message || "";
    $("settings-intro").textContent = canEditSettings
      ? "These come from policy.yaml and the server's environment. Choose Edit to change them."
      : "Read-only. These come from policy.yaml and the server's environment.";
    try {
      const res = await api("/api/settings", { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error();
      const info = await res.json();
      renderSettings(info);
      show($("settings-undo"), Boolean(info.undo_available));
    } catch {
      $("settings-body").replaceChildren(el("p", { className: "quiet", text: "Couldn't load the settings." }));
    }
  }
  $("settings-close").addEventListener("click", () => settingsDialog.close());

  // ------------------------------------------------------------------ editing settings
  let canEditSettings = false;
  let editState = null;   // what /api/settings/edit returned

  $("settings-edit").addEventListener("click", openEditor);
  $("settings-undo").addEventListener("click", async () => {
    if (!confirm("Go back to the version of the policy before the last change?")) return;
    const r = await postJson("/api/settings/undo", {});
    showSettings(r.ok ? "Undone: " + (r.data.changes.map((c) => c.text).join("; ") || "no differences") + "."
      : (r.data.message || "Couldn't undo."));
  });

  async function openEditor() {
    let e;
    try {
      const res = await api("/api/settings/edit", { headers: { Accept: "application/json" } });
      e = await res.json();
      if (!res.ok) throw new Error(e.message);
    } catch (err) {
      showSettings(err.message || "Couldn't load the settings for editing.");
      return;
    }
    editState = e;
    show($("settings-body"), false);
    show($("settings-edit"), false);
    show($("settings-msg"), false);
    $("settings-intro").textContent = "Changes are checked like a hand-edited " + e.file + ", saved with a backup, and apply within seconds.";
    const box = $("settings-editor");
    box.replaceChildren(...buildEditor(e));
    show(box, true);
  }

  // small form helpers (all values are set as properties, never as HTML)
  function field(labelText, input, hint) {
    const id = "f" + Math.random().toString(36).slice(2, 9);
    input.id = id;
    const label = el("label", { text: labelText });
    label.htmlFor = id;
    return el("div", { className: "edit-field" }, hint ? [label, input, el("p", { className: "quiet", text: hint })] : [label, input]);
  }
  function textInput(value, cls) { const i = el("input", { type: "text", className: cls || "" }); i.value = value || ""; return i; }
  function numberInput(value) { const i = el("input", { type: "number" }); i.value = value; i.min = "0"; i.step = "1"; return i; }
  function checkbox(labelText, checked, cls) {
    const c = el("input", { type: "checkbox" });
    c.checked = Boolean(checked);
    const l = el("label", { className: "check " + (cls || "") }, [c, document.createTextNode(" " + labelText)]);
    return { node: l, input: c };
  }
  function section(title, hint, children) {
    return el("section", { className: "edit-section" },
      [el("h3", { text: title }), ...(hint ? [el("p", { className: "quiet", text: hint })] : []), ...children]);
  }
  const csv = (s) => s.split(",").map((x) => x.trim()).filter(Boolean);

  // Builds the form; each section registers how to read its value back.
  let readers = {};
  function buildEditor(e) {
    readers = {};
    const v = e.values, out = [];
    const can = (k) => e.editable.includes(k);

    if (can("definitions")) {
      const rows = el("div", { className: "edit-rows" });
      const addRow = (term, meaning) => {
        const t = textInput(term), m = el("textarea", { className: "" });
        m.rows = 2;
        m.value = meaning || "";
        t.placeholder = "term";
        m.placeholder = "what it means, e.g. which rows count";
        const rm = el("button", { text: "Remove", type: "button", className: "secondary small" });
        const row = el("div", { className: "edit-row def-row" }, [t, m, rm]);
        rm.addEventListener("click", () => row.remove());
        rows.append(row);
      };
      Object.entries(v.definitions).forEach(([t, m]) => addRow(t, m));
      const add = el("button", { text: "Add a term", type: "button", className: "secondary small" });
      add.addEventListener("click", () => addRow("", ""));
      readers.definitions = () => Object.fromEntries([...rows.querySelectorAll(".def-row")]
        .map((r) => [r.querySelector("input").value.trim(), r.querySelector("textarea").value.trim()]).filter(([t]) => t));
      out.push(section("Business definitions", "What your terms mean. Claude applies them whenever a question uses the term.", [rows, add]));
    }

    if (can("metrics")) {
      const rows = el("div", { className: "edit-rows" });
      const addMetric = (name, m) => {
        m = m || {};
        const inputs = {
          name: textInput(name, "mono"), description: textInput(m.description), sql: textInput(m.sql, "mono"),
          from: textInput(m.from, "mono"), where: textInput(m.where, "mono"), time_column: textInput(m.time_column, "mono"),
        };
        inputs.name.placeholder = "name_like_this";
        inputs.sql.placeholder = "SUM(t.amount)";
        inputs.from.placeholder = "table t JOIN other o ON o.id = t.other_id";
        inputs.where.placeholder = "optional condition";
        inputs.time_column.placeholder = "optional, e.g. t.created_at";
        const rm = el("button", { text: "Remove metric", type: "button", className: "secondary small" });
        const box = el("fieldset", { className: "edit-metric" }, [
          field("Name", inputs.name), field("Description", inputs.description), field("Expression", inputs.sql),
          field("From", inputs.from), field("Where", inputs.where), field("Time column", inputs.time_column), rm]);
        box.metricInputs = inputs;
        rm.addEventListener("click", () => box.remove());
        rows.append(box);
      };
      Object.entries(v.metrics).forEach(([n, m]) => addMetric(n, m));
      const add = el("button", { text: "Add a metric", type: "button", className: "secondary small" });
      add.addEventListener("click", () => addMetric("", {}));
      readers.metrics = () => Object.fromEntries([...rows.querySelectorAll(".edit-metric")].map((b) => {
        const i = b.metricInputs;
        return [i.name.value.trim(), { description: i.description.value, sql: i.sql.value, from: i.from.value,
          where: i.where.value, time_column: i.time_column.value }];
      }).filter(([n]) => n));
      out.push(section("Metrics", "Measures as exact SQL: SELECT expression FROM … WHERE …. Checked against the rules when you save.", [rows, add]));
    }

    if (can("examples")) {
      const t = el("textarea");
      t.rows = 4;
      t.value = v.examples.join("\n");
      t.placeholder = "Leave empty and Claude writes them from your tables";
      readers.examples = () => t.value.split("\n").map((x) => x.trim()).filter(Boolean);
      out.push(section("Example questions", "One per line, shown under the question box.", [t]));
    }

    const behaviour = [];
    if (can("ask_clarifying_questions")) {
      const c = checkbox("Ask a clarifying question when a question can be read several ways", v.ask_clarifying_questions);
      readers.ask_clarifying_questions = () => c.input.checked;
      behaviour.push(c.node);
    }
    if (can("share_results_with_ai")) {
      const c = checkbox("Share up to 50 masked result rows with Claude, for one-line summaries", v.share_results_with_ai, "is-risky");
      readers.share_results_with_ai = () => c.input.checked;
      behaviour.push(c.node);
    }
    if (can("share_category_values")) {
      const c = checkbox("Share the values of short category columns (statuses, country codes) with Claude", v.share_category_values, "is-risky");
      readers.share_category_values = () => c.input.checked;
      behaviour.push(c.node);
    }
    if (behaviour.length) out.push(section("Answers and sharing", "", behaviour));

    if (can("limits")) {
      const labels = { max_rows: "Rows per answer", timeout_ms: "Time per query (ms)", max_joins: "Joins per query",
        max_selects: "SELECT blocks per query", max_question_chars: "Question length", max_sql_chars: "SQL length",
        max_tables_in_prompt: "Tables sent with each question" };
      const inputs = {};
      const grid = el("div", { className: "edit-grid" }, Object.entries(labels).map(([k, l]) => {
        inputs[k] = numberInput(v.limits[k]);
        return field(l, inputs[k]);
      }));
      readers.limits = () => Object.fromEntries(Object.entries(inputs).map(([k, i]) => [k, Number(i.value)]));
      out.push(section("Limits", "", [grid]));
    }

    if (can("allowed_tables")) {
      const schema = textInput(v.schema, "mono");
      readers.schema = () => schema.value.trim();
      const allowed = new Set(v.allowed_tables), blocked = v.blocked_columns || {};
      const list = el("div", { className: "edit-tables" });
      const tables = e.tables || [];
      const names = new Set(tables.map((t) => t.name));
      for (const t of v.allowed_tables) if (!names.has(t)) tables.push({ name: t, columns: [] });   // listed but not found
      for (const t of tables) {
        const on = checkbox(t.name, allowed.has(t.name), "mono");
        on.input.dataset.table = t.name;
        const cols = el("div", { className: "edit-cols" }, t.columns.map((c) => {
          const b = checkbox(c, (blocked[t.name] || []).includes(c), "mono");
          b.input.dataset.table = t.name;
          b.input.dataset.column = c;
          b.input.className = "block-col";
          return b.node;
        }));
        const det = el("details", {}, [el("summary", { text: "Blocked columns" + ((blocked[t.name] || []).length ? " (" + blocked[t.name].length + ")" : "") }), cols]);
        list.append(el("div", { className: "edit-table" }, [on.node, t.columns.length ? det : el("span", { className: "quiet", text: "not found in this schema" })]));
      }
      readers.allowed_tables = () => [...list.querySelectorAll("input[data-table]:not(.block-col)")].filter((i) => i.checked).map((i) => i.dataset.table);
      readers.blocked_columns = () => {
        const out2 = {};
        for (const i of list.querySelectorAll("input.block-col")) if (i.checked) (out2[i.dataset.table] = out2[i.dataset.table] || []).push(i.dataset.column);
        return out2;
      };
      const masked = textInput(v.masked_columns.join(", "), "mono");
      readers.masked_columns = () => csv(masked.value);
      const funcs = textInput(v.blocked_functions.join(", "), "mono");
      readers.blocked_functions = () => csv(funcs.value);
      out.push(section("Data access", "Which tables Claude may use, and which columns are never read. The database user's permissions must match: run `querywarden grants` after changing them.", [
        field("Schema", schema), list,
        field("Hidden in results (column names, comma-separated)", masked),
        field("Extra blocked functions (comma-separated)", funcs, "The built-in list of dangerous PostgreSQL functions always applies."),
      ]));
    }

    if (e.models_editable) {
      const m = e.models;
      const pick = (value, choices) => {
        const s = el("select");
        const all = choices.includes(value) ? choices : [value, ...choices];
        s.replaceChildren(...all.map((c) => { const o = el("option", { text: c }); o.value = c; return o; }));
        s.value = value;
        return s;
      };
      const sm = pick(m.sql_model, e.model_choices), se = pick(m.sql_effort, e.efforts);
      const hm = pick(m.helper_model, e.model_choices), he = pick(m.helper_effort, e.efforts);
      readers.__models = () => ({ sql_model: sm.value, sql_effort: se.value, helper_model: hm.value, helper_effort: he.value });
      out.push(section("Models", "Saved in .querywarden/models.json and used straight away. Check accuracy with `querywarden eval` after changing the SQL model.", [
        el("div", { className: "edit-grid" }, [field("Writes the SQL", sm), field("Effort", se), field("Examples, summaries, follow-ups", hm), field("Effort", he)]),
      ]));
    }

    const review = el("div", { id: "settings-review", className: "edit-review" });
    review.hidden = true;
    const reviewBtn = el("button", { text: "Review changes", type: "button", className: "primary" });
    const cancel = el("button", { text: "Cancel", type: "button", className: "secondary" });
    reviewBtn.addEventListener("click", async () => {
      reviewBtn.disabled = true;
      try { await submitSettings(false, review); } finally { reviewBtn.disabled = false; }
    });
    cancel.addEventListener("click", () => showSettings());
    out.push(review, el("div", { className: "dialog-actions" }, [reviewBtn, cancel]));
    return out;
  }

  function collectSettings() {
    const policy = {};
    for (const [k, read] of Object.entries(readers)) if (!k.startsWith("__")) policy[k] = read();
    const body = { policy };
    if (readers.__models) body.models = readers.__models();
    return body;
  }

  async function submitSettings(save, review, confirmed) {
    const body = collectSettings();
    if (save) body.confirm = Boolean(confirmed);
    const r = await postJson(save ? "/api/settings/save" : "/api/settings/preview", body);
    const d = r.data;
    if (save && d.status === "ok") {
      const n = d.changes.length;
      showSettings("Saved " + n + (n === 1 ? " change" : " changes") + (d.backup ? " (backup: " + d.backup + ")" : "")
        + ". It applies within seconds." + (d.grants_note ? " " + d.grants_note : ""));
      return;
    }
    show(review, true);
    if (d.status === "invalid" || (!r.ok && d.status !== "confirm" && d.status !== "unchanged")) {
      review.replaceChildren(el("p", { className: "edit-error", text: d.message || "Couldn't check these settings." }));
      return;
    }
    if (d.status === "unchanged") { review.replaceChildren(el("p", { className: "quiet", text: "Nothing has changed." })); return; }
    const items = d.changes.map((c) => el("li", { className: c.widens ? "is-widening" : "", text: c.text }));
    const parts = [el("h3", { text: "These changes will be saved" }), el("ul", {}, items)];
    let confirmBox = null;
    if (d.needs_confirm) {
      confirmBox = checkbox("I understand: the changes marked in red let more data be seen or leave this computer", false);
      parts.push(confirmBox.node);
    }
    if (d.grants_note) parts.push(el("p", { className: "quiet", text: d.grants_note }));
    const saveBtn = el("button", { text: "Save changes", type: "button", className: "primary" });
    saveBtn.addEventListener("click", () => {
      if (confirmBox && !confirmBox.input.checked) { confirmBox.node.classList.add("needs-attention"); return; }
      saveBtn.disabled = true;
      saveBtn.textContent = "Saving and applying…";   // the server rebuilds its setup before answering
      submitSettings(true, review, confirmBox ? confirmBox.input.checked : false);
    });
    parts.push(el("div", { className: "dialog-actions" }, [saveBtn]));
    review.replaceChildren(...parts);
    review.scrollIntoView({ block: "nearest" });
  }
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
        ["Tables sent with each question", p.tables_per_question || "all"],
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
        ["Clarifying questions", p.ask_clarifying_questions === false ? "Off: Claude picks the most natural reading"
          : "On: Claude asks when a question has several readings"],
      ]),
      el("h3", { text: "Live updates" }),
      rows([
        ["Picks up changes", s.live && s.live.enabled
          ? "Yes: policy.yaml and the database are checked every 30 seconds" : "No: restart to apply changes"],
        ...(s.live && s.live.last_change ? [["Last change", new Date(s.live.last_change).toLocaleString() + " (" + s.live.last_change_reason + ")"]] : []),
        ...(s.live && s.live.last_error ? [["Problem", s.live.last_error, "is-on"]] : []),
        ["Refusal fallback", m.refusal_fallback_sql ? "on for SQL writing" : "off"],
      ]),
      el("h3", { text: "Metrics" }),
      Object.keys(p.metrics || {}).length
        ? rows(Object.entries(p.metrics).map(([name, m]) => [name, m.description + "  " + m.sql]))
        : el("p", { className: "quiet", text: "None. Add a metrics section to policy.yaml to define measures as exact SQL." }),
      el("h3", { text: "Business definitions" }),
      Object.keys(p.definitions || {}).length
        ? rows(Object.entries(p.definitions).map(([term, meaning]) => [term, meaning]))
        : el("p", { className: "quiet", text: "None. Add a definitions section to policy.yaml so your business terms always mean the same thing." }),
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
