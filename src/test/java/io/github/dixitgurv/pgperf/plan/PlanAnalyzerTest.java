package io.github.dixitgurv.pgperf.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Each rule against a plan fixture. Fixtures are trimmed copies of real EXPLAIN JSON output,
 * so no database is needed and the rules run in milliseconds.
 */
class PlanAnalyzerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final PlanAnalyzer analyzer = new PlanAnalyzer();

    private PlanReport analyze(String planJson) {
        return analyzer.analyze(JSON.readTree(
                "[{\"Plan\": " + planJson + ", \"Planning Time\": 0.2, \"Execution Time\": 100.0}]"));
    }

    private static Set<String> rules(PlanReport report) {
        return report.findings().stream().map(Finding::rule).collect(Collectors.toSet());
    }

    @Test
    void wastefulSeqScanSuggestsIndexOnFilterColumn() {
        PlanReport report = analyze("""
                {"Node Type": "Seq Scan", "Relation Name": "orders", "Alias": "orders",
                 "Filter": "(orders.status = 'disputed'::text)",
                 "Plan Rows": 5000, "Actual Rows": 5000, "Actual Loops": 1,
                 "Rows Removed by Filter": 995000, "Actual Total Time": 80.0}
                """);

        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("seq_scan_filter_waste");
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.suggestion()).contains("CREATE INDEX CONCURRENTLY ON orders (status)");
        });
    }

    @Test
    void selectiveSeqScanOnSmallTableIsNotFlagged() {
        PlanReport report = analyze("""
                {"Node Type": "Seq Scan", "Relation Name": "countries", "Filter": "(code = 'IN'::text)",
                 "Plan Rows": 1, "Actual Rows": 1, "Actual Loops": 1, "Rows Removed by Filter": 240,
                 "Actual Total Time": 0.05}
                """);

        assertThat(report.findings()).isEmpty();
    }

    @Test
    void rowMisestimatePointsToExtendedStatistics() {
        PlanReport report = analyze("""
                {"Node Type": "Seq Scan", "Relation Name": "customers",
                 "Plan Rows": 1000, "Actual Rows": 20000, "Actual Loops": 1, "Actual Total Time": 10.0}
                """);

        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("row_misestimate");
            assertThat(f.message()).contains("underestimated rows by 20x");
            assertThat(f.suggestion()).contains("CREATE STATISTICS");
        });
    }

    @Test
    void smallMisestimateIsIgnored() {
        PlanReport report = analyze("""
                {"Node Type": "Seq Scan", "Relation Name": "customers",
                 "Plan Rows": 4000, "Actual Rows": 20000, "Actual Loops": 1, "Actual Total Time": 10.0}
                """);

        assertThat(report.findings()).isEmpty();
    }

    @Test
    void misestimateComparesPerLoopRowsNotTotals() {
        // 50,000 loops of a 1-row index lookup is a correct estimate, not a 50,000x miss.
        PlanReport report = analyze("""
                {"Node Type": "Index Scan", "Relation Name": "orders", "Index Name": "orders_pkey",
                 "Plan Rows": 1, "Actual Rows": 1, "Actual Loops": 50000, "Actual Total Time": 0.01}
                """);

        assertThat(rules(report)).doesNotContain("row_misestimate");
    }

    @Test
    void sortSpillSuggestsSizedWorkMemAndHashBatchesAreFlagged() {
        PlanReport report = analyze("""
                {"Node Type": "Hash Join", "Actual Rows": 10, "Plan Rows": 10, "Actual Loops": 1, "Actual Total Time": 50,
                 "Plans": [
                   {"Node Type": "Sort", "Sort Key": ["price_cents DESC"], "Sort Method": "external merge",
                    "Sort Space Used": 61000, "Sort Space Type": "Disk", "Actual Rows": 10, "Plan Rows": 10,
                    "Actual Loops": 1, "Actual Total Time": 30},
                   {"Node Type": "Hash", "Hash Batches": 8, "Peak Memory Usage": 4096, "Actual Rows": 10,
                    "Plan Rows": 10, "Actual Loops": 1, "Actual Total Time": 5}]}
                """);

        assertThat(rules(report)).containsExactlyInAnyOrder("sort_spilled_to_disk", "hash_multi_batch");
        // 61,000 kB spilled x 3 = 178 MB needed in memory.
        assertThat(report.findings()).filteredOn(f -> f.rule().equals("sort_spilled_to_disk"))
                .singleElement().satisfies(f -> assertThat(f.suggestion()).contains("178MB"));
    }

    @Test
    void sortSpillCountsEveryParallelWorker() {
        PlanReport report = analyze("""
                {"Node Type": "Sort", "Sort Method": "external merge", "Sort Space Used": 20000,
                 "Sort Space Type": "Disk", "Actual Rows": 10, "Plan Rows": 10, "Actual Loops": 3,
                 "Actual Total Time": 30,
                 "Workers": [{"Sort Space Used": 25000, "Sort Space Type": "Disk"},
                             {"Sort Space Used": 28000, "Sort Space Type": "Disk"}]}
                """);

        assertThat(report.findings()).singleElement()
                .satisfies(f -> assertThat(f.evidence()).containsEntry("space_kb", 73000L));
    }

    @Test
    void nestedLoopWithRepeatedInnerSeqScan() {
        PlanReport report = analyze("""
                {"Node Type": "Nested Loop", "Actual Rows": 5, "Plan Rows": 5, "Actual Loops": 1, "Actual Total Time": 900,
                 "Plans": [
                   {"Node Type": "Seq Scan", "Relation Name": "customers", "Actual Rows": 2000, "Plan Rows": 2000,
                    "Actual Loops": 1, "Actual Total Time": 2},
                   {"Node Type": "Seq Scan", "Relation Name": "orders", "Actual Rows": 0, "Plan Rows": 1,
                    "Actual Loops": 2000, "Actual Total Time": 0.4}]}
                """);

        assertThat(rules(report)).contains("nested_loop_repeated_scan");
    }

    @Test
    void joinReadingWholeTableSuggestsForeignKeyIndex() {
        PlanReport report = analyze("""
                {"Node Type": "Hash Join", "Hash Cond": "(o.customer_id = c.id)",
                 "Actual Rows": 500, "Plan Rows": 500, "Actual Loops": 1, "Actual Total Time": 60,
                 "Plans": [
                   {"Node Type": "Seq Scan", "Relation Name": "orders", "Alias": "o", "Actual Rows": 1000000,
                    "Plan Rows": 1000000, "Actual Loops": 1, "Actual Total Time": 40},
                   {"Node Type": "Hash", "Actual Rows": 50, "Plan Rows": 50, "Actual Loops": 1, "Actual Total Time": 0.1,
                    "Plans": [{"Node Type": "Index Scan", "Relation Name": "customers", "Alias": "c",
                               "Index Name": "customers_pkey", "Actual Rows": 50, "Plan Rows": 50,
                               "Actual Loops": 1, "Actual Total Time": 0.1}]}]}
                """);

        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("join_scans_whole_table");
            assertThat(f.suggestion()).contains("CREATE INDEX CONCURRENTLY ON orders (customer_id)");
        });
    }

    @Test
    void misestimateIsReportedOnceWhenPassedUpUnchanged() {
        PlanReport report = analyze("""
                {"Node Type": "Bitmap Heap Scan", "Relation Name": "customers",
                 "Recheck Cond": "((country = 'C3'::text) AND (city = 'City3'::text))",
                 "Plan Rows": 260, "Actual Rows": 5000, "Actual Loops": 1, "Actual Total Time": 2,
                 "Plans": [{"Node Type": "Bitmap Index Scan", "Index Name": "customers_country_city_idx",
                            "Plan Rows": 260, "Actual Rows": 5000, "Actual Loops": 1, "Actual Total Time": 0.2}]}
                """);

        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.node()).isEqualTo("Bitmap Heap Scan on customers");
            assertThat(f.suggestion()).contains("CREATE STATISTICS ON country, city FROM customers");
        });
    }

    @Test
    void hottestNodesUseExclusiveTime() {
        PlanReport report = analyze("""
                {"Node Type": "Aggregate", "Actual Rows": 1, "Plan Rows": 1, "Actual Loops": 1, "Actual Total Time": 100,
                 "Plans": [{"Node Type": "Seq Scan", "Relation Name": "orders", "Actual Rows": 10, "Plan Rows": 10,
                            "Actual Loops": 1, "Actual Total Time": 90}]}
                """);

        List<NodeCost> hottest = report.hottestNodes();
        assertThat(hottest.get(0).node()).isEqualTo("Seq Scan on orders");
        assertThat(hottest.get(0).exclusiveMs()).isEqualTo(90.0);
        assertThat(hottest.get(1).exclusiveMs()).isEqualTo(10.0);
    }

    @Test
    void parallelWorkersAreNotDoubleCounted() {
        // Three workers each spent 33 ms at the same time: wall-clock cost is 33 ms, not 99.
        PlanReport report = analyze("""
                {"Node Type": "Gather", "Actual Rows": 5000, "Plan Rows": 5000, "Actual Loops": 1, "Actual Total Time": 36,
                 "Plans": [{"Node Type": "Seq Scan", "Relation Name": "orders", "Parallel Aware": true,
                            "Actual Rows": 1667, "Plan Rows": 1667, "Actual Loops": 3, "Actual Total Time": 33}]}
                """);

        assertThat(report.hottestNodes().get(0).exclusiveMs()).isEqualTo(33.0);
    }

    @Test
    void reportCarriesExecutionAndPlanningTime() {
        PlanReport report = analyze("""
                {"Node Type": "Result", "Actual Rows": 1, "Plan Rows": 1, "Actual Loops": 1, "Actual Total Time": 0.01}
                """);

        assertThat(report.totalMs()).isEqualTo(100.0);
        assertThat(report.planningMs()).isEqualTo(0.2);
    }

    @Test
    void filterColumnsHandlesCastsAndQualifiers() {
        assertThat(FilterColumns.in("((c.country = 'IN'::text) AND ((city)::text = 'Pune'::text))"))
                .containsExactly("country", "city");
        assertThat(FilterColumns.in("(created_at >= '2024-01-01'::date)")).containsExactly("created_at");
        assertThat(FilterColumns.in(null)).isEmpty();
    }
}
