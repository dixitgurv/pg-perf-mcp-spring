package io.github.dixitgurv.pgperf.plan.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.dixitgurv.pgperf.plan.FilterColumns;
import io.github.dixitgurv.pgperf.plan.Finding;
import io.github.dixitgurv.pgperf.plan.PlanNode;
import io.github.dixitgurv.pgperf.plan.PlanRule;
import io.github.dixitgurv.pgperf.plan.Severity;

/**
 * Actual rows far from the planner's estimate. Every downstream choice (join type, memory,
 * parallelism) was made for the wrong row count.
 */
public class RowMisestimateRule implements PlanRule {

    static final double FACTOR = 10;
    static final double MIN_ROWS = 1_000;

    @Override
    public void apply(PlanNode node, PlanNode parent, List<Finding> findings) {
        if (!node.has("Actual Rows")) {
            return;
        }
        double planned = Math.max(node.number("Plan Rows"), 1);
        double actual = node.number("Actual Rows");
        if (Math.max(planned, actual) < MIN_ROWS) {
            return;
        }
        double factor = Math.max(actual, 1) / planned;
        if (factor >= 1 / FACTOR && factor <= FACTOR) {
            return;
        }
        // The same estimate passed straight up (e.g. Bitmap Index Scan -> Bitmap Heap Scan): report it once, on the parent.
        if (parent != null && Objects.equals(parent.raw("Plan Rows"), node.raw("Plan Rows"))
                && Objects.equals(parent.raw("Actual Rows"), node.raw("Actual Rows"))) {
            return;
        }
        String rel = node.text("Relation Name") == null ? "the table" : node.text("Relation Name");
        List<String> cols = new ArrayList<>();
        for (String key : List.of("Filter", "Recheck Cond", "Index Cond")) {
            FilterColumns.in(node.text(key)).stream().filter(c -> !cols.contains(c)).forEach(cols::add);
        }
        String stats = cols.size() >= 2 && !rel.equals("the table")
                ? "CREATE STATISTICS ON " + String.join(", ", cols) + " FROM " + rel + "; then ANALYZE " + rel + ";"
                : "CREATE STATISTICS (dependencies, ndistinct) on the filtered columns.";
        findings.add(new Finding(
                "row_misestimate",
                Severity.MEDIUM,
                node.label(),
                String.format("Planner %sestimated rows by %.0fx (planned %s, actual %s).",
                        factor > 1 ? "under" : "over", Math.max(factor, 1 / factor),
                        Evidence.n(planned), Evidence.n(actual)),
                "First refresh statistics (ANALYZE " + rel + "). If the estimate stays off, the filtered columns "
                        + "are probably correlated and the planner is multiplying their selectivities. Try " + stats,
                Evidence.of("plan_rows", planned, "actual_rows", actual, "columns", cols)));
    }
}
