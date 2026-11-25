# How QueryWarden compares with similar tools (November 2026)

A survey of text-to-SQL tools and "talk to your data" products, made on 2026-11-25 from their public
documentation, repositories and security advisories. Each claim links its source. A few details came
only from search-result summaries; those are marked. Dataline was not covered, and ThoughtSpot, the
Supabase assistant, Chat2DB and DB-GPT were only looked at lightly.

## Where the projects stand

| Project | Status | Source |
|---|---|---|
| Vanna | Repository archived (read-only) since 29 Mar 2026 | https://github.com/vanna-ai/vanna |
| WrenAI | Active. The older Docker app is now "GenBI Classic", with no new features or security fixes | https://raw.githubusercontent.com/Canner/WrenAI/HEAD/README.md |
| Dataherald | Listed as inactive by Y Combinator | https://www.ycombinator.com/companies/dataherald |
| Defog SQLCoder | Original model marked outdated; Cloudflare deprecated sqlcoder-7b-2 on 30 May 2026 | https://huggingface.co/defog/sqlcoder ; https://developers.cloudflare.com/ai/models/@cf/defog/sqlcoder-7b-2/ |
| Amazon QuickSight | Now Amazon Quick Suite | https://aws.amazon.com/blogs/business-intelligence/reimagine-business-intelligence-amazon-quicksight-evolves-to-amazon-quick-suite |

## Features others have that QueryWarden doesn't (yet)

| Feature | Found in | Why it matters | Source |
|---|---|---|---|
| Semantic layer: metrics, dimensions and joins defined as data | Snowflake semantic views, WrenAI MDL, Metabase metrics, Databricks Genie | Metric definitions are versioned and exact, which goes further than prose definitions | https://docs.snowflake.com/en/user-guide/snowflake-cortex/cortex-analyst ; WrenAI README |
| Library of verified question-and-SQL pairs used at answer time, marked "trusted" | Snowflake Cortex Analyst (verified queries), Genie (trusted assets), ThoughtSpot (reference questions), Amazon Quick (verified questions) | Recurring questions get the same, checked answer | https://docs.snowflake.com/en/user-guide/views-semantic/verified-query-repository ; https://learn.microsoft.com/da-dk/azure/databricks/genie/trusted-assets ; https://docs.thoughtspot.com/cloud/latest/spotter-coaching-options |
| Clarifying questions instead of guessing | Genie | Ambiguous questions get the right answer, not a plausible one | https://docs.databricks.com/aws/en/genie/talk-to-genie |
| Feedback: "Fix it", ask for review, expert corrects | Genie, ThoughtSpot (votes, conversation log) | Mistakes become curated examples | https://docs.databricks.com/aws/en/genie/talk-to-genie ; https://docs.thoughtspot.com/cloud/26.6.0.cl/spotter-coach-after |
| Approve the SQL before it runs | LangChain (human-in-the-loop middleware) | Optional gate for sensitive data | https://docs.langchain.com/oss/python/langchain/sql-agent |
| Queries run as the signed-in person (their permissions, row-level security) | Vanna 2.0, Looker/BigQuery agents, Metabot; WrenAI paid tier | In QueryWarden's shared mode everyone uses the same database user | https://github.com/vanna-ai/vanna ; https://docs.cloud.google.com/gemini/docs/conversational-analytics-api/known-limitations |
| Slack and Teams | Genie, Metabot, Dataherald | Answers where people already work | https://docs.databricks.com/aws/en/genie-one/genie-slack ; https://docs.databricks.com/aws/en/integrations/msft-teams |
| Alerts on a schedule | Metabot (set up from Slack) | Questions become monitoring | Metabase 60 announcement (dev.to, "metabase-60-we-made-ai-open-source…") |
| MCP server | Metabase, WrenAI | Lets AI assistants use the same governed pipeline | same; WrenAI README |
| Reusing saved questions and dashboards | Metabot looks for an existing question first; WrenAI dashboards | Fewer duplicate, inconsistent answers | https://www.metabase.com/features/metabot-ai |
| Picking relevant tables for large schemas | LlamaIndex table retriever, WrenAI, LangChain | A full schema doesn't fit in the prompt | https://developers.llamaindex.ai/python/examples/index_structs/struct_indices/sqlindexdemo/ |
| Many database types | WrenAI (22+ sources), Vanna | Wider use | WrenAI README ; https://github.com/vanna-ai/vanna |
| Multi-step research reports, PDF export | Genie agent mode | "Why" questions, not only single queries | https://docs.databricks.com/aws/en/genie/talk-to-genie |

