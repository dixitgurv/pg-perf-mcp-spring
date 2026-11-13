package io.github.dixitgurv.pgperf.plan;

import java.util.List;

/** What the analyzer concluded about one plan. Times are null for a plan that was not executed. */
public record PlanReport(Double totalMs, Double planningMs, List<Finding> findings, List<NodeCost> hottestNodes) {
}
