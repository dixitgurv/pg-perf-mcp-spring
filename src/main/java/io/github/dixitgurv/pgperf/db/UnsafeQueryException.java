package io.github.dixitgurv.pgperf.db;

/** The query was refused before it reached the database. The message is written for the agent. */
public class UnsafeQueryException extends RuntimeException {

    public UnsafeQueryException(String message) {
        super(message);
    }
}
