package io.github.dixitgurv.pgperf.db;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Statistics and catalog queries, each returning rows ready to hand to the agent. */
@Component
public class CatalogQueries {

    /** What slow_queries can order by, mapped to a pg_stat_statements column. Never user text in SQL. */
    public enum SlowQueryOrder {
        TOTAL("total_exec_time"), MEAN("mean_exec_time"), CALLS("calls"), IO("shared_blks_read");

        private final String column;

        SlowQueryOrder(String column) {
            this.column = column;
        }
    }

    private static final String SLOW_QUERIES = """
            SELECT queryid::text,
                   calls,
                   round(total_exec_time::numeric, 1) AS total_ms,
                   round(mean_exec_time::numeric, 2)  AS mean_ms,
                   round((100 * total_exec_time / nullif(sum(total_exec_time) OVER (), 0))::numeric, 1) AS pct_of_total,
                   rows,
                   shared_blks_hit,
                   shared_blks_read,
                   temp_blks_written,
                   left(regexp_replace(query, '\\s+', ' ', 'g'), 500) AS query
            FROM pg_stat_statements
            WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
            ORDER BY %s DESC
            LIMIT ?
            """;

    private static final String TABLE_HEALTH = """
            SELECT s.schemaname || '.' || s.relname              AS table_name,
                   pg_size_pretty(pg_total_relation_size(s.relid)) AS total_size,
                   c.reltuples::bigint                             AS est_rows,
                   s.seq_scan,
                   s.seq_tup_read,
                   coalesce(s.idx_scan, 0)                         AS idx_scan,
                   round(100.0 * s.seq_scan / nullif(s.seq_scan + coalesce(s.idx_scan, 0), 0), 1) AS seq_scan_pct,
                   s.n_live_tup,
                   s.n_dead_tup,
                   round(100.0 * s.n_dead_tup / nullif(s.n_live_tup + s.n_dead_tup, 0), 1) AS dead_pct,
                   greatest(s.last_vacuum, s.last_autovacuum)      AS last_vacuum,
                   greatest(s.last_analyze, s.last_autoanalyze)    AS last_analyze,
                   s.n_mod_since_analyze
            FROM pg_stat_user_tables s
            JOIN pg_class c ON c.oid = s.relid
            ORDER BY pg_total_relation_size(s.relid) DESC
            LIMIT ?
            """;

    private static final String UNUSED_INDEXES = """
            SELECT s.schemaname || '.' || s.relname          AS table_name,
                   s.indexrelname                              AS index_name,
                   pg_size_pretty(pg_relation_size(s.indexrelid)) AS index_size,
                   pg_relation_size(s.indexrelid)              AS index_bytes,
                   s.idx_scan,
                   pg_get_indexdef(s.indexrelid)               AS definition
            FROM pg_stat_user_indexes s
            JOIN pg_index i ON i.indexrelid = s.indexrelid
            WHERE s.idx_scan = 0
              AND NOT i.indisunique
              AND NOT i.indisprimary
              AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = s.indexrelid)
            ORDER BY pg_relation_size(s.indexrelid) DESC
            LIMIT ?
            """;

    /** Indexes whose key columns are a leading prefix of another index on the same table. */
    private static final String REDUNDANT_INDEXES = """
            WITH idx AS (
              SELECT i.indexrelid, i.indrelid, i.indkey::text AS keys, i.indisunique,
                     pg_get_expr(i.indpred, i.indrelid) AS pred, pg_get_expr(i.indexprs, i.indrelid) AS exprs,
                     am.amname
              FROM pg_index i
              JOIN pg_class c ON c.oid = i.indexrelid
              JOIN pg_am am ON am.oid = c.relam
              JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
            )
            SELECT a.indrelid::regclass::text                     AS table_name,
                   a.indexrelid::regclass::text                   AS redundant_index,
                   b.indexrelid::regclass::text                   AS covered_by,
                   pg_size_pretty(pg_relation_size(a.indexrelid)) AS redundant_size,
                   pg_get_indexdef(a.indexrelid)                  AS redundant_definition,
                   pg_get_indexdef(b.indexrelid)                  AS covering_definition
            FROM idx a
            JOIN idx b ON a.indrelid = b.indrelid AND a.indexrelid <> b.indexrelid
            WHERE a.amname = b.amname
              AND a.pred IS NOT DISTINCT FROM b.pred
              AND a.exprs IS NOT DISTINCT FROM b.exprs
              AND NOT a.indisunique
              AND (b.keys = a.keys AND a.indexrelid > b.indexrelid
                   OR b.keys LIKE a.keys || ' %')
            ORDER BY pg_relation_size(a.indexrelid) DESC
            """;

    private static final String ACTIVITY = """
            SELECT pid,
                   usename,
                   application_name,
                   state,
                   wait_event_type,
                   wait_event,
                   round(extract(epoch FROM now() - query_start)::numeric, 1) AS running_s,
                   round(extract(epoch FROM now() - xact_start)::numeric, 1)  AS xact_s,
                   left(regexp_replace(query, '\\s+', ' ', 'g'), 300)        AS query
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND pid <> pg_backend_pid()
              AND state IS DISTINCT FROM 'idle'
            ORDER BY query_start NULLS LAST
            LIMIT ?
            """;

    private static final String OVERVIEW = """
            SELECT round(100.0 * sum(blks_hit) / nullif(sum(blks_hit) + sum(blks_read), 0), 2) AS cache_hit_pct,
                   sum(temp_files)                         AS temp_files,
                   pg_size_pretty(sum(temp_bytes))         AS temp_bytes,
                   sum(deadlocks)                          AS deadlocks,
                   current_setting('shared_buffers')       AS shared_buffers,
                   current_setting('work_mem')             AS work_mem,
                   current_setting('effective_cache_size') AS effective_cache_size,
                   current_setting('random_page_cost')     AS random_page_cost,
                   current_setting('max_connections')      AS max_connections,
                   (SELECT count(*) FROM pg_stat_activity) AS connections_now,
                   version()                               AS version
            FROM pg_stat_database
            WHERE datname = current_database()
            """;

    private final ReadOnlyDatabase db;

    public CatalogQueries(ReadOnlyDatabase db) {
        this.db = db;
    }

    public List<Map<String, Object>> slowQueries(int limit, SlowQueryOrder order) {
        if (!db.hasExtension("pg_stat_statements")) {
            throw new IllegalStateException("pg_stat_statements is not installed in this database. Add it to "
                    + "shared_preload_libraries and run CREATE EXTENSION pg_stat_statements;");
        }
        return db.query(SLOW_QUERIES.formatted(order.column), limit);
    }

    public List<Map<String, Object>> tableHealth(int limit) {
        return db.query(TABLE_HEALTH, limit);
    }

    public List<Map<String, Object>> unusedIndexes(int limit) {
        return db.query(UNUSED_INDEXES, limit);
    }

    public List<Map<String, Object>> redundantIndexes() {
        return db.query(REDUNDANT_INDEXES);
    }

    public List<Map<String, Object>> activeQueries(int limit) {
        return db.query(ACTIVITY, limit);
    }

    public Map<String, Object> overview() {
        return db.query(OVERVIEW).get(0);
    }
}
