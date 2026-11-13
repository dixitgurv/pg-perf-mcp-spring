package io.github.dixitgurv.pgperf.plan.rules;

import java.util.List;

import io.github.dixitgurv.pgperf.plan.Finding;
import io.github.dixitgurv.pgperf.plan.PlanNode;
import io.github.dixitgurv.pgperf.plan.PlanRule;
import io.github.dixitgurv.pgperf.plan.Severity;
import tools.jackson.databind.JsonNode;

/** A sort that ran out of work_mem and spilled to temp files. */
public class SortSpilledToDiskRule implements PlanRule {

    /** Measured: a sort that spills 73 MB needs about 205 MB to run in memory. */
    static final int IN_MEMORY_FACTOR = 3;
    static final long MAX_SAFE_WORK_MEM_MB = 256;

    @Override
    public void apply(PlanNode node, PlanNode parent, List<Finding> findings) {
        if (!"Disk".equals(node.text("Sort Space Type"))) {
            return;
        }
        // In a parallel plan each worker spills its own share; the leader's figure alone understates it.
        long kb = (long) node.number("Sort Space Used");
        JsonNode workers = node.raw("Workers");
        if (workers != null && workers.isArray()) {
            for (JsonNode w : workers.values()) {
                if ("Disk".equals(w.path("Sort Space Type").asString(null))) {
                    kb += w.path("Sort Space Used").asLong(0);
                }
            }
        }
        long needMb = Math.max(4, kb * IN_MEMORY_FACTOR / 1024);
        String fix = needMb <= MAX_SAFE_WORK_MEM_MB
                ? "An in-memory sort needs roughly " + IN_MEMORY_FACTOR + "x the spilled size, so try SET work_mem = '"
                        + needMb + "MB' for this session and re-run EXPLAIN to confirm. "
                : "Keeping it in memory would need about " + Evidence.n(needMb)
                        + " MB of work_mem per sort, which is rarely safe. ";
        String method = node.text("Sort Method") == null ? "external" : node.text("Sort Method");
        findings.add(new Finding(
                "sort_spilled_to_disk",
                Severity.MEDIUM,
                node.label(),
                "Sort spilled " + Evidence.n(kb) + " kB to disk (" + method + ").",
                fix + "The bigger win is usually not sorting this much: an index that matches the ORDER BY, "
                        + "or a LIMIT if the caller only needs the first rows.",
                Evidence.of("sort_key", node.raw("Sort Key"), "space_kb", kb)));
    }
}
