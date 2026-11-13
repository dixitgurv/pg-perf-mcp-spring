# How it works

A walk through one request, then the reason behind each design decision.

## One request, end to end

An agent asks: *why is this query slow?*

1. **The client launches the server.** Claude Desktop (or any MCP client) runs `java -jar pg-perf-mcp-spring.jar` and speaks JSON-RPC over the process's stdin and stdout. That's the MCP stdio transport, configured by `spring.ai.mcp.server.stdio=true`.
2. **Spring AI finds the tools.** At startup, its annotation scanner finds every `@McpTool` method on Spring beans (here, `PerfTools`). It builds a JSON schema for each one from the method's parameters and `@McpToolParam` descriptions, and registers them with the MCP server. The agent sees the names, descriptions and schemas when it calls `tools/list`.
3. **The agent calls `explain_query`.** Spring AI deserializes the arguments and invokes `PerfTools.explainQuery(sql, analyzeQuery, timeoutMs)`.
4. **`QueryGuard.check`** blanks out comments and string literals, then rejects anything that isn't a single `SELECT`/`WITH`/`VALUES`/`TABLE` statement or that calls a side-effect function.
5. **`ReadOnlyDatabase.explain`** opens a read-only Spring transaction, marks it rollback-only, runs `SET LOCAL statement_timeout`, then `EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT JSON) <query>`. The connection was already read-only before any of that, thanks to the Hikari init SQL.
6. **`PlanAnalyzer.analyze`** walks the JSON plan tree. At each node it runs all eight `PlanRule`s and records the node's exclusive time. It returns findings sorted by severity, plus the three hottest nodes.
7. **`PerfTools` returns an `ExplainResult` record.** Spring AI serializes it with Jackson 3 into the tool result's text content.
8. **If anything fails,** `ToolFailure.translate` turns it into one sentence the agent can act on, and Spring AI returns it as an MCP error result (`isError: true`), not a stack trace.

## Why the code is shaped this way

### The rules make decisions; the LLM explains them

`plan/` has no Spring and no database. A rule is a plain class that looks at a node and adds a `Finding`. That gives three properties:

- **Deterministic.** The same plan always produces the same findings. An LLM asked "what's wrong with this plan?" can give different answers to the same input.
- **Testable.** `PlanAnalyzerTest` runs every rule against trimmed real plans in about 200 ms, with no database.
- **Auditable.** When a DBA disagrees with a finding, there's one class and one threshold to look at.

The agent's job is what LLMs are good at: explaining the finding, relating it to the user's question, and walking through the trade-offs of the fix.

### One interface, one class per rule

`PlanRule` is a strategy interface. Adding a rule is one new class and one line in `PlanAnalyzer`'s constructor, with no change to the others. `PlanAnalyzer` also accepts a custom rule list, so a team could run only its own rules.

### `PlanNode` hides the two traps in EXPLAIN output

- **Per-loop averages.** `Actual Rows` and `Actual Total Time` are averages per loop. An index lookup run 50,000 times shows `Actual Rows: 1`. `totalRows()` multiplies by loops. `RowMisestimateRule` deliberately compares per-loop numbers, because that's what the planner estimated.
- **Parallel workers.** Under a `Gather`, the "loops" are workers running at the same time. Multiplying their time by loops triple-counts it. `PlanNode` knows whether it's below a Gather and doesn't.

Both are easy to get wrong, and each has a regression test.

### Exclusive time, not inclusive

A node's `Actual Total Time` includes all of its children. The top node always looks most expensive, which says nothing. Subtracting the children's time gives the time spent in the node itself, which is what tells you where to look.

### Safety in layers, strongest at the bottom

| Layer | Where | Stops |
|---|---|---|
| `default_transaction_read_only = on` | Hikari `connection-init-sql` | Any write, inside Postgres |
| Read-only transaction, rollback-only | `ReadOnlyDatabase` | Anything `EXPLAIN ANALYZE` executed from persisting |
| `SET LOCAL statement_timeout` | `ReadOnlyDatabase` | Runaway queries |
| `QueryGuard` | before the database | Multi-statement input, non-reads, side-effect functions, with a clear message |

The regex guard is the weakest layer (a regex can't parse SQL), so it isn't trusted alone. Its job is to fail fast with a good message. `DiagnosticsIntegrationTest.writeHiddenFromTheStaticCheckIsStoppedByPostgres` proves the bottom layer catches what the guard lets through.

`SET LOCAL` is used rather than `SET` because it ends with the transaction. With a connection pool, a plain `SET` would leak onto the next caller's session.

### Errors the agent can act on

`ToolFailure` maps by SQLState, not message text:

- `25006` (read-only transaction) becomes "Refused: this server is read-only and the query tries to write."
- `57014` (query canceled) becomes advice to retry with `analyzeQuery=false` or a larger timeout.

An agent given a 40-line stack trace tends to retry the same thing. Given one sentence, it changes its approach.

### stdout belongs to the protocol

With the stdio transport, every byte on stdout must be MCP JSON. Spring Boot's banner or one log line there corrupts the stream, and the client disconnects with an unhelpful parse error. So the banner is off, and `logback-spring.xml` sends all logging to stderr, which MCP clients show as server logs. `McpStdioIT` launches the real jar to make sure this holds.

### Tests at three levels

| Level | Test | Needs |
|---|---|---|
| Logic | `PlanAnalyzerTest`, `QueryGuardTest` | nothing |
| Integration | `DiagnosticsIntegrationTest`: Spring context plus real PostgreSQL 17 in Testcontainers, seeded with the demo data | Docker |
| End to end | `McpStdioIT`: the packaged jar, driven by the MCP Java SDK client over stdio | Docker, runs in `verify` via Failsafe |

## Questions worth being able to answer

**Why MCP instead of a REST API?** MCP is the standard way to give an LLM tools. The client discovers the tools, their schemas and descriptions at runtime, so any MCP client can use this server with no integration code. A REST API would need a custom adapter for each agent framework.

**Why does `EXPLAIN ANALYZE` need a rollback?** Because it really executes the statement. `EXPLAIN ANALYZE DELETE ...` deletes rows. The read-only session stops that, and the rollback makes sure nothing persists even for statements Postgres considers read-only but with side effects.

**Why not let the LLM read the plan directly?** It can: the raw plan is included in the result. But plans for real queries are long, and LLMs misread per-loop numbers and parallel timing just as people do. The rules encode those details once, correctly, with tests.

**How would you extend it?** Add a `PlanRule` class (for example, a rule that flags `Rows Removed by Index Recheck` on lossy bitmap scans) with a fixture test. For a new tool, add a `@McpTool` method and a catalog query.

**What would you change for production use?** Connect as a role with only `pg_monitor` and `pg_read_all_data`. Use the streamable HTTP transport (`spring-ai-starter-mcp-server-webmvc`) behind authentication, so one server can serve a team instead of one desktop. Add a per-tool rate limit, since `explain_query` executes real queries.
