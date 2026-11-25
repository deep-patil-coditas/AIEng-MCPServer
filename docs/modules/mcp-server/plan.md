# mcp-server: Implementation plan
Status: draft (session 11, 2026-11-25)
Derived from: `docs/modules/mcp-server/spec.md` (approved, `469adf6`) and `docs/modules/mcp-server/test.md` (approved, `4a38557`)

This plan adds the new Maven module `product-mcp-server`. It changes no backend code. The only file outside the new
module that changes is the root `pom.xml` (one `<module>` line).

The mechanisms below were checked in session 11 with a throwaway probe against Spring AI 1.1.8 (MCP Java SDK 0.18.3)
on Boot 3.5.16. The probe is not committed. Its findings are marked *(probe)*.

## 1. Classes / files

`…/` means `product-mcp-server/src/main/java/com/aieng/productmcpserver/` for main code and
`product-mcp-server/src/test/java/com/aieng/productmcpserver/` for tests. Everything is in one package.

**Build**

| Name | Location | Change | Spec rules |
|------|----------|--------|------------|
| `pom.xml` | repo root | Add `<module>product-mcp-server</module>` after `product-backend` | S01 |
| `pom.xml` | `product-mcp-server/` | Parent `com.aieng:aieng-mcpserver`. Property `spring-ai.version` `1.1.8`, and `spring-ai-bom` imported in `<dependencyManagement>`. Dependencies in §2. `spring-boot-maven-plugin` so `package` builds the executable jar named in the spec §4 registration snippet | S01 |
| `application.properties` | `product-mcp-server/src/main/resources/` | Exactly the spec §4 block, plus `spring.ai.mcp.server.type=SYNC` (the default, written out so it's visible) | S01, S03, S15 |

**Main code**

| Name | Responsibility | Spec rules |
|------|----------------|------------|
| `ProductMcpServerApplication` | `@SpringBootApplication`, `main` calls `SpringApplication.run`. Nothing else | S01 |
| `ProductApiProperties` | `@ConfigurationProperties(prefix = "product-api") record (String baseUrl, String username, String password)` | S03 |
| `ProductApiConfig` | `@Configuration @EnableConfigurationProperties(ProductApiProperties.class)`. Holds the timeouts: `static final ClientHttpRequestFactorySettings HTTP_SETTINGS = ClientHttpRequestFactorySettings.defaults().withConnectTimeout(Duration.ofSeconds(5)).withReadTimeout(Duration.ofSeconds(10))`, exposed as a `@Bean ClientHttpRequestFactorySettings`. Boot's `HttpClientAutoConfiguration` backs off (`@ConditionalOnMissingBean`), so the auto-configured `RestClient.Builder` gets these timeouts | S13 |
| `ProductApiClient` | `@Component`. Constructor `(RestClient.Builder builder, ProductApiProperties props)` builds one `RestClient` (details below). Five methods from spec §2 | S03, S04, S05–S09, S11–S13 |
| `ErrorText` | Package-private, static `String format(HttpStatusCode status, byte[] body)`. Builds `<status> <reason>[: <detail>][ (<field>: <msg>; …)]` | S11, S12 |
| `ProductApiException` | `RuntimeException(String message)`. Its message *is* the tool-error text | S11–S14 |
| `Product`, `ProductInput` | Records from spec §2. `Product` has `@JsonIgnoreProperties(ignoreUnknown = true)`; component order gives the JSON field order | S16 |
| `ProductTools` | `@Component`. The five `@Tool` methods with the exact catalogue texts (spec §3). Constructor `(ProductApiClient client, ObjectMapper objectMapper)` | S02, S05–S10, S14, S16 |
| `RawTextResultConverter` | Package-private `ToolCallResultConverter` that returns the method's `String` unchanged. Every `@Tool` sets `resultConverter = RawTextResultConverter.class` | S05–S09, S16 |
| `McpToolConfig` | `@Bean ToolCallbackProvider productToolCallbacks(ProductTools tools)` = `MethodToolCallbackProvider.builder().toolObjects(tools).build()`. The starter's `ToolCallbackConverterAutoConfiguration` turns it into sync tool specifications | S02 |

**`ProductApiClient` details**

```java
String base = props.baseUrl().replaceAll("/+$", "");          // S03, MD-12
RestClient.Builder b = builder.baseUrl(base)
    .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
        throw new ProductApiException(ErrorText.format(res.getStatusCode(), res.getBody().readAllBytes()));
    });
if (hasText(props.username()) && hasText(props.password())) {  // S04, MD-05
    b.defaultHeaders(h -> h.setBasicAuth(props.username(), props.password()));
}
this.restClient = b.build();
```

- `list()` → `get().uri("/api/products").accept(APPLICATION_JSON).retrieve().body(new ParameterizedTypeReference<List<Product>>() {})`.
  `get`, `create` (`contentType(APPLICATION_JSON).body(input)`), `update` and `delete` (`toBodilessEntity()`) follow the
  same shape, with `uri("/api/products/{id}", id)`.
- Every method wraps its call in `try { … } catch (ResourceAccessException e) { throw new ProductApiException("Product API unreachable at " + base); }`.
  Spring wraps both a refused connection and the JDK client's `HttpTimeoutException` in `ResourceAccessException` (S13).
  The cause is not chained into the message, so no URL user-info or header can leak (S04, T17).
- The client never sets a request factory itself. In Spring the timeouts come from `ProductApiConfig`, and in layer 1
  the test passes a builder (spec §2 sketch, test.md layer-1 row).

**`ErrorText.format` rules**

1. Head: `status.value() + " " + HttpStatus.resolve(value).getReasonPhrase()`. Unknown codes use the value alone.
   The reason comes from `HttpStatus`, not the response's status text, which the JDK client doesn't provide.
2. Parse the body with a private static `ObjectMapper` (`readTree`). If the body is empty, isn't JSON, or has no
   textual `detail`, return the head only (S12, T13).
3. Otherwise `head + ": " + detail`. If `errors` is a non-empty object, append
   ` (` + entries sorted by key, each `key: value`, joined with `; ` + `)` (S11, T11).

**`ProductTools` details**

- Each tool body runs inside `call(() -> …)`:
  ```java
  private String call(Supplier<String> work) {
      try { return work.get(); }
      catch (ProductApiException e) { throw e; }
      catch (RuntimeException e) { throw new ProductApiException("Unexpected error: " + e.getMessage()); }
  }
  ```
  Spring AI turns any exception thrown from a `@Tool` method into `CallToolResult(isError = true)` whose only text is
  the exception message *(probe)*. So the error texts reach the client unchanged, and no stack trace is included (S14).
- `get_product`, `update_product` and `delete_product` start with `if (id == null) throw new ProductApiException("id is required")`.
  Spring AI passes a missing argument as `null` even when the schema marks it required *(probe)* (S10, T32).
- list/get/create/update return `objectMapper.writeValueAsString(…)` with Boot's `ObjectMapper`. `description: null`
  is written out (Boot's default inclusion is `ALWAYS`), and `BigDecimal` keeps the scale it was parsed with (S16).
  A `JsonProcessingException` is wrapped in an unchecked exception and becomes `Unexpected error: …`.
- `delete_product` returns `"Product " + id + " deleted"`.
- Why `RawTextResultConverter`: Spring AI's default converter JSON-encodes the returned `String`, so
  `Product 3 deleted` would arrive as `"Product 3 deleted"` with quotes, and product JSON would be double-encoded
  *(probe)*.

**Tests**

| Name | Covers | Setup |
|------|--------|-------|
| `StubResponses` | | Constants from test.md "Stub backend responses" and the two Basic header values |
| `ProductApiClientTest` | T01–T17 | `RestClient.Builder b = RestClient.builder(); MockRestServiceServer server = MockRestServiceServer.bindTo(b).build(); new ProductApiClient(b, props)`. T15, T16 and the unreachable case of T17 use a real builder instead: `RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ProductApiConfig.HTTP_SETTINGS))`, so they test the production timeout values |
| `McpToolTestSupport` | | Builds `List<SyncToolSpecification>` with `McpToolUtils.toSyncToolSpecification(List.of(provider.getToolCallbacks()))` (what the starter does) and calls a tool through `spec.callHandler().apply(EXCHANGE, new CallToolRequest(name, argsMap))`. `EXCHANGE` is a real `McpSyncServerExchange` wrapping `new McpAsyncServerExchange("test", null, ClientCapabilities.builder().build(), new Implementation("test", "1"), McpTransportContext.EMPTY)`. A `null` exchange makes every call fail with a `null` error text *(probe)* |
| `ProductToolsMcpTest` | T18–T34 | `@SpringBootTest(properties = {"spring.ai.mcp.server.enabled=false", <layer-2 defaults>}) @AutoConfigureMockRestServiceServer`. `@Autowired MockRestServiceServer server`. `@AfterEach`: `server.verify(); server.reset();` |
| `ProductMcpContextTest` | T35, T36 | `@SpringBootTest(properties = "spring.ai.mcp.server.enabled=false")`. T35 reads the two name/version properties from the `Environment` and checks the context isn't a `WebServerApplicationContext` |
| `StdioServerProcessTest` | T37–T41 | Child `ProcessBuilder(<java.home>/bin/java, "-Djava.io.tmpdir=" + tempDir, "-cp", System.getProperty("java.class.path"), "com.aieng.productmcpserver.ProductMcpServerApplication")`, env from test.md layer-3 defaults. Stub backend: `com.sun.net.httpserver.HttpServer` on port 0, with a route table and a request log. One reader thread puts stdout lines on a `BlockingQueue`. `initialize` uses `protocolVersion` `2025-11-25` (`McpSchema.LATEST_PROTOCOL_VERSION` in SDK 0.18.3) |

How the stdio transport stays off `System.in` in layer 2: `spring.ai.mcp.server.enabled=false` turns off the whole
MCP server auto-configuration, so no `StdioServerTransportProvider` is built. Our `ToolCallbackProvider` bean is our
own configuration, so it stays. This matters because Surefire's forked JVM uses stdin/stdout to talk to Maven.

How `MockRestServiceServer` is bound in layer 2: `@AutoConfigureMockRestServiceServer` registers a
`MockServerRestClientCustomizer`. Boot applies it to the `RestClient.Builder` bean after the request-factory settings,
so the mock replaces the real factory, and `ProductApiClient` receives that builder through its constructor.

## 2. Dependencies

| Library | Version | Scope | Use |
|---------|---------|-------|-----|
| `spring-ai-starter-mcp-server` | 1.1.8 (`spring-ai-bom`) | compile | stdio MCP server, `@Tool`, `ToolCallbackProvider`, `McpToolUtils` |
| MCP Java SDK `io.modelcontextprotocol.sdk:mcp` | 0.18.3 (via Spring AI) | compile | `McpSchema`, sync server, stdio transport |
| `spring-web` | 6.2.19 (Boot-managed) | compile | `RestClient`. The MCP starter doesn't bring it. `spring-boot-starter-web` is not used, so Tomcat isn't on the classpath |
| `spring-boot-starter-json` | 3.5.16 (managed) | compile | Boot `ObjectMapper`, Jackson auto-configuration |
| Jackson | 2.21.4 (managed) | compile | product JSON, error body parsing |
| `spring-boot-starter-test` | 3.5.16 (managed) | test | JUnit 5, AssertJ, `MockRestServiceServer`, `@AutoConfigureMockRestServiceServer` |

Version choice: Spring AI 2.x needs Spring Boot 4, and CLAUDE.md fixes Boot 3.5.x. So the newest compatible line is
1.1.x, and 1.1.8 is its latest release. It resolves cleanly against Boot 3.5.16 (checked with `dependency:tree`).
Mockito is not used, so the JDK 25 / Byte Buddy issue (product plan R1) doesn't apply.

Other modules used: none at build time. The server talks to `product-backend` only over HTTP at run time.

## 3. Steps

Each step ends in the stated test state and is its own commit. Test command: `mvnw.cmd test` from the repo root.

| # | Step | Ends with | Commit |
|---|------|-----------|--------|
| 1 | **Module skeleton.** Root `<module>`, module `pom.xml`, `application.properties`, `ProductMcpServerApplication`, `ProductApiProperties`, `ProductApiConfig`, the records, `ProductApiException`, `RawTextResultConverter`, `McpToolConfig`. `ProductTools` gets its real `@Tool`/`@ToolParam` declarations (the catalogue is a declaration, not behaviour), but every method body throws `UnsupportedOperationException`. `ProductApiClient` and `ErrorText` have real signatures and bodies that throw `UnsupportedOperationException` | Root build green: all backend tests pass; the new module compiles and has no tests yet | `feat(mcp-server): add product-mcp-server module skeleton` |
| 2 | **Tests first.** `StubResponses`, `McpToolTestSupport` and the four test classes, T01–T41 as in test.md. Check each failure has the right cause: layer-1 tests throw `UnsupportedOperationException`, and layer-2 tool calls return `Unexpected error: null` instead of the expected text. Expected to pass already: T18, T19 (catalogue), T35, T36 (context), T37, T38 (initialize and `tools/list` don't call the tools), probably T41 | Tests compile. Only expected failures; pass/fail count recorded in the commit body | `test(mcp-server): add tests MCP-SERVER-T01..T41` |
| 3 | **HTTP client.** Real `ProductApiClient` and `ErrorText` | `ProductApiClientTest` green (T16 adds about 10 s). Layer-2 tests still fail at the tool layer | `feat(mcp-server): implement ProductApiClient with error mapping` |
| 4 | **Tools.** Real `ProductTools` bodies | Whole root build green: backend tests plus T01–T41 | `feat(mcp-server): implement product MCP tools` |
| 5 | **Manual acceptance (T42, T43).** `mvnw.cmd -pl product-mcp-server -am package -DskipTests`, start the backend with `mvnw.cmd -pl product-backend spring-boot:run`, then register the jar in Claude Code with `claude mcp add` (local scope, so nothing is committed) using the spec §4 command and env vars. Run T42 and T43 (a) with admin, then re-register with `user`/`user123` for T43 (b) | Outcomes recorded in the session log | none, unless a fix is needed |

If a step shows a spec gap, stop, update spec.md/test.md, commit, then carry on (CLAUDE.md rule).

## 4. Risks / open points

| ID | Risk | Mitigation |
|----|------|------------|
| R1 | Spring AI JSON-encodes `String` tool results by default (`"Product 3 deleted"`) *(probe)* | `RawTextResultConverter` on every `@Tool`. T20–T26 compare exact strings |
| R2 | A thrown `RuntimeException` with a `null` message gives an empty error text | All our exceptions carry a message. Unexpected ones get the `Unexpected error: ` prefix, and T34 checks that prefix |
| R3 | Tool-argument conversion turns `1599.00` into `BigDecimal` `1599.0` *(probe)*, so the backend gets `1599.0` | Accepted by the spec (S10 passes values through) and by test.md TD-07 (numeric comparison at the tool layer). The backend's response, not our input, decides the output scale |
| R4 | Surefire runs the fork with a manifest-only classpath jar on Windows, so `java.class.path` is `surefirebooter*.jar` | The JVM honours that jar's `Class-Path` manifest, so `java -cp <it> …` still finds every class. If not, set `<useManifestOnlyJar>false</useManifestOnlyJar>` in the module's Surefire config |
| R5 | `logging.pattern.console=` might not fully silence console output, or a library might print to stdout | T37 and T41 parse every stdout line. If one fails, add `logging.threshold.console=OFF` and record it in the spec (S15) first |
| R6 | After stdin closes, the child JVM may not exit by itself | T41 already allows destroying it after 10 s. Exiting on EOF isn't a spec rule |
| R7 | `MockServerRestClientCustomizer` must be applied after the timeout settings, or the real JDK client would be used in layer 2 | Settings are applied when Boot builds the builder, and customizers run after that. A wrong order shows as T20 failing with "unreachable" |
| R8 | Layer 2 doesn't run the starter's own `ToolCallbackConverterAutoConfiguration` (server disabled) | It calls the same `McpToolUtils.toSyncToolSpecification` with the default MIME type. Layer 3 (T38–T40) checks the real starter path end to end |
| R9 | A developer's `PRODUCT_API_*` env vars leak into tests | Layers 1–2 set every property explicitly; T36 uses `assumeTrue` (TD-09); layer 3 sets the child's env explicitly |

## 5. Definition of done

- MCP-SERVER-T01..T41 are implemented, named with their IDs, and pass with `mvnw.cmd test` from the root, together
  with every backend test (backend tests unchanged).
- Every spec rule S01–S16 is covered (test.md coverage table), and the spec is unchanged or was updated and committed first.
- T42 and T43 run by hand against the real backend in Claude Code, with outcomes in the session log (AC-07, AC-08).
- No credential in code other than test fixtures and the spec's demo values. No `.mcp.json` with credentials committed.
  No `.idea/`, `target/` or `*.iml` committed.
- Commits follow §3 in order, and the session log is exported.

## 6. Decision log

| ID | Question | Decision |
|----|----------|----------|
| PL-01 | *(decided without asking)* Spring AI version | 1.1.8 through `spring-ai-bom`, imported in the module pom only. 2.x needs Boot 4 |
| PL-02 | *(decided without asking)* How a thrown exception becomes `isError: true` (spec §2 left this to the plan) | Spring AI's own handling: the exception message becomes the only text content *(probe)*. No custom `ToolExecutionExceptionProcessor` |
| PL-03 | *(decided without asking)* Plain-text tool results | `RawTextResultConverter` on every `@Tool` (R1) |
| PL-04 | *(decided without asking)* Where the timeouts live | A `ClientHttpRequestFactorySettings` constant and bean in `ProductApiConfig`, used by Boot's builder and reused by T15/T16. Not `spring.http.client.*` properties, so the layer-1 tests use the production values |
| PL-05 | *(decided without asking)* Web dependency | `spring-web` + `spring-boot-starter-json`, not `spring-boot-starter-web` (no Tomcat in a stdio server) |
| PL-06 | *(decided without asking)* Layer-2 isolation from stdio | `spring.ai.mcp.server.enabled=false` in layer-2 tests; tool specifications built with `McpToolUtils` and a real test exchange |
| PL-07 | *(decided without asking)* Error-text helper | Separate package-private `ErrorText`, reason phrase from `HttpStatus`, error body parsed with its own `ObjectMapper` so `ProductApiClient` keeps the two-argument constructor from test.md |
| PL-08 | *(decided without asking)* Extra config class | `ProductApiConfig` (properties + timeouts) next to the spec's `McpToolConfig`; the spec §2 sketch doesn't forbid helpers |
| PL-09 | *(decided without asking)* Manual registration | `claude mcp add` at local scope for T42/T43, so no `.mcp.json` with credentials is committed |
| PL-10 | *(decided without asking)* Steps | Skeleton with the real catalogue declarations first, so T18/T19/T35–T38 can pass before behaviour exists; client and tools implemented in separate commits |
