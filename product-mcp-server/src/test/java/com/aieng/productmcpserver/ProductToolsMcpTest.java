package com.aieng.productmcpserver;

import static com.aieng.productmcpserver.StubResponses.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;

/** Layer 2: MCP tools in-JVM with real CallToolResults (MCP-SERVER-T18..T34). */
@SpringBootTest(properties = {
        "spring.ai.mcp.server.enabled=false",
        "product-api.base-url=http://localhost:8080",
        "product-api.username=admin",
        "product-api.password=admin123"})
@AutoConfigureMockRestServiceServer
class ProductToolsMcpTest {

    private static final String BASE = "http://localhost:8080";
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ID = "Product id (positive integer)";
    private static final String NAME = "Product name, 1-100 characters";
    private static final String DESCRIPTION = "Optional description, up to 500 characters";
    private static final String PRICE = "Price greater than 0 with at most 2 decimal places, e.g. 799.00";
    private static final String QUANTITY = "Stock quantity, an integer of 0 or more";

    @Autowired
    private MockRestServiceServer server;

    @Autowired
    private ToolCallbackProvider provider;

    private McpToolTestSupport tools;

    @BeforeEach
    void setUp() {
        tools = new McpToolTestSupport(provider);
    }

    @AfterEach
    void verifyAndReset() {
        server.verify();
        server.reset();
    }

