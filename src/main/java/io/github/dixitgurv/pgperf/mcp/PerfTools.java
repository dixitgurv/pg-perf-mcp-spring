package io.github.dixitgurv.pgperf.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import io.github.dixitgurv.pgperf.db.CatalogQueries;
import io.github.dixitgurv.pgperf.db.CatalogQueries.SlowQueryOrder;
import io.github.dixitgurv.pgperf.db.ReadOnlyDatabase;
import io.github.dixitgurv.pgperf.plan.PlanAnalyzer;
import io.github.dixitgurv.pgperf.plan.PlanReport;
import tools.jackson.databind.JsonNode;

/**
 * The MCP tools. Each method is thin: validate input, call the database layer, return data.
 * Every tool is marked read-only so MCP clients can auto-approve it.
 */
@Component
public class PerfTools {

    private final ReadOnlyDatabase db;
    private final CatalogQueries catalog;
    private final PlanAnalyzer analyzer = new PlanAnalyzer();

    public PerfTools(ReadOnlyDatabase db, CatalogQueries catalog) {
        this.db = db;
        this.catalog = catalog;
    }

    @McpTool(name = "explain_query", annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false),
            description = """
                    Run EXPLAIN (ANALYZE, BUFFERS) on one SELECT query and diagnose the plan. The query runs in a \
                    read-only transaction that is rolled back. Returns total time, the plan nodes where time was \
                    actually spent, and findings such as wasteful sequential scans, row misestimates, sorts \
                    spilling to disk and repeated nested-loop scans, each with a suggested fix.""")
    public ExplainResult explainQuery(
            @McpToolParam(description = "A single SELECT / WITH query.") String sql,
            @McpToolParam(description = "false shows the estimated plan without executing the query. Default true.",
                    required = false) Boolean analyzeQuery,
            @McpToolParam(description = "Abort the query after this many milliseconds. Default 15000.",
                    required = false) Integer timeoutMs) {
        boolean analyze = analyzeQuery == null || analyzeQuery;
        int timeout = timeoutMs == null ? ReadOnlyDatabase.DEFAULT_TIMEOUT_MS : timeoutMs;
        return ToolFailure.translate(() -> {
            JsonNode plan = db.explain(sql, analyze, timeout);
            PlanReport report = analyzer.analyze(plan);
            String note = analyze ? null
                    : "Estimated plan only; timing and misestimate rules need analyzeQuery=true.";
            return new ExplainResult(report.totalMs(), report.planningMs(), report.findings(),
                    report.hottestNodes(), note, plan.get(0).get("Plan"));
        });
    }

    @McpTool(name = "slow_queries", annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false),
            description = "Top queries from pg_stat_statements by total time, mean time, call count or disk reads.")
    public List<Map<String, Object>> slowQueries(
            @McpToolParam(description = "How many queries, 1-50. Default 10.", required = false) Integer limit,
            @McpToolParam(description = "One of: total, mean, calls, io. Default total.", required = false)
            String orderBy) {
        return ToolFailure.translate(() -> catalog.slowQueries(clamp(limit, 10, 50), order(orderBy)));
    }

    @McpTool(name = "table_health", annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false),
            description = "Largest tables with seq-vs-index scan ratio, dead tuple share and last vacuum/analyze.")
    public List<Map<String, Object>> tableHealth(
            @McpToolParam(description = "How many tables, 1-100. Default 20.", required = false) Integer limit) {
        return ToolFailure.translate(() -> catalog.tableHealth(clamp(limit, 20, 100)));
    }

    @McpTool(name = "unused_indexes", annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false),
            description = "Non-unique indexes never used since statistics were last reset, largest first.")
    public List<Map<String, Object>> unusedIndexes(
            @McpToolParam(description = "How many indexes, 1-100. Default 20.", required = false) Integer limit) {
        return ToolFailure.translate(() -> catalog.unusedIndexes(clamp(limit, 20, 100)));
    }

    @McpTool(name = "redundant_indexes", annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false),
            description = "Indexes whose columns are a leading prefix of another index on the same table.")
    public List<Map<String, Object>> redundantIndexes() {
        return ToolFailure.translate(catalog::redundantIndexes);
    }

    @McpTool(name = "active_queries", annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false),
            description = "Currently running queries with run time, transaction age and wait events.")
    public List<Map<String, Object>> activeQueries(
            @McpToolParam(description = "How many queries, 1-100. Default 20.", required = false) Integer limit) {
        return ToolFailure.translate(() -> catalog.activeQueries(clamp(limit, 20, 100)));
    }

    @McpTool(name = "database_overview", annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false),
            description = "Cache hit rate, temp file usage, deadlocks, key memory settings and connection count.")
    public Map<String, Object> databaseOverview() {
        return ToolFailure.translate(catalog::overview);
    }

    static int clamp(Integer value, int defaultValue, int max) {
        return value == null ? defaultValue : Math.min(Math.max(value, 1), max);
    }

    static SlowQueryOrder order(String value) {
        if (value == null || value.isBlank()) {
            return SlowQueryOrder.TOTAL;
        }
        try {
            return SlowQueryOrder.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ToolFailure("orderBy must be one of: total, mean, calls, io.", e);
        }
    }
}
