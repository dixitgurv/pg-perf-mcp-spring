package io.github.dixitgurv.pgperf;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * End to end, the way Claude Desktop or any MCP client uses it: launch the packaged jar as a
 * subprocess and speak JSON-RPC over its stdin/stdout. Catches what in-process tests can't,
 * such as a stray log line on stdout corrupting the protocol.
 */
class McpStdioIT {

    private static McpSyncClient client;

    @BeforeAll
    static void launchServer() {
        ServerParameters server = ServerParameters.builder("java")
                .args("-jar", System.getProperty("server.jar"))
                .addEnvVar("PG_PERF_JDBC_URL", PostgresTestSupport.url())
                .addEnvVar("PG_PERF_USER", PostgresTestSupport.user())
                .addEnvVar("PG_PERF_PASSWORD", PostgresTestSupport.password())
                .build();
        client = McpClient.sync(new StdioClientTransport(server, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(60))
                .build();
        client.initialize();
    }

    @AfterAll
    static void stopServer() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void listsEveryToolAsReadOnly() {
        Set<String> names = client.listTools().tools().stream().map(Tool::name).collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder("explain_query", "slow_queries", "table_health",
                "unused_indexes", "redundant_indexes", "active_queries", "database_overview");
        assertThat(client.listTools().tools()).allSatisfy(t -> assertThat(t.annotations().readOnlyHint()).isTrue());
    }

    @Test
    void explainQueryReturnsTheFinding() {
        CallToolResult result = client.callTool(new CallToolRequest("explain_query",
                Map.of("sql", "SELECT id FROM orders WHERE status = 'disputed'")));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("seq_scan_filter_waste").contains("CREATE INDEX CONCURRENTLY ON orders (status)");
    }

    @Test
    void refusalReachesTheAgentAsAReadableError() {
        CallToolResult result = client.callTool(new CallToolRequest("explain_query", Map.of("sql", "DELETE FROM orders")));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Refused");
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}
