package io.github.dixitgurv.pgperf.plan;

/**
 * Time spent in one plan node itself, excluding its children.
 *
 * @param share fraction of the whole plan's exclusive time, 0..1
 */
public record NodeCost(String node, double exclusiveMs, double share) {
}
