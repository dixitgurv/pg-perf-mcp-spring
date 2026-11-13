package io.github.dixitgurv.pgperf.plan;

import java.util.Map;

/**
 * One problem found in a plan, with the fix a DBA would try first.
 *
 * @param rule       stable id of the rule that fired, e.g. {@code seq_scan_filter_waste}
 * @param node       the plan node it is about, e.g. "Seq Scan on orders"
 * @param evidence   the numbers the rule decided on, so the agent can quote them
 */
public record Finding(String rule, Severity severity, String node, String message, String suggestion,
                      Map<String, Object> evidence) {
}
