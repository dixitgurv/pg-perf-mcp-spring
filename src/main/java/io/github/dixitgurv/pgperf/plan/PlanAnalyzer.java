package io.github.dixitgurv.pgperf.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.dixitgurv.pgperf.plan.rules.ColdCacheReadsRule;
import io.github.dixitgurv.pgperf.plan.rules.HashMultiBatchRule;
import io.github.dixitgurv.pgperf.plan.rules.IndexOnlyScanHeapFetchesRule;
import io.github.dixitgurv.pgperf.plan.rules.JoinScansWholeTableRule;
import io.github.dixitgurv.pgperf.plan.rules.NestedLoopRepeatedScanRule;
import io.github.dixitgurv.pgperf.plan.rules.RowMisestimateRule;
import io.github.dixitgurv.pgperf.plan.rules.SeqScanFilterWasteRule;
import io.github.dixitgurv.pgperf.plan.rules.SortSpilledToDiskRule;
import tools.jackson.databind.JsonNode;

/**
 * Turns {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} output into findings and a time breakdown.
 *
 * <p>Pure and deterministic: the same plan always gives the same findings. The decisions live
 * here, in code with unit tests. The LLM on the other side of MCP explains them; it doesn't make them.
 */
public class PlanAnalyzer {

    private final List<PlanRule> rules;

    public PlanAnalyzer() {
        this(List.of(
                new SeqScanFilterWasteRule(),
                new RowMisestimateRule(),
                new SortSpilledToDiskRule(),
                new HashMultiBatchRule(),
                new NestedLoopRepeatedScanRule(),
                new JoinScansWholeTableRule(),
                new IndexOnlyScanHeapFetchesRule(),
                new ColdCacheReadsRule()));
    }

    public PlanAnalyzer(List<PlanRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** Accepts the raw EXPLAIN output (a one-element array) or its first element. */
    public PlanReport analyze(JsonNode explain, int topN) {
        JsonNode doc = explain.isArray() ? explain.get(0) : explain;
        PlanNode root = new PlanNode(doc.get("Plan"), false);

        List<Finding> findings = new ArrayList<>();
        List<NodeCost> costs = new ArrayList<>();
        walk(root, null, findings, costs);

        double totalExclusive = costs.stream().mapToDouble(NodeCost::exclusiveMs).sum();
        double denominator = totalExclusive > 0 ? totalExclusive : 1;
        List<NodeCost> hottest = costs.stream()
                .filter(c -> c.exclusiveMs() > 0)
                .sorted(Comparator.comparingDouble(NodeCost::exclusiveMs).reversed())
                .limit(topN)
                .map(c -> new NodeCost(c.node(), round(c.exclusiveMs(), 3), round(c.exclusiveMs() / denominator, 3)))
                .toList();
        findings.sort(Comparator.comparing(Finding::severity));
        return new PlanReport(number(doc, "Execution Time"), number(doc, "Planning Time"), findings, hottest);
    }

    public PlanReport analyze(JsonNode explain) {
        return analyze(explain, 3);
    }

    private void walk(PlanNode node, PlanNode parent, List<Finding> findings, List<NodeCost> costs) {
        for (PlanRule rule : rules) {
            rule.apply(node, parent, findings);
        }
        List<PlanNode> children = node.children();
        double childrenMs = children.stream().mapToDouble(PlanNode::inclusiveMs).sum();
        costs.add(new NodeCost(node.label(), Math.max(node.inclusiveMs() - childrenMs, 0), 0));
        for (PlanNode child : children) {
            walk(child, node, findings, costs);
        }
    }

    private static Double number(JsonNode doc, String key) {
        JsonNode value = doc.get(key);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }
}
