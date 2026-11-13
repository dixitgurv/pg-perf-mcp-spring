package io.github.dixitgurv.pgperf.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM orders",
            "  with t as (select 1) select * from t",
            "(SELECT 1) UNION (SELECT 2)",
            "VALUES (1), (2)",
            "TABLE orders",
            "SELECT ';' AS semicolon_in_a_string",
            "SELECT 1 -- trailing comment; DROP TABLE orders",
            "SELECT $$ ; $$",
    })
    void allowsSingleReadQueries(String sql) {
        assertThat(QueryGuard.check(sql)).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM orders",
            "UPDATE orders SET status = 'x'",
            "INSERT INTO orders VALUES (1)",
            "DROP TABLE orders",
            "SET work_mem = '1GB'",
            "SELECT 1; DROP TABLE orders",
            "SELECT pg_terminate_backend(42)",
            "SELECT set_config('statement_timeout', '0', false)",
            "SELECT nextval('orders_id_seq')",
            "SELECT pg_sleep(60)",
            "",
    })
    void refusesEverythingElse(String sql) {
        assertThatThrownBy(() -> QueryGuard.check(sql)).isInstanceOf(UnsafeQueryException.class);
    }

    @Test
    void stripsTrailingSemicolons() {
        assertThat(QueryGuard.check("SELECT 1;; ")).isEqualTo("SELECT 1");
    }
}
