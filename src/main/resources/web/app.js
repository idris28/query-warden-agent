// QueryWarden web page.
// Security rule for this file: text from the server (questions, data, messages, SQL) is only ever
// inserted with textContent or as text nodes, never as HTML.
"use strict";

(function () {
  const $ = (id) => document.getElementById(id);
  const RECENT_KEY = "querywarden.recent";
  const RECENT_MAX = 12;

  const form = $("ask-form");
  const input = $("question");
  const button = $("ask-button");
  const statusEl = $("status");
  let chart = null;
  let lastResult = null;
  let busy = false;

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

  function renderRecent() {
    const list = loadRecent();
    const ol = $("recent");
    ol.replaceChildren(...list.map((q) => {
      const b = el("button", { text: q, type: "button" });
      b.addEventListener("click", () => { input.value = q; ask(q); });
      return el("li", {}, [b]);
    }));
    show($("recent-empty"), list.length === 0);
    show($("recent-clear"), list.length > 0);
  }

  $("recent-clear").addEventListener("click", () => { saveRecent([]); renderRecent(); });

  // ------------------------------------------------------------------ available data + examples
  async function loadSchema() {
    try {
      const res = await fetch("/api/schema", { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error();
      const data = await res.json();
      renderTables(data.tables || []);
      renderExamples(data.examples || []);
      if (data.max_question_chars) input.maxLength = data.max_question_chars;
    } catch {
      $("tables").replaceChildren(el("p", { className: "quiet", text: "Couldn't load the list of tables. Is the database running?" }));
    }
  }

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
    const ul = $("examples");
    ul.replaceChildren(...examples.map((q) => {
      const b = el("button", { text: q, type: "button" });
      b.addEventListener("click", () => { input.value = q; ask(q); });
      return el("li", {}, [b]);
    }));
    show($("examples-wrap"), examples.length > 0);
  }

  // ------------------------------------------------------------------ asking
  form.addEventListener("submit", (e) => { e.preventDefault(); ask(input.value); });
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); ask(input.value); }
  });

  async function ask(raw) {
    const question = (raw || "").trim();
    if (!question || busy) return;
    busy = true;
    button.disabled = true;
    button.textContent = "Asking…";
    setStatus("Working on it. This usually takes about 5 seconds.");

    let data;
    try {
      const res = await fetch("/api/ask", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ question }),
      });
      try { data = await res.json(); } catch { data = null; }
      if (!data || typeof data.status !== "string") {
        data = { status: "failed", message: "The server sent an unexpected reply (HTTP " + res.status + ")." };
      }
    } catch {
      data = { status: "failed", message: "Couldn't reach QueryWarden. Is `querywarden serve` still running?" };
    } finally {
      busy = false;
      button.disabled = false;
      button.textContent = "Ask";
    }

    setStatus("");
    // Problems before the agent ran (busy, rate limit, server down) have no "sql" field: show them as
    // a status line and keep the previous answer on screen.
    if (data.status === "failed" && !("sql" in data)) {
      setStatus(data.message || "Something went wrong.", true);
      return;
    }
    remember(question);
    renderAnswer(question, data);
    input.value = "";   // ready for the next question; this one is in the heading and in Recent
  }

  // ------------------------------------------------------------------ rendering an answer
  function renderAnswer(question, data) {
    lastResult = data;
    const answer = $("answer");
    show(answer, true);   // visible before drawing, so the chart can measure its space
    $("answer-question").textContent = question;
    const msg = $("answer-message");
    const ok = data.status === "ok";

    if (ok) {
      const n = data.rows.length;
      const secs = (data.elapsed_ms / 1000).toFixed(1);
      $("answer-summary").textContent =
        (n === 1 ? "1 row" : n + " rows") + (data.truncated ? " (cut off at the row limit)" : "") +
        ", answered in " + secs + " seconds";
      show(msg, false);
    } else {
      $("answer-summary").textContent = data.status === "unanswerable" ? "Not answered"
        : data.status === "blocked" ? "Question blocked" : "Couldn't answer";
      msg.textContent = data.message || "";
      msg.classList.toggle("is-declined", data.status !== "unanswerable");
      show(msg, true);
    }

    renderTable(ok ? data : null);
    renderChart(ok ? data : null);
    renderWarnings(ok ? data.warnings || [] : []);
    renderHow(data);

    answer.classList.remove("is-fresh");
    void answer.offsetWidth; // restart the one entrance animation
    answer.classList.add("is-fresh");
  }

  function isNumberType(t) { return t === "number"; }

  // One formatter per column, decided from all its values, so a column reads consistently:
  //   numbers: two decimals if any value has a fraction (money), otherwise whole numbers
  //   dates:   "Jan 2025" when every value is the first of a month, otherwise 2025-08-21
  function columnFormatters(data) {
    return data.columns.map((c, i) => {
      const values = data.rows.map((r) => r[i]).filter((v) => v !== null && v !== undefined);
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

  function renderTable(data) {
    const table = $("table");
    if (!data || data.columns.length === 0) {
      table.replaceChildren();
      show($("table-wrap"), false);
      show($("table-tools"), false);
      return;
    }
    const head = el("tr", {}, data.columns.map((c) =>
      el("th", { text: c.name, className: isNumberType(c.type) ? "num" : "" })));
    const fmt = columnFormatters(data);
    const body = data.rows.map((row) => el("tr", {}, row.map((v, i) => {
      const type = data.columns[i].type;
      const cls = [];
      if (isNumberType(type)) cls.push("num");
      if (v === "***") cls.push("masked");
      if (v === null) cls.push("null");
      return el("td", { text: fmt[i](v), className: cls.join(" ") });
    })));
    table.replaceChildren(el("thead", {}, [head]), el("tbody", {}, body));
    show($("table-wrap"), true);
    show($("table-tools"), data.rows.length > 0);
  }

  function renderWarnings(warnings) {
    const ul = $("warnings");
    ul.replaceChildren(...warnings.map((w) => el("li", { text: w })));
    show(ul, warnings.length > 0);
  }

  function renderHow(data) {
    const how = $("how");
    const hasSql = Boolean(data.sql);
    const rejected = data.rejected_attempts || [];
    show(how, hasSql || rejected.length > 0);
    $("how-explanation").textContent = data.explanation || "";
    show($("how-explanation"), Boolean(data.explanation));
    $("how-sql").textContent = hasSql ? prettySql(data.sql) : "";
    show($("how-sql").parentElement, hasSql);
    const list = $("how-rejected-list");
    list.replaceChildren(...rejected.map((a) =>
      el("li", {}, [el("span", { text: a.reason }), el("code", { text: a.sql })])));
    show($("how-rejected"), rejected.length > 0);
    how.open = false;
  }

  // Line breaks before the main clauses so the SQL is readable. Text only; nothing is executed.
  function prettySql(sql) {
    return sql.replace(/\s+(FROM|WHERE|GROUP BY|HAVING|ORDER BY|LIMIT|JOIN|LEFT JOIN|RIGHT JOIN|INNER JOIN|UNION|WITH)\s/g, "\n$1 ");
  }

  // ------------------------------------------------------------------ charts
  // Draw a chart only when it clearly helps:
  //   date column + 1–3 number columns          -> line chart
  //   text column + 1–3 number columns, ≤ 30 rows -> bar chart
  //   anything else                             -> table only
  function chooseChart(data) {
    if (!data || data.rows.length < 2) return null;
    const cols = data.columns.map((c, i) => ({ ...c, i }));
    const isId = (name) => /(^id$|_id$)/i.test(name);
    const numbers = cols.filter((c) => c.type === "number" && !isId(c.name));
    const label = cols.find((c) => c.type === "date" || c.type === "datetime")
      || cols.find((c) => c.type === "text");
    if (!label || numbers.length === 0 || numbers.length > 3) return null;
    const masked = data.rows.some((r) => r[label.i] === "***");
    if (masked) return null;
    const isTime = label.type === "date" || label.type === "datetime";
    if (!isTime && data.rows.length > 30) return null;
    return { kind: isTime ? "line" : "bar", label, numbers, horizontal: !isTime && data.rows.length > 6 };
  }

  function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

  function renderChart(data) {
    if (chart) { chart.destroy(); chart = null; }
    const wrap = $("chart-wrap");
    const plan = chooseChart(data);
    if (!plan || typeof window.Chart !== "function") { show(wrap, false); return; }

    const palette = [cssVar("--teal"), cssVar("--brass"), cssVar("--plum")];
    const ink = cssVar("--ink"), muted = cssVar("--muted"), rule = cssVar("--rule");
    let rows = data.rows;
    if (plan.kind === "line") rows = [...rows].sort((a, b) => String(a[plan.label.i]).localeCompare(String(b[plan.label.i])));
    const fmt = columnFormatters({ columns: data.columns, rows });
    const labels = rows.map((r) => fmt[plan.label.i](r[plan.label.i]));
    const datasets = plan.numbers.map((c, k) => ({
      label: c.name,
      data: rows.map((r) => (typeof r[c.i] === "number" ? r[c.i] : null)),
      backgroundColor: palette[k],
      borderColor: palette[k],
      borderWidth: plan.kind === "line" ? 2 : 0,
      pointRadius: plan.kind === "line" ? 3 : 0,
      tension: 0.2,
      maxBarThickness: 36,
    }));

    const valueAxis = { grid: { color: rule }, ticks: { color: muted }, border: { display: false }, beginAtZero: true };
    const labelAxis = { grid: { display: false }, ticks: { color: ink, autoSkip: true, maxRotation: 0 }, border: { color: rule } };
    show(wrap, true);
    chart = new window.Chart($("chart"), {
      type: plan.kind,
      data: { labels, datasets },
      options: {
        maintainAspectRatio: false,
        indexAxis: plan.horizontal ? "y" : "x",
        animation: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? false : { duration: 300 },
        plugins: {
          legend: { display: datasets.length > 1, labels: { color: ink, boxWidth: 12 } },
          tooltip: { callbacks: { label: (ctx) => ctx.dataset.label + ": " + formatNumber(ctx.parsed[plan.horizontal ? "x" : "y"]) } },
        },
        scales: plan.horizontal ? { x: valueAxis, y: labelAxis } : { x: labelAxis, y: valueAxis },
      },
    });
    const caption = (plan.kind === "line" ? "Line chart of " : "Bar chart of ") +
      plan.numbers.map((c) => c.name).join(", ") + " by " + plan.label.name + ". The same data is in the table below.";
    $("chart-caption").textContent = caption;
    $("chart").setAttribute("aria-label", caption);
  }

  // Redraw with the right colours when the system switches between light and dark.
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
    if (lastResult && lastResult.status === "ok") renderChart(lastResult);
  });

  // ------------------------------------------------------------------ CSV download
  $("download").addEventListener("click", () => {
    if (!lastResult || lastResult.status !== "ok") return;
    const esc = (v) => {
      let s = v === null || v === undefined ? "" : String(v);
      if (typeof v === "string" && /^[=+\-@\t\r]/.test(s)) s = "'" + s;   // stop spreadsheet formula injection
      return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    };
    const lines = [lastResult.columns.map((c) => esc(c.name)).join(",")]
      .concat(lastResult.rows.map((r) => r.map(esc).join(",")));
    const blob = new Blob([lines.join("\r\n")], { type: "text/csv;charset=utf-8" });
    const a = el("a");
    a.href = URL.createObjectURL(blob);
    a.download = "querywarden-result.csv";
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  });

  // ------------------------------------------------------------------ start
  renderRecent();
  loadSchema();
  input.focus();
})();
