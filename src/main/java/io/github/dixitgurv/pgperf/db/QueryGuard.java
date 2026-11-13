package io.github.dixitgurv.pgperf.db;

import java.util.regex.Pattern;

/**
 * A static check on SQL the agent asks us to EXPLAIN, before anything reaches Postgres.
 *
 * <p>This is the outermost of four safety layers, not the main one. A regex can't fully parse SQL,
 * so the real guarantees are inside Postgres: read-only transactions, a statement timeout and
 * a rollback after every call (see {@link ReadOnlyDatabase}). The guard catches the obvious cases
 * early and gives the agent a clear reason.
 */
public final class QueryGuard {

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern DOLLAR_QUOTED = Pattern.compile("\\$([a-zA-Z_]*)\\$.*?\\$\\1\\$", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    private static final Pattern ALLOWED_START = Pattern.compile(
            "^\\s*(\\(\\s*)*(select|with|values|table)\\b", Pattern.CASE_INSENSITIVE);

    /** Functions that change state even inside a read-only transaction. */
    private static final Pattern BLOCKED_FUNCTIONS = Pattern.compile(
            "\\b(pg_terminate_backend|pg_cancel_backend|pg_reload_conf|pg_rotate_logfile|set_config"
                    + "|pg_advisory_\\w*lock\\w*|dblink\\w*|lo_import|lo_export|pg_read_binary_file|pg_read_file"
                    + "|pg_ls_dir|pg_stat_reset\\w*|nextval|setval|txid_current|pg_sleep\\w*)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private QueryGuard() {
    }

    /**
     * @return the query without a trailing semicolon
     * @throws UnsafeQueryException if it is not a single read-only query
     */
    public static String check(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new UnsafeQueryException("Query is empty.");
        }
        String cleaned = stripLiteralsAndComments(sql).strip();
        cleaned = stripTrailingSemicolons(cleaned);
        if (cleaned.contains(";")) {
            throw new UnsafeQueryException("Only a single statement is allowed.");
        }
        if (!ALLOWED_START.matcher(cleaned).find()) {
            throw new UnsafeQueryException("Only SELECT / WITH / VALUES / TABLE queries can be explained.");
        }
        if (BLOCKED_FUNCTIONS.matcher(cleaned).find()) {
            throw new UnsafeQueryException("Query calls a function with side effects; refusing to run it.");
        }
        return stripTrailingSemicolons(sql.strip());
    }

    /** Comments and string contents can hide a ';' or a keyword, so they are blanked before checking. */
    static String stripLiteralsAndComments(String sql) {
        String s = LINE_COMMENT.matcher(sql).replaceAll(" ");
        s = BLOCK_COMMENT.matcher(s).replaceAll(" ");
        s = DOLLAR_QUOTED.matcher(s).replaceAll("''");
        return STRING_LITERAL.matcher(s).replaceAll("''");
    }

    private static String stripTrailingSemicolons(String s) {
        String out = s;
        while (out.endsWith(";")) {
            out = out.substring(0, out.length() - 1).strip();
        }
        return out;
    }
}
