package com.aieng.productmcpserver;

import static com.aieng.productmcpserver.StubResponses.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Layer 3: the server as a stdio child process against a JDK HttpServer stub (MCP-SERVER-T37..T41). */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StdioServerProcessTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private HttpServer stub;
    private final Map<String, String[]> routes = new ConcurrentHashMap<>();   // "GET /path" -> {status, body}
    private final List<String[]> requests = Collections.synchronizedList(new ArrayList<>()); // {method, path, auth}

    private Process process;
    private OutputStream stdin;
    private Thread reader;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final List<String> allLines = Collections.synchronizedList(new ArrayList<>());
    private int nextId = 1;

    @BeforeEach
    void start() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            requests.add(new String[] {exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization")});
            String[] route = routes.get(key);
            int status = route == null ? 500 : Integer.parseInt(route[0]);
            byte[] body = route == null ? new byte[0] : route[1].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    status >= 400 ? "application/problem+json" : "application/json");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        stub.start();

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Djava.io.tmpdir=" + tempDir,
                "-cp", System.getProperty("java.class.path"),
                "com.aieng.productmcpserver.ProductMcpServerApplication");
        pb.environment().put("PRODUCT_API_BASE_URL", "http://localhost:" + stub.getAddress().getPort() + "/");
        pb.environment().put("PRODUCT_API_USERNAME", "admin");
        pb.environment().put("PRODUCT_API_PASSWORD", "admin123");
        pb.redirectError(tempDir.resolve("stderr.txt").toFile());
        process = pb.start();
        stdin = process.getOutputStream();

        reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    allLines.add(line);
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // stream closed when the process ends
            }
        }, "stdio-reader");
        reader.setDaemon(true);
        reader.start();
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        stub.stop(0);
    }

    // ---- JSON-RPC helpers ----

    private void send(Map<String, Object> message) throws IOException {
        stdin.write((JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private JsonNode request(String method, Map<String, Object> params) throws Exception {
        int id = nextId++;
        send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        while (true) {
            String line = lines.poll(20, TimeUnit.SECONDS);
            if (line == null) {
                fail("No response to " + method + "; stderr: " + Files.readString(tempDir.resolve("stderr.txt")));
            }
            JsonNode node = JSON.readTree(line);
            if (node.has("id") && node.get("id").asInt() == id) {
                return node;
            }
        }
    }

    private JsonNode initialize() throws Exception {
        JsonNode response = request("initialize", Map.of(
                "protocolVersion", McpSchema.LATEST_PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1")));
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        return response;
    }

    private JsonNode callTool(String name, Map<String, Object> args) throws Exception {
        return request("tools/call", Map.of("name", name, "arguments", args));
    }

    private void assertAllLinesAreJsonRpc() throws IOException {
        synchronized (allLines) {
            for (String line : allLines) {
                JsonNode node;
                try {
                    node = JSON.readTree(line);
                } catch (IOException e) {
                    throw new AssertionError("Non-JSON stdout line: " + line, e);
                }
                assertThat(node.path("jsonrpc").asText()).as(line).isEqualTo("2.0");
                assertThat(line).doesNotContain("Spring").doesNotContain(":: Spring Boot ::");
            }
        }
    }

    // ---- Tests ----

    @Test
    void T37_initialize_cleanStdoutAndServerInfo() throws Exception {
        JsonNode response = initialize();

        assertThat(response.get("id").asInt()).isEqualTo(1);
        JsonNode result = response.get("result");
        assertThat(result.at("/serverInfo/name").asText()).isEqualTo("product-mcp-server");
        assertThat(result.at("/serverInfo/version").asText()).isEqualTo("0.0.1");
        assertThat(result.at("/capabilities/tools").isObject()).isTrue();
        assertAllLinesAreJsonRpc();
    }

    @Test
    void T38_toolsList_exactlyFiveTools() throws Exception {
        initialize();

        JsonNode response = request("tools/list", Map.of());

        List<String> names = new ArrayList<>();
        response.at("/result/tools").forEach(t -> names.add(t.get("name").asText()));
        assertThat(names).containsExactlyInAnyOrder(
                "list_products", "get_product", "create_product", "update_product", "delete_product");
    }

    @Test
    void T39_listProducts_envBindingTrailingSlashAndBasicAuth() throws Exception {
        routes.put("GET /api/products", new String[] {"200", SEED_LIST});
        initialize();

        JsonNode response = callTool("list_products", Map.of());

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).containsExactly("GET", "/api/products", ADMIN_BASIC);
        assertThat(response.at("/result/isError").asBoolean(false)).isFalse();
        assertThat(response.at("/result/content/0/text").asText()).isEqualTo(SEED_LIST);
    }

    @Test
    void T40_toolErrorIsResult_thenSuccess() throws Exception {
        routes.put("GET /api/products/999", new String[] {"404", notFound(999)});
        routes.put("GET /api/products", new String[] {"200", SEED_LIST});
        initialize();

        JsonNode first = callTool("get_product", Map.of("id", 999));
        assertThat(first.has("error")).as("JSON-RPC error member").isFalse();
        assertThat(first.at("/result/isError").asBoolean()).isTrue();
        assertThat(first.at("/result/content/0/text").asText()).isEqualTo(notFoundText(999));

        JsonNode second = callTool("list_products", Map.of());
        assertThat(second.at("/result/content/0/text").asText()).isEqualTo(SEED_LIST);
        assertThat(process.isAlive()).isTrue();
    }

    @Test
    void T41_afterStdinClosed_allStdoutIsJsonRpc_andLogFileWritten() throws Exception {
        routes.put("GET /api/products", new String[] {"200", SEED_LIST});
        initialize();
        callTool("list_products", Map.of());

        stdin.close();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        reader.join(5_000);

        assertAllLinesAreJsonRpc();
        Path log = tempDir.resolve("product-mcp-server.log");
        assertThat(log).exists();
        assertThat(Files.size(log)).isPositive();
    }
}
