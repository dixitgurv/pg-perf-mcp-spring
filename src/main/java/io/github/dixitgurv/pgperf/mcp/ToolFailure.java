package io.github.dixitgurv.pgperf.mcp;

import java.sql.SQLException;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;

import io.github.dixitgurv.pgperf.db.UnsafeQueryException;

/**
 * An expected failure, reported to the agent in words it can act on instead of a stack trace.
 * The MCP layer turns a thrown exception into a tool error result carrying this message.
 */
public class ToolFailure extends RuntimeException {

    static final String READ_ONLY = "25006";   // read_only_sql_transaction
    static final String CANCELED = "57014";    // query_canceled (statement_timeout)

    public ToolFailure(String message, Throwable cause) {
        super(message, cause);
    }

    /** Runs a tool body and translates the failures an agent can recover from. */
    public static <T> T translate(Supplier<T> body) {
        try {
            return body.get();
        } catch (UnsafeQueryException e) {
            throw new ToolFailure("Refused: " + e.getMessage(), e);
        } catch (DataAccessException e) {
            SQLException sql = findSqlException(e);
            String state = sql == null ? null : sql.getSQLState();
            if (READ_ONLY.equals(state)) {
                throw new ToolFailure("Refused: this server is read-only and the query tries to write.", e);
            }
            if (CANCELED.equals(state)) {
                throw new ToolFailure("Query hit the statement timeout. Try analyzeQuery=false for the estimated "
                        + "plan, or a larger timeoutMs.", e);
            }
            String detail = sql != null ? sql.getMessage() : e.getMostSpecificCause().getMessage();
            throw new ToolFailure("PostgreSQL error: " + detail.strip(), e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ToolFailure(e.getMessage(), e);
        }
    }

    private static SQLException findSqlException(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }
}
