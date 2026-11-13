package io.github.dixitgurv.pgperf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.dixitgurv.pgperf.mcp.ExplainResult;
import io.github.dixitgurv.pgperf.mcp.PerfTools;
import io.github.dixitgurv.pgperf.mcp.ToolFailure;
import io.github.dixitgurv.pgperf.plan.Finding;

/**
 * Real PostgreSQL, real plans: each problem planted in demo/seed.sql must be found,
 * and every way of writing through the server must be refused.
 */
// The MCP transport is exercised by McpStdioIT; here a stdio server would sit on the test JVM's stdin.
@SpringBootTest(properties = "spring.ai.mcp.server.enabled=false")
class DiagnosticsIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::url);
        registry.add("spring.datasource.username", PostgresTestSupport::user);
        registry.add("spring.datasource.password", PostgresTestSupport::password);
    }

    @Autowired
    private PerfTools tools;

    private Map<String, Finding> findings(String sql) {
        ExplainResult result = tools.explainQuery(sql, true, 30_000);
        return result.findings().stream().collect(Collectors.toMap(Finding::rule, Function.identity(), (a, b) -> a));
    }

    @Test
    void findsPlantedSequentialScan() {
        assertThat(findings("SELECT id FROM orders WHERE status = 'disputed'").get("seq_scan_filter_waste").suggestion())
                .contains("orders (status)");
    }

    @Test
    void findsCorrelatedColumnMisestimate() {
        assertThat(findings("SELECT count(*) FROM customers WHERE country = 'C3' AND city = 'City3'"))
                .containsKey("row_misestimate");
    }

    @Test
    void findsSortSpill() {
        assertThat(findings("SELECT * FROM order_items ORDER BY price_cents DESC, sku"))
                .containsKey("sort_spilled_to_disk");
    }

    @Test
    void findsUnindexedForeignKeyJoin() {
        Finding f = findings("SELECT c.email, count(*) FROM customers c JOIN orders o ON o.customer_id = c.id "
                + "WHERE c.id BETWEEN 1 AND 50 GROUP BY c.email").get("join_scans_whole_table");
        assertThat(f.suggestion()).contains("orders (customer_id)");
    }

    @Test
    void estimatedPlanSkipsExecution() {
        ExplainResult result = tools.explainQuery("SELECT * FROM orders", false, null);
        assertThat(result.totalMs()).isNull();
        assertThat(result.note()).contains("Estimated plan only");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM orders",
            "SELECT 1; DROP TABLE orders",
            "SELECT pg_terminate_backend(1)",
            "WITH x AS (DELETE FROM orders RETURNING *) SELECT * FROM x",
    })
    void refusesWrites(String sql) {
        assertThatThrownBy(() -> tools.explainQuery(sql, true, null))
                .isInstanceOf(ToolFailure.class)
                .hasMessageStartingWith("Refused");
    }

    @Test
    void writeHiddenFromTheStaticCheckIsStoppedByPostgres() {
        // A CTE that writes starts with WITH, so it passes the guard; the read-only session stops it.
        assertThatThrownBy(() -> tools.explainQuery("WITH x AS (DELETE FROM orders RETURNING *) SELECT * FROM x",
                true, null)).hasMessage("Refused: this server is read-only and the query tries to write.");
    }

    @Test
    void semicolonInsideStringLiteralIsFine() {
        assertThat(tools.explainQuery("SELECT ';' AS x", false, null).plan()).isNotNull();
    }

    @Test
    void statementTimeoutIsReportedInWordsTheAgentCanActOn() {
        assertThatThrownBy(() -> tools.explainQuery("SELECT count(*) FROM order_items a, order_items b", true, 200))
                .hasMessageContaining("statement timeout");
    }

    @Test
    void findsUnusedAndRedundantIndexes() {
        Set<Object> unused = tools.unusedIndexes(50).stream().map(r -> r.get("index_name")).collect(Collectors.toSet());
        Set<Object> redundant = tools.redundantIndexes().stream().map(r -> r.get("redundant_index"))
                .collect(Collectors.toSet());

        assertThat(unused).contains("orders_total_idx");
        assertThat(redundant).contains("customers_country_idx");
    }

    @Test
    void slowQueriesReadsPgStatStatements() throws Exception {
        try (Connection c = PostgresTestSupport.adminConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT count(*) FROM orders WHERE total_cents > 100");
        }
        assertThat(tools.slowQueries(50, "total"))
                .anySatisfy(row -> assertThat((String) row.get("query")).contains("total_cents"));
    }

    @Test
    void unknownOrderIsRejectedWithTheValidChoices() {
        assertThatThrownBy(() -> tools.slowQueries(10, "fastest")).hasMessageContaining("total, mean, calls, io");
    }

    @Test
    void overviewAndTableHealthReturnData() {
        assertThat(tools.databaseOverview()).containsKey("cache_hit_pct");
        assertThat(tools.tableHealth(10)).extracting(r -> r.get("table_name")).contains("public.order_items");
    }
}
