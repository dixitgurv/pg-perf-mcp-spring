# Findings: what they mean and when the usual fix is wrong

Each finding from `explain_query` has a stable `rule` id. Use this to explain it and to check whether its suggested fix fits the user's case.

## seq_scan_filter_waste

**What happened:** a sequential scan read the whole table and threw away most rows with a filter.

**Usual fix:** an index on the filtered columns (the suggestion writes out the DDL).

**When it's wrong:**
- The filter keeps a large share of the table (roughly over 10–20%). Postgres will still prefer a sequential scan, so the index won't be used.
- The column has very few distinct values and the query asks for the common one. A **partial index** on the rare values is better: `CREATE INDEX CONCURRENTLY ON orders (status) WHERE status <> 'shipped';`
- The filter wraps the column in a function (`lower(email) = ...`). A plain index won't help; it needs an expression index on the same function.

## join_scans_whole_table

**What happened:** a hash or merge join read a whole large table to keep under 1% of its rows.

**Usual fix:** an index on that table's join column. It's almost always a foreign key, which Postgres does not index automatically.

**When it's wrong:** when the query really does need most of the table (a report over all customers). Then a hash join over a full scan is the right plan.

## row_misestimate

**What happened:** the planner expected far fewer (or more) rows than it got, so its later choices were made for the wrong size.

**Usual fix:**
1. `ANALYZE <table>;` in case the statistics are stale.
2. If it stays wrong and the query filters on several related columns (country and city, make and model), the planner is multiplying their selectivities as if they were independent. Use `CREATE STATISTICS ON col1, col2 FROM <table>;` then `ANALYZE`.

**When it's wrong:** when the misestimate is on a node that takes no meaningful time. Fix it only if it led to a bad plan choice above it: a nested loop over many rows, or a hash that spilled.

## sort_spilled_to_disk

**What happened:** a sort didn't fit in `work_mem` and used temp files.

**Usual fix:** the suggestion gives a sized `work_mem`. Test it for one session (`SET work_mem = '...'`), never globally first.

**When it's wrong:**
- `work_mem` is per sort and per connection, so a large value multiplied across many connections can exhaust memory.
- A bigger `work_mem` often isn't faster for very large sorts. Measure before and after.
- The better fix is usually not sorting so much: an index that matches the `ORDER BY` (it returns rows already sorted), or a `LIMIT` when the caller only needs the first page.

## hash_multi_batch

**What happened:** a hash join's table didn't fit in memory and was split into batches on disk.

**Usual fix:** raise `work_mem` or `hash_mem_multiplier` for the query, or filter the smaller side earlier. The same per-connection memory caution as for sorts applies.

## nested_loop_repeated_scan

**What happened:** the inner side of a nested loop was a full scan, repeated once per outer row.

**Usual fix:** an index on the inner table's join column, so each loop becomes a lookup.

**Also check:** a row misestimate on the outer side. The planner may have chosen a nested loop because it expected a handful of outer rows.

## index_only_scan_heap_fetches

**What happened:** an index-only scan still had to visit the table for many rows, because the visibility map is out of date.

**Usual fix:** `VACUUM <table>;`, then check that autovacuum keeps up (`table_health` shows the last vacuum and dead tuples).

## cold_cache_reads

**What happened:** most data came from disk, not shared buffers.

**Usual fix:** none yet. Run the query again: if the second run is fast, the first was just a cold cache. If it stays cold, the working set may be bigger than `shared_buffers`, or the query reads more than it needs. Look at the other findings first.
