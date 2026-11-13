package io.github.dixitgurv.pgperf.db;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every database call goes through here, inside a read-only, time-boxed transaction that is
 * always rolled back.
 *
 * <p>Safety is layered, because the caller is an LLM agent:
 * <ol>
 *   <li>Connections start with {@code default_transaction_read_only = on} (application.yml), so
 *       writes fail inside Postgres even if everything else here were bypassed.</li>
 *   <li>Each call runs in a read-only Spring transaction, and is rolled back: EXPLAIN ANALYZE
 *       really executes the query, and nothing it does may persist.</li>
 *   <li>{@code SET LOCAL statement_timeout} caps every statement.</li>
 *   <li>{@link QueryGuard} rejects multi-statement input and side-effect functions up front.</li>
 * </ol>
 * Point it at a role with only {@code pg_monitor} and {@code pg_read_all_data} for a fifth.
 */
@Component
public class ReadOnlyDatabase {

    public static final int DEFAULT_TIMEOUT_MS = 15_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public ReadOnlyDatabase(JdbcTemplate jdbc, PlatformTransactionManager txManager, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setReadOnly(true);
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        return inReadOnlyTransaction(DEFAULT_TIMEOUT_MS, jdbc -> jdbc.queryForList(sql, args));
    }

    /**
     * Runs {@code EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT JSON)} on one checked query.
     *
     * @param analyze false returns the estimated plan without executing the query
     */
    public JsonNode explain(String sql, boolean analyze, int timeoutMs) {
        String query = QueryGuard.check(sql);
        String options = analyze ? "ANALYZE, BUFFERS, VERBOSE, FORMAT JSON" : "VERBOSE, FORMAT JSON";
        String plan = inReadOnlyTransaction(timeoutMs,
                jdbc -> jdbc.queryForObject("EXPLAIN (" + options + ") " + query, String.class));
        return json.readTree(plan);
    }

    public boolean hasExtension(String name) {
        return !query("SELECT 1 FROM pg_extension WHERE extname = ?", name).isEmpty();
    }

    private <T> T inReadOnlyTransaction(int timeoutMs, Function<JdbcTemplate, T> work) {
        int timeout = Math.max(1, timeoutMs);
        return tx.execute(status -> {
            status.setRollbackOnly();
            // SET can't take a bind parameter; timeout is an int, so this can't inject SQL.
            jdbc.execute("SET LOCAL statement_timeout = " + timeout);
            return work.apply(jdbc);
        });
    }
}
