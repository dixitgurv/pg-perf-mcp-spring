package io.github.dixitgurv.pgperf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A PostgreSQL seeded with demo/seed.sql, shared by every integration test.
 *
 * <p>Uses {@code PG_PERF_TEST_JDBC_URL} if set (any Postgres with pg_stat_statements preloaded,
 * user {@code postgres}, no password). Otherwise starts one with Testcontainers, which is what CI does.
 * Seeding uses its own writable connection: the application's own connections are read-only.
 */
public final class PostgresTestSupport {

    private static String url;
    private static String user;
    private static String password;

    private PostgresTestSupport() {
    }

    public static synchronized String url() {
        if (url == null) {
            start();
        }
        return url;
    }

    public static String user() {
        url();
        return user;
    }

    public static String password() {
        url();
        return password;
    }

    /** A writable connection, for test setup only. */
    public static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(url(), user(), password());
    }

    private static void start() {
        String external = System.getenv("PG_PERF_TEST_JDBC_URL");
        if (external != null && !external.isBlank()) {
            url = external;
            user = "postgres";
            password = "";
        } else {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is not available and PG_PERF_TEST_JDBC_URL is not set");
            PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17-alpine")
                    .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");
            pg.start();
            url = pg.getJdbcUrl();
            user = pg.getUsername();
            password = pg.getPassword();
        }
        seed();
    }

    private static void seed() {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            s.execute(Files.readString(Path.of("demo/seed.sql")));
            s.execute("SELECT pg_stat_statements_reset()");
        } catch (Exception e) {
            throw new IllegalStateException("Could not seed the test database", e);
        }
    }
}
