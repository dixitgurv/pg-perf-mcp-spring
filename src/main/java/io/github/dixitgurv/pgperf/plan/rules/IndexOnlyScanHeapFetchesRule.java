package io.github.dixitgurv.pgperf.plan.rules;

import java.util.List;

import io.github.dixitgurv.pgperf.plan.Finding;
import io.github.dixitgurv.pgperf.plan.PlanNode;
import io.github.dixitgurv.pgperf.plan.PlanRule;
import io.github.dixitgurv.pgperf.plan.Severity;

/** An index-only scan that still visits the table, because the visibility map is stale. */
public class IndexOnlyScanHeapFetchesRule implements PlanRule {

    static final double MIN_ROWS = 1_000;
    static final double HEAP_FETCH_RATIO = 0.2;

    @Override
    public void apply(PlanNode node, PlanNode parent, List<Finding> findings) {
        if (!node.is("Index Only Scan")) {
            return;
        }
        double rows = node.totalRows();
        double fetches = node.number("Heap Fetches");
        if (rows < MIN_ROWS || fetches < HEAP_FETCH_RATIO * rows) {
            return;
        }
        String rel = node.text("Relation Name") == null ? "the table" : node.text("Relation Name");
        findings.add(new Finding(
                "index_only_scan_heap_fetches",
                Severity.LOW,
                node.label(),
                "Index-only scan still visited the heap " + Evidence.n(fetches) + " times for " + Evidence.n(rows) + " rows.",
                "The visibility map is stale. VACUUM " + rel + "; and check that autovacuum keeps up on it.",
                Evidence.of("heap_fetches", fetches, "rows", rows)));
    }
}
