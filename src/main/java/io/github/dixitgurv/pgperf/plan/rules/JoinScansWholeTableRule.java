package io.github.dixitgurv.pgperf.plan.rules;

import java.util.List;
import java.util.Set;

import io.github.dixitgurv.pgperf.plan.FilterColumns;
import io.github.dixitgurv.pgperf.plan.Finding;
import io.github.dixitgurv.pgperf.plan.PlanNode;
import io.github.dixitgurv.pgperf.plan.PlanRule;
import io.github.dixitgurv.pgperf.plan.Severity;

/**
 * A hash or merge join that reads a whole large table to keep a handful of its rows.
 * Usually an unindexed foreign key: Postgres does not index those automatically.
 */
public class JoinScansWholeTableRule implements PlanRule {

    static final double MIN_ROWS_SCANNED = 100_000;
    static final double MAX_KEEP_RATIO = 0.01;
    private static final Set<String> WRAPPERS = Set.of("Hash", "Gather", "Materialize");

    @Override
    public void apply(PlanNode node, PlanNode parent, List<Finding> findings) {
        if (!node.is("Hash Join") && !node.is("Merge Join")) {
            return;
        }
        String cond = node.text("Hash Cond") != null ? node.text("Hash Cond") : node.text("Merge Cond");
        double joined = node.totalRows();
        for (PlanNode child : node.children()) {
            PlanNode scan = seqScanUnder(child);
            if (scan == null) {
                continue;
            }
            double scanned = scan.totalRows() + scan.totalRowsRemoved();
            if (scanned < MIN_ROWS_SCANNED || joined > MAX_KEEP_RATIO * scanned) {
                continue;
            }
            String rel = scan.text("Relation Name");
            String alias = scan.text("Alias") != null ? scan.text("Alias") : rel;
            List<String> cols = FilterColumns.qualifiedBy(alias, cond);
            String ddl = cols.isEmpty() ? "an index on the join column of " + rel + "."
                    : "CREATE INDEX CONCURRENTLY ON " + rel + " (" + String.join(", ", cols) + ");";
            findings.add(new Finding(
                    "join_scans_whole_table",
                    Severity.HIGH,
                    scan.label(),
                    "Read all " + Evidence.n(scanned) + " rows of " + rel + " to produce " + Evidence.n(joined) + " joined rows.",
                    "The join key on " + rel + " has no usable index, so the planner hashes the whole table. "
                            + "Candidate fix: " + ddl + " With it, Postgres can look up matching rows directly. "
                            + "Foreign key columns are the usual culprit: Postgres does not index them automatically.",
                    Evidence.of("join_condition", cond, "rows_scanned", scanned, "rows_joined", joined, "columns", cols)));
        }
    }

    /** The Seq Scan feeding a join input, looking through Hash and parallel wrappers. */
    private static PlanNode seqScanUnder(PlanNode node) {
        PlanNode current = node;
        while (WRAPPERS.contains(current.type()) && !current.children().isEmpty()) {
            current = current.children().get(0);
        }
        return current.is("Seq Scan") ? current : null;
    }
}
