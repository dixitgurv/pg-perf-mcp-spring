package io.github.dixitgurv.pgperf.plan.rules;

import java.util.List;

import io.github.dixitgurv.pgperf.plan.Finding;
import io.github.dixitgurv.pgperf.plan.PlanNode;
import io.github.dixitgurv.pgperf.plan.PlanRule;
import io.github.dixitgurv.pgperf.plan.Severity;

/** Most blocks came from disk, so the timing reflects a cold cache. Judged once, at the root. */
public class ColdCacheReadsRule implements PlanRule {

    static final double MIN_BLOCKS_READ = 1_000;
    static final double READ_RATIO = 0.5;

    @Override
    public void apply(PlanNode node, PlanNode parent, List<Finding> findings) {
        if (parent != null) {
            return;
        }
        double read = node.number("Shared Read Blocks");
        double hit = node.number("Shared Hit Blocks");
        if (read < MIN_BLOCKS_READ || read / Math.max(read + hit, 1) < READ_RATIO) {
            return;
        }
        findings.add(new Finding(
                "cold_cache_reads",
                Severity.LOW,
                node.label(),
                Evidence.n(read) + " of " + Evidence.n(read + hit) + " shared blocks came from disk, not cache.",
                "Run it again to see warm-cache timing. If it stays cold, the working set may be larger than "
                        + "shared_buffers, or the query reads more than it needs.",
                Evidence.of("shared_read", read, "shared_hit", hit)));
    }
}
