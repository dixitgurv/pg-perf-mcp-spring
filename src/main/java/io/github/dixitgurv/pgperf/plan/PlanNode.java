package io.github.dixitgurv.pgperf.plan;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Typed, null-safe access to one node of {@code EXPLAIN (FORMAT JSON)} output.
 *
 * <p>Postgres reports {@code Actual Rows} and {@code Actual Total Time} as per-loop averages.
 * {@link #totalRows()} and {@link #inclusiveMs()} turn those into totals, which is where most
 * hand-written plan readers go wrong.
 */
public final class PlanNode {

    private final JsonNode json;
    private final boolean parallel;

    /**
     * @param parallel true when this node runs inside parallel workers (below a Gather)
     */
    public PlanNode(JsonNode json, boolean parallel) {
        this.json = json;
        this.parallel = parallel;
    }

    public String type() {
        return text("Node Type");
    }

    public boolean is(String nodeType) {
        return nodeType.equals(type());
    }

    public String text(String key) {
        JsonNode value = json.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    public double number(String key) {
        JsonNode value = json.get(key);
        return value == null || value.isNull() ? 0 : value.asDouble();
    }

    public boolean has(String key) {
        return json.has(key);
    }

    public JsonNode raw(String key) {
        return json.get(key);
    }

    public double loops() {
        return Math.max(number("Actual Loops"), 1);
    }

    /** Actual rows over all loops (EXPLAIN reports a per-loop average). */
    public double totalRows() {
        return number("Actual Rows") * loops();
    }

    /** Rows removed by the filter over all loops. */
    public double totalRowsRemoved() {
        return number("Rows Removed by Filter") * loops();
    }

    public boolean isGather() {
        return is("Gather") || is("Gather Merge");
    }

    public boolean parallel() {
        return parallel;
    }

    /**
     * Wall-clock time spent in this node and its children.
     *
     * <p>For an ordinary node, Actual Total Time is per loop, so multiply by loops. Under a Gather,
     * the "loops" are workers running at the same time; their times overlap, so the per-worker
     * figure is already the wall-clock time. Multiplying would make parallel scans look
     * several times slower than they are.
     */
    public double inclusiveMs() {
        double perLoop = number("Actual Total Time");
        return parallel ? perLoop : perLoop * loops();
    }

    public List<PlanNode> children() {
        List<PlanNode> out = new ArrayList<>();
        JsonNode plans = json.get("Plans");
        if (plans != null && plans.isArray()) {
            boolean childParallel = parallel || isGather();
            plans.values().forEach(child -> out.add(new PlanNode(child, childParallel)));
        }
        return out;
    }

    /** Human-readable name, e.g. "Index Scan using orders_pkey on orders". */
    public String label() {
        String kind = type() == null ? "?" : type();
        String rel = text("Relation Name");
        String idx = text("Index Name");
        String alias = text("Alias");
        if (rel != null && idx != null) {
            return kind + " using " + idx + " on " + rel;
        }
        if (idx != null) {
            return kind + " on " + idx;
        }
        if (rel != null) {
            return kind + " on " + rel + (alias != null && !alias.equals(rel) ? " " + alias : "");
        }
        return kind;
    }
}
