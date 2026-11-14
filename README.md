# pg-perf-mcp-spring

[![CI](https://github.com/dixitgurv/pg-perf-mcp-spring/actions/workflows/ci.yml/badge.svg)](https://github.com/dixitgurv/pg-perf-mcp-spring/actions/workflows/ci.yml)

An MCP server, built with Spring Boot 4 and Spring AI, that lets an AI agent (Claude, or any MCP client) diagnose PostgreSQL performance the way a DBA would: read the plan, find where the time actually went, and propose a specific fix.

It is read-only by design. The agent can look at anything and change nothing.

```
You:     the customer orders page got slow, can you check?
Agent:   → explain_query("SELECT c.email, count(*) FROM customers c JOIN orders o ON o.customer_id = c.id ...")

         The join reads all 999,999 rows of orders to produce 501, because orders.customer_id
         has no index. Postgres doesn't index foreign keys automatically. Suggested fix:

           CREATE INDEX CONCURRENTLY ON orders (customer_id);
```

## Tools

| Tool | What it answers |
|---|---|
| `explain_query` | Runs `EXPLAIN (ANALYZE, BUFFERS)` on one query; returns the nodes where time was spent and rule-based findings with fixes |
| `slow_queries` | Top queries from `pg_stat_statements` by total time, mean time, calls or disk reads |
| `table_health` | Seq-scan vs index-scan ratio, dead tuples, last vacuum/analyze per table |
| `unused_indexes` | Indexes never scanned, largest first (excludes unique and constraint indexes) |
| `redundant_indexes` | Indexes whose columns are a prefix of another index on the same table |
| `active_queries` | What is running now, for how long, and what it is waiting on |
| `database_overview` | Cache hit rate, temp file usage, deadlocks, memory settings, connection count |

Every tool carries the MCP `readOnlyHint`, so clients can approve them without asking each time.

## Plan analysis rules

Each rule is one class implementing `PlanRule`, in [`plan/rules`](src/main/java/io/github/dixitgurv/pgperf/plan/rules):

| Rule | Fires when | Suggests |
|---|---|---|
| `seq_scan_filter_waste` | A sequential scan discards 10k+ rows and 10x more than it keeps | An index on the filter columns, written out as DDL |
| `join_scans_whole_table` | A hash/merge join reads 100k+ rows of a table to keep under 1% | An index on that table's join key (usually an unindexed foreign key) |
| `row_misestimate` | Actual rows differ from the estimate by 10x or more | `ANALYZE`, then `CREATE STATISTICS` on the correlated columns |
| `sort_spilled_to_disk` | A sort used disk (summed across parallel workers) | A sized `work_mem`, or an index / `LIMIT` when the sort is too big for memory |
| `hash_multi_batch` | A hash table split into batches | `work_mem` / `hash_mem_multiplier`, or a smaller build side |
| `nested_loop_repeated_scan` | The inner side of a nested loop is a scan repeated 1000+ times | An index on the inner join column |
| `index_only_scan_heap_fetches` | An index-only scan still visits the heap for 20%+ of rows | `VACUUM` to refresh the visibility map |
| `cold_cache_reads` | Most blocks came from disk rather than shared buffers | Re-run warm; check working set vs `shared_buffers` |

**The rules make the decisions; the model explains them.** The same plan always gives the same findings, and every rule has a unit test. The agent turns findings into a conversation; it never decides on its own what is wrong.

The analyzer also reports **exclusive** time per node (a node's time minus its children's), which is what shows where the time went. Under a `Gather`, worker loops run concurrently, so their time is not multiplied by the loop count. Getting that wrong makes parallel scans look several times slower than they are.

## Read-only, in layers

The caller is an LLM, so no single check is trusted alone:

1. **Postgres itself.** Every connection starts with `default_transaction_read_only = on` (Hikari `connection-init-sql`). A write fails inside the database even if everything below were bypassed.
2. **Read-only transactions, always rolled back.** `EXPLAIN ANALYZE` really executes the query, so nothing it does may persist.
3. **`SET LOCAL statement_timeout`** on every call, so a runaway query can't hold the database.
4. **`QueryGuard`** rejects multi-statement input, non-SELECT statements and side-effect functions such as `pg_terminate_backend` before anything is sent, so the agent gets a clear reason.

There's a test for a write that slips past layer 4 (`WITH x AS (DELETE ...) SELECT ...` starts with `WITH`) and is stopped by layer 1.

## Results on the demo database

[`demo/seed.sql`](demo/seed.sql) builds 1M orders, 2M order items and 100k customers with problems planted on purpose. For each query in [`demo/queries.sql`](demo/queries.sql), I applied the fix the server suggested and ran the query again (median of 5, PostgreSQL 14, Apple M1):

| Query | Finding | Fix applied | Before | After |
|---|---|---|---|---|
| Orders by status | `seq_scan_filter_waste` | Partial index on `status` | 20.7 ms | 5.1 ms |
| Customer's orders | `join_scans_whole_table` | Index on `orders.customer_id` | 30.8 ms | 0.15 ms |
| Country + city count | `row_misestimate` (20x under) | `CREATE STATISTICS ON country, city` | estimate 244 rows | estimate 4,800 (actual 5,000) |
| Sort 2M rows | `sort_spilled_to_disk` | `SET work_mem = '213MB'` | 523 ms | 524 ms |

The sort is the honest case. The memory estimate was right: the server asked for 213 MB, and the in-memory quicksort used 205 MB. But it wasn't faster, because sorting 2M rows costs the same in memory as in a well-behaved external merge on fast storage. That's why the suggestion goes on to say the bigger win is not sorting this much: an index matching the `ORDER BY`, or a `LIMIT`. The tool's job is to give the measurement that settles the question, not to promise a speed-up.

## Run it

Requires Java 17+ and a PostgreSQL database (with `pg_stat_statements` for `slow_queries`).

```bash
./mvnw -DskipTests package
```

Then register it with your MCP client. For Claude Desktop, in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "pg-perf": {
      "command": "java",
      "args": ["-jar", "/path/to/pg-perf-mcp-spring-0.1.0.jar"],
      "env": {
        "PG_PERF_JDBC_URL": "jdbc:postgresql://localhost:5432/mydb",
        "PG_PERF_USER": "perf_reader",
        "PG_PERF_PASSWORD": "..."
      }
    }
  }
}
```

For Claude Code:

```bash
claude mcp add pg-perf --env PG_PERF_JDBC_URL=jdbc:postgresql://localhost:5432/mydb --env PG_PERF_USER=perf_reader --env PG_PERF_PASSWORD=... -- java -jar /path/to/pg-perf-mcp-spring-0.1.0.jar
```

### Agent skill

The server gives the agent tools; [`skills/postgres-performance`](skills/postgres-performance/SKILL.md) tells it when and how to use them: which tool to start with for each kind of question, how to read the findings, when a standard fix is wrong, and how to recover from each error. To install it for Claude Code:

```bash
cp -r skills/postgres-performance ~/.claude/skills/
```

For a fifth layer of safety, connect as a role that can only read:

```sql
CREATE ROLE perf_reader LOGIN PASSWORD '...';
GRANT pg_monitor, pg_read_all_data TO perf_reader;
```

## Tests

```bash
./mvnw verify
```

| Suite | What it covers |
|---|---|
| `PlanAnalyzerTest` (14) | Each rule against a trimmed real plan, including the parallel-timing and dedup edge cases |
| `QueryGuardTest` (20) | What the static check allows and refuses, including `;` hidden in strings and comments |
| `DiagnosticsIntegrationTest` (16) | Real PostgreSQL 17 in Testcontainers seeded with the demo data: every planted problem is found, every write is refused, timeouts come back as readable errors |
| `McpStdioIT` (3) | Launches the packaged jar and drives it with the MCP Java SDK client over stdio, exactly as Claude Desktop does |

Without Docker, set `PG_PERF_TEST_JDBC_URL` to any Postgres with `pg_stat_statements` preloaded.

## Design

```
src/main/java/io/github/dixitgurv/pgperf/
  plan/          pure plan analysis: no Spring, no database
    PlanAnalyzer       walks the plan, runs the rules, computes exclusive time
    PlanNode           typed access to one EXPLAIN node (per-loop vs total rows and time)
    PlanRule           one interface, eight implementations in rules/
  db/
    ReadOnlyDatabase   every call: read-only transaction, statement timeout, rollback
    QueryGuard         static check before anything is sent
    CatalogQueries     pg_stat_statements and catalog SQL
  mcp/
    PerfTools          the @McpTool methods: thin, validate and delegate
    ToolFailure        maps SQLState 25006 / 57014 and refusals to messages the agent can act on
```

More detail, including why each decision was made, is in [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md).

Stack: Java 17, Spring Boot 4.1, Spring AI 2.0 (MCP server, stdio transport), Jackson 3, JDBC with HikariCP, JUnit 5, Testcontainers.
