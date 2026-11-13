package io.github.dixitgurv.pgperf.plan.rules;

import java.util.List;

import io.github.dixitgurv.pgperf.plan.FilterColumns;
import io.github.dixitgurv.pgperf.plan.Finding;
import io.github.dixitgurv.pgperf.plan.PlanNode;
import io.github.dixitgurv.pgperf.plan.PlanRule;
import io.github.dixitgurv.pgperf.plan.Severity;

/** A sequential scan that reads far more rows than it returns: the classic missing index. */
public class SeqScanFilterWasteRule implements PlanRule {

    static final double MIN_ROWS_REMOVED = 10_000;
    static final double WASTE_RATIO = 10;

    @Override
    public void apply(PlanNode node, PlanNode parent, List<Finding> findings) {
        if (!node.is("Seq Scan") || !node.has("Filter")) {
            return;
        }
        double removed = node.totalRowsRemoved();
        double kept = node.totalRows();
        if (removed < MIN_ROWS_REMOVED || removed < WASTE_RATIO * Math.max(kept, 1)) {
            return;
        }
        String rel = node.text("Relation Name");
        List<String> cols = FilterColumns.in(node.text("Filter"));
        String ddl = cols.isEmpty() ? "an index on the filtered columns."
                : "CREATE INDEX CONCURRENTLY ON " + rel + " (" + String.join(", ", cols) + ");";
        findings.add(new Finding(
                "seq_scan_filter_waste",
                removed > 100 * Math.max(kept, 1) ? Severity.HIGH : Severity.MEDIUM,
                node.label(),
                "Sequential scan read and discarded " + Evidence.n(removed) + " rows to return " + Evidence.n(kept) + ".",
                "Candidate fix: " + ddl + " Check selectivity first: an index only pays off if the filter "
                        + "keeps a small fraction of the table.",
                Evidence.of("filter", node.text("Filter"), "rows_removed", removed, "rows_returned", kept,
                        "columns", cols)));
    }
}
