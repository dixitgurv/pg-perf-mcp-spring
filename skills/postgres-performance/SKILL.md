---
name: postgres-performance
description: Diagnose slow PostgreSQL queries and database health with the pg-perf MCP server. Use when the user says a query, page, endpoint or job is slow, asks why Postgres is using a sequential scan, asks which indexes to add or drop, or wants a database health check. Requires the pg-perf MCP server (tools explain_query, slow_queries, table_health, unused_indexes, redundant_indexes, active_queries, database_overview).
---

# PostgreSQL performance diagnosis

You have read-only tools that measure a PostgreSQL database. Your job is to turn their findings into a clear explanation and a fix the user can review. The tools decide what is wrong; you explain it, connect it to the user's question, and propose the change.

## Pick the starting point

| The user says | Start with |
|---|---|
| "This query is slow" (and gives the SQL) | `explain_query` with that SQL |
| "The app / page / endpoint is slow" (no SQL) | `slow_queries`, then `explain_query` on the top entries |
| "Is the database healthy?" | `database_overview`, then `table_health` |
| "Which indexes should I add / drop?" | `slow_queries` + `explain_query` for adds; `unused_indexes` + `redundant_indexes` for drops |
| "Something is stuck right now" | `active_queries` |

## Workflow for a slow query

1. **Get the real SQL.** If the user describes the query in words, ask for the SQL, or find it with `slow_queries`. If the query has bind parameters (`$1`), replace them with realistic literal values before explaining it. Plans depend on the values.
2. **Run `explain_query`.** It executes the query inside a read-only transaction that is rolled back. For a query that may run for minutes, call it first with `analyzeQuery: false` to get the estimated plan without executing it.
3. **Read the result in this order:**
   - `totalMs`: is it actually slow? Compare it with what the user expects.
   - `hottestNodes`: where the time went. This is exclusive time per node, so it points at the culprit, not just the root of the plan.
   - `findings`: sorted HIGH, MEDIUM, LOW. Each has a `message` (what happened), a `suggestion` (the fix) and `evidence` (the numbers behind it).
4. **Explain in plain language.** Lead with the finding that matches the hottest node. Quote the evidence ("it read 999,999 rows to return 501"), not the rule name.
5. **Propose the fix as a suggestion,** following the rules below.
6. **Offer to verify.** After the user applies a fix, run `explain_query` again and compare `totalMs` and the plan. A fix isn't done until the numbers say so.

What each finding means, and when its standard fix is wrong, is in [references/findings.md](references/findings.md). Read it before recommending a fix for a finding you haven't explained before in this conversation.

## Rules for recommending changes

- **Never present DDL as already applied.** These tools are read-only and cannot change the database. Say "you could run", not "I have added".
- **Write index DDL with `CONCURRENTLY`** so it doesn't lock writes on a live table, and point out that it can't run inside a transaction block.
- **Say what the fix costs.** Every index slows writes and takes disk space. A higher `work_mem` applies per sort, per connection.
- **Recommend testing on a copy of production first** for anything on a large or busy table.
- **Don't recommend dropping an index on `unused_indexes` evidence alone.** The counters only cover the time since statistics were last reset, and an index may serve a monthly report, a replica, or a unique constraint. Ask the user first.
- **When the finding and the user's intuition disagree, show the evidence.** Don't drop a finding to agree with them.

## When a tool returns an error

| Error starts with | What it means | What to do |
|---|---|---|
| `Refused:` | The query writes, has several statements, or calls a side-effect function | Explain that the server only analyses single read queries. For an `UPDATE`/`DELETE`, offer to explain the equivalent `SELECT` with the same `WHERE` clause |
| `Query hit the statement timeout` | The query ran longer than `timeoutMs` (default 15 s) | Retry with `analyzeQuery: false`. Only raise `timeoutMs` if the user agrees to run the slow query for real |
| `pg_stat_statements is not installed` | `slow_queries` needs the extension | Tell the user it needs `shared_preload_libraries = 'pg_stat_statements'` and a restart, then `CREATE EXTENSION pg_stat_statements;`. Meanwhile, ask them for the slow SQL directly |
| `PostgreSQL error:` | Usually a typo, a missing table, or a permission | Read the message, fix the SQL, try once more. If it's a permission error, the server's role lacks `pg_monitor` or `pg_read_all_data` |

## Example

> **User:** The customer orders page got slow.
>
> **You:** call `slow_queries`. The top entry by total time is a customers-orders join. Call `explain_query` on it.
>
> **Result:** `totalMs` 35, hottest node `Seq Scan on orders o`, finding `join_scans_whole_table` (HIGH): read 999,999 rows of orders to produce 501.
>
> **You tell the user:** The join reads all ~1M rows of `orders` to find the 501 that belong to these customers, because `orders.customer_id` has no index. Postgres doesn't index foreign keys automatically. You could run:
> ```sql
> CREATE INDEX CONCURRENTLY ON orders (customer_id);
> ```
> It will take a little disk space and slightly slow inserts into `orders`. Once it's built, I can re-run the plan to confirm the improvement.
