package com.aieng.productmcpserver;

import static com.aieng.productmcpserver.StubResponses.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Layer 1: HTTP client against MockRestServiceServer (MCP-SERVER-T01..T17). */
class ProductApiClientTest {

    private static final String BASE = "http://localhost:8080";
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    private static ProductApiProperties admin(String baseUrl) {
        return new ProductApiProperties(baseUrl, "admin", "admin123");
    }

    /** A client plus its mock server, with the given properties. */
    private record Fixture(ProductApiClient client, MockRestServiceServer server) {
        static Fixture of(ProductApiProperties props) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            return new Fixture(new ProductApiClient(builder, props), server);
        }
    }

    private static ProductApiClient realClient(String baseUrl) {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ProductApiConfig.HTTP_SETTINGS));
        return new ProductApiClient(builder, admin(baseUrl));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ---- Requests (S05–S09) ----

    @Test
    void T01_list_getsAllProductsInOrder() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", containsString("application/json")))
                .andRespond(withSuccess(SEED_LIST, MediaType.APPLICATION_JSON));

        List<Product> products = f.client().list();

        assertThat(products).extracting(Product::id).containsExactly(1L, 2L, 3L);
        Product hub = products.get(2);
        assertThat(hub.price()).isEqualTo(new BigDecimal("1299.50"));
        assertThat(hub.price().scale()).isEqualTo(2);
        assertThat(hub.quantity()).isZero();
        f.server().verify();
    }

    @Test
    void T02_list_emptyCatalogue() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(f.client().list()).isEmpty();
        f.server().verify();
    }

    @Test
    void T03_get_returnsProductWithScaleKept() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products/1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(MOUSE, MediaType.APPLICATION_JSON));

        Product p = f.client().get(1L);

        assertThat(p).isEqualTo(new Product(1L, "Wireless Mouse", "2.4 GHz wireless mouse, black",
                new BigDecimal("799.00"), 25));
        assertThat(p.price().scale()).isEqualTo(2);
        f.server().verify();
    }

    @Test
    void T04_create_postsBodyWithNullDescription() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", containsString("application/json")))
                .andExpect(content().string(
                        "{\"name\":\"Laptop Stand\",\"description\":null,\"price\":1599.00,\"quantity\":7}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(STAND_CREATED));

        Product p = f.client().create(new ProductInput("Laptop Stand", null, new BigDecimal("1599.00"), 7));

        assertThat(p.id()).isEqualTo(4L);
        f.server().verify();
    }

    @Test
    void T05_update_putsFullBody() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products/1")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().string(
                        "{\"name\":\"Wireless Mouse Pro\",\"description\":null,\"price\":899.00,\"quantity\":20}"))
                .andRespond(withSuccess(MOUSE_PRO, MediaType.APPLICATION_JSON));

        Product p = f.client().update(1L, new ProductInput("Wireless Mouse Pro", null, new BigDecimal("899.00"), 20));

        assertThat(p.name()).isEqualTo("Wireless Mouse Pro");
        assertThat(p.description()).isNull();
        f.server().verify();
    }

    @Test
    void T06_delete_sendsDelete() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products/3")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        f.client().delete(3L);
        f.server().verify();
    }

    // ---- Configuration and credentials (S03, S04) ----

    record Call(String name, HttpMethod method, String path, HttpStatus status, String body,
                Consumer<ProductApiClient> invoke) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Call> allCalls() {
        ProductInput stand = new ProductInput("Laptop Stand", null, new BigDecimal("1599.00"), 7);
        return Stream.of(
                new Call("list", HttpMethod.GET, "/api/products", HttpStatus.OK, SEED_LIST, ProductApiClient::list),
                new Call("get", HttpMethod.GET, "/api/products/1", HttpStatus.OK, MOUSE, c -> c.get(1L)),
                new Call("create", HttpMethod.POST, "/api/products", HttpStatus.CREATED, STAND_CREATED, c -> c.create(stand)),
                new Call("update", HttpMethod.PUT, "/api/products/1", HttpStatus.OK, MOUSE_PRO, c -> c.update(1L, stand)),
                new Call("delete", HttpMethod.DELETE, "/api/products/3", HttpStatus.NO_CONTENT, null, c -> c.delete(3L)));
    }

    @ParameterizedTest(name = "T07 [{0}]")
    @MethodSource("allCalls")
    void T07_adminCredentials_sentOnEveryCall(Call call) {
        Fixture f = Fixture.of(admin(BASE));
        var response = withStatus(call.status());
        if (call.body() != null) {
            response = response.contentType(MediaType.APPLICATION_JSON).body(call.body());
        }
        f.server().expect(requestTo(BASE + call.path())).andExpect(method(call.method()))
                .andExpect(header("Authorization", ADMIN_BASIC))
                .andRespond(response);

        call.invoke().accept(f.client());
        f.server().verify();
    }

    static Stream<Arguments> partialCredentials() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of("", ""),
                Arguments.of("admin", null),
                Arguments.of("admin", ""),
                Arguments.of(null, "admin123"),
                Arguments.of("   ", "admin123"));
    }

    @ParameterizedTest(name = "T08 [user={0}, pass={1}]")
    @MethodSource("partialCredentials")
    void T08_partialCredentials_noAuthorizationHeader(String username, String password) {
        Fixture f = Fixture.of(new ProductApiProperties(BASE, username, password));
        f.server().expect(requestTo(BASE + "/api/products")).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(SEED_LIST, MediaType.APPLICATION_JSON));

        assertThat(f.client().list()).hasSize(3);
        f.server().verify();
    }

    @Test
    void T09_trailingSlashInBaseUrl_ignored() {
        Fixture f = Fixture.of(admin("http://localhost:9090/"));
        f.server().expect(requestTo("http://localhost:9090/api/products"))
                .andRespond(withSuccess(SEED_LIST, MediaType.APPLICATION_JSON));
        f.server().expect(requestTo("http://localhost:9090/api/products/1"))
                .andRespond(withSuccess(MOUSE, MediaType.APPLICATION_JSON));

        f.client().list();
        f.client().get(1L);
        f.server().verify();
    }

    // ---- Error mapping (S10–S13) ----

    @Test
    void T10_notFound_mappedToToolErrorText() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products/999"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM).body(notFound(999)));

        assertThatThrownBy(() -> f.client().get(999L))
                .isInstanceOf(ProductApiException.class)
                .hasMessage(notFoundText(999));
        f.server().verify();
    }

    @Test
    void T11_validationErrors_sortedByField() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(PROBLEM).body(INVALID_3));

        assertThatThrownBy(() -> f.client().create(new ProductInput("", null, new BigDecimal("0"), -1)))
                .isInstanceOf(ProductApiException.class)
                .hasMessage(INVALID_3_TEXT);
        f.server().verify();
    }

    @Test
    void T12a_unauthorized() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products/1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(PROBLEM).body(UNAUTHORIZED));

        assertThatThrownBy(() -> f.client().delete(1L))
                .isInstanceOf(ProductApiException.class)
                .hasMessage("401 Unauthorized: Authentication required");
        f.server().verify();
    }

    @Test
    void T12b_forbidden() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products/3")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(PROBLEM).body(FORBIDDEN));

        assertThatThrownBy(() -> f.client().delete(3L))
                .isInstanceOf(ProductApiException.class)
                .hasMessage("403 Forbidden: Access Denied");
        f.server().verify();
    }

    static Stream<Arguments> unusableErrorBodies() {
        return Stream.of(
                Arguments.of("a: 502 html", HttpStatus.BAD_GATEWAY, MediaType.TEXT_HTML,
                        "<html><body>Bad Gateway</body></html>", "502 Bad Gateway"),
                Arguments.of("b: 500 empty", HttpStatus.INTERNAL_SERVER_ERROR, null, "", "500 Internal Server Error"),
                Arguments.of("c: 400 no detail", HttpStatus.BAD_REQUEST, PROBLEM,
                        "{\"title\":\"Validation failed\",\"status\":400}", "400 Bad Request"),
                Arguments.of("d: 503 not json", HttpStatus.SERVICE_UNAVAILABLE, null, "not json", "503 Service Unavailable"));
    }

    @ParameterizedTest(name = "T13 [{0}]")
    @MethodSource("unusableErrorBodies")
    void T13_errorWithoutUsableBody_statusAndReasonOnly(String label, HttpStatus status, MediaType type,
                                                       String body, String expected) {
        Fixture f = Fixture.of(admin(BASE));
        var response = withStatus(status).body(body);
        if (type != null) {
            response = response.contentType(type);
        }
        f.server().expect(requestTo(BASE + "/api/products")).andRespond(response);

        assertThatThrownBy(() -> f.client().list())
                .isInstanceOf(ProductApiException.class)
                .hasMessage(expected);
        f.server().verify();
    }

    @Test
    void T14_noClientSideValidation_inputPassedThrough() {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(
                        "{\"name\":\"  Pen  \",\"description\":\"\",\"price\":10.555,\"quantity\":-1}"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(PROBLEM).body(INVALID_3));
        f.server().expect(requestTo(BASE + "/api/products/0"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM).body(notFound(0)));
        f.server().expect(requestTo(BASE + "/api/products/-1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(PROBLEM).body(notFound(-1)));

        assertThatThrownBy(() -> f.client().create(new ProductInput("  Pen  ", "", new BigDecimal("10.555"), -1)))
                .isInstanceOf(ProductApiException.class);
        assertThatThrownBy(() -> f.client().get(0L)).hasMessage(notFoundText(0));
        assertThatThrownBy(() -> f.client().get(-1L)).hasMessage(notFoundText(-1));
        f.server().verify();
    }

    @Test
    void T15_backendUnreachable() throws IOException {
        int port = closedPort();
        ProductApiClient client = realClient("http://localhost:" + port);

        assertThatThrownBy(client::list)
                .isInstanceOf(ProductApiException.class)
                .hasMessage("Product API unreachable at http://localhost:" + port);
    }

    @Test
    void T16_readTimeout_reportedAsUnreachable() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/api/products", exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            ProductApiClient client = realClient("http://localhost:" + port);
            long start = System.nanoTime();

            ProductApiException e = catchThrowableOfType(ProductApiException.class, client::list);

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(e).hasMessage("Product API unreachable at http://localhost:" + port);
            assertThat(elapsedMs).isBetween(9_500L, 14_999L);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    static Stream<Arguments> leakCases() {
        return Stream.of(
                Arguments.of("401", HttpStatus.UNAUTHORIZED, UNAUTHORIZED),
                Arguments.of("403", HttpStatus.FORBIDDEN, FORBIDDEN),
                Arguments.of("400", HttpStatus.BAD_REQUEST, INVALID_3));
    }

    @ParameterizedTest(name = "T17 [{0}]")
    @MethodSource("leakCases")
    void T17_errorTexts_neverContainCredentials(String label, HttpStatus status, String body) {
        Fixture f = Fixture.of(admin(BASE));
        f.server().expect(requestTo(BASE + "/api/products/1"))
                .andRespond(withStatus(status).contentType(PROBLEM).body(body));

        ProductApiException e = catchThrowableOfType(ProductApiException.class, () -> f.client().delete(1L));

        assertThat(e).isNotNull();
        assertThat(e.getMessage()).doesNotContain("admin123").doesNotContain("YWRtaW46YWRtaW4xMjM=");
        f.server().verify();
    }

    @Test
    void T17_unreachable_neverContainsCredentials() throws IOException {
        ProductApiClient client = realClient("http://localhost:" + closedPort());

        ProductApiException e = catchThrowableOfType(ProductApiException.class, client::list);

        assertThat(e).isNotNull();
        assertThat(e.getMessage()).doesNotContain("admin123").doesNotContain("YWRtaW46YWRtaW4xMjM=");
    }
}
