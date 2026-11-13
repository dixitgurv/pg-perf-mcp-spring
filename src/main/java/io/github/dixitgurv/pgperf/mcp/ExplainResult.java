package io.github.dixitgurv.pgperf.mcp;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.dixitgurv.pgperf.plan.Finding;
import io.github.dixitgurv.pgperf.plan.NodeCost;
import tools.jackson.databind.JsonNode;

/**
 * What explain_query returns: the diagnosis first, the raw plan last for anything the rules don't cover.
 * Null fields (timings of an estimated plan, an absent note) are left out rather than sent as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExplainResult(Double totalMs, Double planningMs, List<Finding> findings, List<NodeCost> hottestNodes,
                            String note, JsonNode plan) {
}