## Problems in those projects, and where QueryWarden stands

| Problem | Project | Source | QueryWarden |
|---|---|---|---|
| Prompt injection → remote code execution: model-written Plotly code run with `exec` (CVE-2024-5565, CVE-2024-5826) | Vanna | https://jfrog.com/blog/prompt-injection-attack-code-execution-in-vanna-ai-cve-2024-5565/ ; https://advisories.gitlab.com/pypi/vanna/CVE-2024-5826/ | Never runs model-written code; charts are drawn from data by fixed code |
| Prompt injection → code execution despite allow-lists (CVE-2024-12366) | PandasAI | https://kb.cert.org/vuls/id/148244 | Same: no code execution |
| Prompt injection → SQL injection or arbitrary SQL (CVE-2023-32785, CVE-2023-36189; CVE-2024-8309 in a related chain) | LangChain | https://advisories.gitlab.com/pkg/pypi/langchain/CVE-2023-32785/ ; https://nvd.nist.gov/vuln/detail/CVE-2023-36189 | Every query is parsed and checked, then run as a read-only user with column-level grants; `doctor` verifies those grants |
| SQL injection through text-to-SQL engines (CVE-2024-23751, disputed) | LlamaIndex | https://nvd.nist.gov/vuln/detail/CVE-2024-23751 | As above |
| Unauthenticated SQL API, permissive CORS, plugin-upload code execution (CVE-2024-10835, CVE-2024-10906, CVE-2025-51459) | DB-GPT | https://advisories.gitlab.com/pypi/dbgpt/CVE-2024-10835/ ; https://www.tenable.com/cve/CVE-2024-10906 ; https://nvd.nist.gov/vuln/detail/CVE-2025-51459 | Local-only by default; shared mode needs tokens; no cross-site access; no plugins |
| SQL injection, vendor unresponsive (CVE-2025-9148, CVE-2026-4173) | Chat2DB | https://nvd.nist.gov/vuln/detail/CVE-2025-9148 ; https://vuldb.com/vuln/320527 (search summary) | As above |
| "Lethal trifecta": an untrusted support ticket plus a connection that bypassed row-level security leaked data | Supabase MCP | https://generalanalysis.com/blog/supabase-mcp-blog ; https://simonwillison.net/2025/Jul/06/supabase-mcp-lethal-trifecta/ | Least-privilege user; AI text shown as plain text; the page's security policy blocks outside images and connections (checked: a test image request to another site was blocked) |
| Only the first 100 tables are looked at; may invent table names | Metabot | https://www.metabase.com/docs/latest/ai/metabot | Sends every allowed table; unknown tables are rejected by the checks. Large schemas are not yet handled |
| Follow-ups can't use earlier results; long conversations degrade | Cortex Analyst | https://docs.snowflake.com/en/user-guide/snowflake-cortex/cortex-analyst | Follow-ups use the last three questions and their SQL; tested in the evaluation |
| Row caps (1,000–5,000) and output limits | Google Conversational Analytics | https://docs.cloud.google.com/gemini/docs/conversational-analytics-api/known-limitations | Row cap is a policy setting (default 200) |
| Low accuracy on real enterprise schemas (21.3% for o1-preview on Spider 2.0 when published; higher on later leaderboards) | General | https://arxiv.org/abs/2411.07763 ; https://spider2-sql.github.io/ | Measured with the evaluation set; it needs real questions from real users to say more |

## Recommended next steps for QueryWarden

Items 1 and 2 were built on 2026-11-25 (see "Verified answers and feedback" in the README).

1. **Verified answers library.** Save checked question-and-SQL pairs, show matching ones to Claude as
   examples, and mark answers that reuse them as "verified". Builds directly on the evaluation set.
2. **Feedback.** Thumbs up/down and "this is wrong" on each answer, a review list, and a one-click way to
   turn a corrected answer into a verified one and an evaluation question.
3. **Clarifying questions.** Let Claude ask "by revenue or by number of orders?" when it can't tell.
4. **Per-person data permissions in shared mode.** Run each person's queries with their own database
   role or a row-level-security setting.
5. **Structured metrics in `policy.yaml`.** Define metrics and their joins as data, next to the prose
   definitions.
6. **Large schemas.** Pick the relevant tables for each question instead of sending all of them.
7. **MCP server and Slack bot** that go through the same checks.
8. **Saved questions with scheduled alerts**, leading to dashboards built from questions.
