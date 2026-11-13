package io.github.dixitgurv.pgperf.plan.rules;

import java.util.List;
import java.util.Set;

import io.github.dixitgurv.pgperf.plan.Finding;
import io.github.dixitgurv.pgperf.plan.PlanNode;
import io.github.dixitgurv.pgperf.plan.PlanRule;
import io.github.dixitgurv.pgperf.plan.Severity;

/** A nested loop whose inner side is a full scan, repeated once per outer row. */
public class NestedLoopRepeatedScanRule implements PlanRule {

    static final double MIN_INNER_LOOPS = 1_000;
    private static final Set<String> SCANS = Set.of("Seq Scan", "Bitmap Heap Scan");

    @Override
    public void apply(PlanNode node, PlanNode parent, List<Finding> findings) {
        if (!node.is("Nested Loop")) {
            return;
        }
        List<PlanNode> children = node.children();
        if (children.size() < 2) {
            return;
        }
        PlanNode inner = children.get(1);
        double loops = inner.number("Actual Loops");
        if (loops < MIN_INNER_LOOPS || !SCANS.contains(inner.type())) {
            return;
        }
        findings.add(new Finding(
                "nested_loop_repeated_scan",
                Severity.HIGH,
                node.label(),
                "Inner side (" + inner.label() + ") ran " + Evidence.n(loops) + " times inside a nested loop.",
                "Index the join column on the inner table so each loop is an index lookup, or check whether "
                        + "a hash join was ruled out by a bad row estimate.",
                Evidence.of("inner", inner.label(), "loops", loops, "join_filter", node.text("Join Filter"))));
    }
}
