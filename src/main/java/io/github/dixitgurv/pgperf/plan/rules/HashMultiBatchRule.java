package io.github.dixitgurv.pgperf.plan.rules;

import java.util.List;

import io.github.dixitgurv.pgperf.plan.Finding;
import io.github.dixitgurv.pgperf.plan.PlanNode;
import io.github.dixitgurv.pgperf.plan.PlanRule;
import io.github.dixitgurv.pgperf.plan.Severity;

/** A hash table too big for memory, split into batches written to disk. */
public class HashMultiBatchRule implements PlanRule {

    @Override
    public void apply(PlanNode node, PlanNode parent, List<Finding> findings) {
        if (!node.is("Hash") || node.number("Hash Batches") <= 1) {
            return;
        }
        long batches = (long) node.number("Hash Batches");
        findings.add(new Finding(
                "hash_multi_batch",
                Severity.MEDIUM,
                node.label(),
                "Hash table did not fit in memory and was split into " + batches + " batches.",
                "Raise work_mem (or hash_mem_multiplier) for this query, or reduce the build side with a tighter filter.",
                Evidence.of("batches", batches, "peak_memory_kb", node.raw("Peak Memory Usage"))));
    }
}
