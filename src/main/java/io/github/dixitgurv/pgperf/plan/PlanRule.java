package io.github.dixitgurv.pgperf.plan;

import java.util.List;

/**
 * A single check a DBA would make while reading a plan.
 *
 * <p>Rules are pure: they look at one node (and its parent, for context) and add findings.
 * No database, no Spring, no LLM, so each one is unit-tested against a plan fixture.
 */
public interface PlanRule {

    /**
     * @param parent null for the root node
     */
    void apply(PlanNode node, PlanNode parent, List<Finding> findings);
}