    private static void assertResult(CallToolResult result, String expectedText) {
        assertThat(result.isError()).as("isError").isNotEqualTo(Boolean.TRUE);
        assertThat(result.content()).hasSize(1);
        assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedText);
    }

    private static void assertError(CallToolResult result, String expectedText) {
        assertThat(result.isError()).as("isError").isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(((TextContent) result.content().get(0)).text()).isEqualTo(expectedText);
    }

    /** Matches a JSON request body via a check on the parsed tree. */
    private static RequestMatcher jsonBody(Consumer<JsonNode> check) {
        return request -> check.accept(JSON.readTree(((MockClientHttpRequest) request).getBodyAsString()));
    }

    // ---- Catalogue (S02) ----

    @Test
    void T18_exactlyFiveTools_withCatalogueDescriptions() {
        Map<String, String> expected = Map.of(
                "list_products", "List all products in the catalogue, ordered by id. Returns a JSON array of products, each with id, name, description, price and quantity.",
                "get_product", "Get one product by its id. Returns the product as JSON, or an error if no product has that id.",
                "create_product", "Create a new product. The server assigns the id. Product names do not need to be unique. Returns the created product as JSON.",
                "update_product", "Replace all fields of an existing product. This is a full replace, not a partial update: omitting description clears it. Fails if no product has that id; it never creates a product. Returns the updated product as JSON.",
                "delete_product", "Delete a product by its id. This cannot be undone. Requires the MCP server to be configured with ADMIN credentials; otherwise the backend refuses with 401 or 403.");

        assertThat(tools.specs().keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        tools.specs().forEach((name, spec) -> assertThat(spec.tool().description()).as(name).isEqualTo(expected.get(name)));
    }

    @SuppressWarnings("unchecked")
    private static void assertProperty(JsonSchema schema, String name, String type, String description) {
        Map<String, Object> property = (Map<String, Object>) schema.properties().get(name);
        assertThat(property).as(name).isNotNull();
        assertThat(property.get("type")).as(name + ".type").isEqualTo(type);
        assertThat(property.get("description")).as(name + ".description").isEqualTo(description);
    }

    @Test
    void T19_inputSchemas_matchCatalogue() {
        Map<String, JsonSchema> schemas = new java.util.HashMap<>();
        tools.specs().forEach((name, spec) -> schemas.put(name, spec.tool().inputSchema()));

        JsonSchema list = schemas.get("list_products");
        assertThat(list.properties() == null || list.properties().isEmpty()).as("list_products has no properties").isTrue();

        for (String tool : List.of("get_product", "delete_product")) {
            JsonSchema s = schemas.get(tool);
            assertThat(s.properties()).as(tool).containsOnlyKeys("id");
            assertProperty(s, "id", "integer", ID);
            assertThat(s.required()).as(tool).containsExactly("id");
        }

        JsonSchema create = schemas.get("create_product");
        assertThat(create.properties()).containsOnlyKeys("name", "description", "price", "quantity");
        assertProperty(create, "name", "string", NAME);
        assertProperty(create, "description", "string", DESCRIPTION);
        assertProperty(create, "price", "number", PRICE);
        assertProperty(create, "quantity", "integer", QUANTITY);
        assertThat(create.required()).containsExactlyInAnyOrder("name", "price", "quantity");

        JsonSchema update = schemas.get("update_product");
        assertThat(update.properties()).containsOnlyKeys("id", "name", "description", "price", "quantity");
        assertProperty(update, "id", "integer", ID);
        assertProperty(update, "name", "string", NAME);
        assertProperty(update, "description", "string", DESCRIPTION);
        assertProperty(update, "price", "number", PRICE);
        assertProperty(update, "quantity", "integer", QUANTITY);
        assertThat(update.required()).containsExactlyInAnyOrder("id", "name", "price", "quantity");

        List<String> allProperties = new ArrayList<>();
        schemas.values().forEach(s -> {
            if (s.properties() != null) {
                allProperties.addAll(s.properties().keySet());
            }
        });
        assertThat(allProperties).noneMatch(p -> p.toLowerCase().matches(".*(user|pass|auth|cred).*"));
    }

    // ---- Success results (S05–S09, S16) ----

    @Test
    void T20_listProducts_returnsSeedListExactly() {
        server.expect(requestTo(BASE + "/api/products")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(SEED_LIST, MediaType.APPLICATION_JSON));

        assertResult(tools.call("list_products", "{}"), SEED_LIST);
    }

    @Test
    void T21_listProducts_emptyCatalogue() {
        server.expect(requestTo(BASE + "/api/products"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertResult(tools.call("list_products", "{}"), "[]");
    }

    @Test
    void T22_getProduct_returnsProductJson() {
        server.expect(requestTo(BASE + "/api/products/1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(MOUSE, MediaType.APPLICATION_JSON));

        assertResult(tools.call("get_product", "{\"id\":1}"), MOUSE);
    }

    @Test
    void T23_createProduct_omittedDescriptionSentAsNull() {
        server.expect(requestTo(BASE + "/api/products")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonBody(body -> {
                    assertThat(body.get("name").asText()).isEqualTo("Laptop Stand");
                    assertThat(body.has("description")).as("description key present").isTrue();
                    assertThat(body.get("description").isNull()).isTrue();
                    assertThat(body.get("price").decimalValue()).isEqualByComparingTo("1599");
                    assertThat(body.get("quantity").asInt()).isEqualTo(7);
                }))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(STAND_CREATED));

        assertResult(tools.call("create_product", "{\"name\":\"Laptop Stand\",\"price\":1599.00,\"quantity\":7}"),
                STAND_CREATED);
    }

    @Test
    void T24_updateProduct_omittedDescriptionSentAsNull() {
        server.expect(requestTo(BASE + "/api/products/1")).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonBody(body -> {
                    assertThat(body.has("description")).as("description key present").isTrue();
                    assertThat(body.get("description").isNull()).isTrue();
                }))
                .andRespond(withSuccess(MOUSE_PRO, MediaType.APPLICATION_JSON));

        assertResult(tools.call("update_product",
                "{\"id\":1,\"name\":\"Wireless Mouse Pro\",\"price\":899.00,\"quantity\":20}"), MOUSE_PRO);
    }

    @Test
    void T25_deleteProduct_returnsDeletedText() {
        server.expect(requestTo(BASE + "/api/products/3")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertResult(tools.call("delete_product", "{\"id\":3}"), "Product 3 deleted");
    }

    @Test
    void T26_productJsonShape_nullKeptScaleKeptUnknownDropped() {
        server.expect(requestTo(BASE + "/api/products"))
                .andRespond(withSuccess("[{\"id\":5,\"name\":\"Pen\",\"description\":null,\"price\":10.00,\"quantity\":1,"
                        + "\"createdAt\":\"2026-11-25\"}]", MediaType.APPLICATION_JSON));

        assertResult(tools.call("list_products", "{}"),
                "[{\"id\":5,\"name\":\"Pen\",\"description\":null,\"price\":10.00,\"quantity\":1}]");
    }

    // ---- Error results (S09–S14) ----

    @Test
    void T27_getProduct_notFound() {
        server.expect(requestTo(BASE + "/api/products/999"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM).body(notFound(999)));

        assertError(tools.call("get_product", "{\"id\":999}"), notFoundText(999));
    }

    @Test
    void T28_updateProduct_notFound() {
        server.expect(requestTo(BASE + "/api/products/999")).andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM).body(notFound(999)));

        assertError(tools.call("update_product", "{\"id\":999,\"name\":\"Pen\",\"price\":10,\"quantity\":1}"),
                notFoundText(999));
    }

    @Test
    void T29_createProduct_invalidInputPassedThrough() {
        server.expect(requestTo(BASE + "/api/products")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonBody(body -> {
                    assertThat(body.get("name").asText()).isEqualTo("");
                    assertThat(body.get("price").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(body.get("quantity").asInt()).isEqualTo(-1);
                }))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(PROBLEM).body(INVALID_3));

        assertError(tools.call("create_product", "{\"name\":\"\",\"price\":0,\"quantity\":-1}"), INVALID_3_TEXT);
    }

    @Test
    void T30a_deleteProduct_forbidden() {
        server.expect(requestTo(BASE + "/api/products/3")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(PROBLEM).body(FORBIDDEN));

        assertError(tools.call("delete_product", "{\"id\":3}"), "403 Forbidden: Access Denied");
    }

    @Test
    void T30b_deleteProduct_unauthorized() {
        server.expect(requestTo(BASE + "/api/products/1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(PROBLEM).body(UNAUTHORIZED));

        assertError(tools.call("delete_product", "{\"id\":1}"), "401 Unauthorized: Authentication required");
    }

    @Test
    void T30c_deleteProduct_notFound() {
        server.expect(requestTo(BASE + "/api/products/999")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM).body(notFound(999)));

        assertError(tools.call("delete_product", "{\"id\":999}"), notFoundText(999));
    }

    @Test
    void T31_getProduct_zeroAndNegativeIdsSentAsIs() {
        server.expect(requestTo(BASE + "/api/products/0"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM).body(notFound(0)));
        server.expect(requestTo(BASE + "/api/products/-1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM).body(notFound(-1)));

        assertError(tools.call("get_product", "{\"id\":0}"), notFoundText(0));
        assertError(tools.call("get_product", "{\"id\":-1}"), notFoundText(-1));
    }

    @Test
    void T32_missingId_noHttpCall() {
        assertError(tools.call("get_product", "{}"), "id is required");
        assertError(tools.call("delete_product", "{}"), "id is required");
        assertError(tools.call("update_product", "{\"name\":\"Pen\",\"price\":10,\"quantity\":1}"), "id is required");
        // @AfterEach server.verify() passes with zero expectations and zero requests
    }

    @Test
    void T33_backendUnreachable() {
        server.expect(requestTo(BASE + "/api/products"))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertError(tools.call("list_products", "{}"), "Product API unreachable at http://localhost:8080");
    }

    @Test
    void T34_unexpectedError_thenServerKeepsWorking() {
        server.expect(requestTo(BASE + "/api/products/1"))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/products/999"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM).body(notFound(999)));
        server.expect(requestTo(BASE + "/api/products"))
                .andRespond(withSuccess(SEED_LIST, MediaType.APPLICATION_JSON));

        CallToolResult first = tools.call("get_product", "{\"id\":1}");
        assertThat(first.isError()).isTrue();
        String text = ((TextContent) first.content().get(0)).text();
        assertThat(text).startsWith("Unexpected error: ").doesNotContain("\tat ");

        assertError(tools.call("get_product", "{\"id\":999}"), notFoundText(999));
        assertResult(tools.call("list_products", "{}"), SEED_LIST);
    }
}
