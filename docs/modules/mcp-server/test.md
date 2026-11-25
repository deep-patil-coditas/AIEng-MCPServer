# mcp-server: Test cases
Status: draft (session 10, 2026-11-25)
Derived from: `docs/modules/mcp-server/spec.md` (approved, commit `469adf6`)

## Test location and fixtures

All tests live in `product-mcp-server/src/test/java/com/aieng/productmcpserver/`. None of them need a running
`product-backend` (NFR-06). The backend is always a stub.

| Class | Layer | Kind | Covers |
|-------|-------|------|--------|
| `ProductApiClientTest` | 1: HTTP client | `ProductApiClient` built from a `RestClient.Builder` bound to `MockRestServiceServer`, with `ProductApiProperties` given per test. No Spring context | T01–T17 |
| `ProductToolsMcpTest` | 2: MCP tools in-JVM | `@SpringBootTest` (non-web). `MockRestServiceServer` is bound through the `RestClient.Builder` the app uses. The registered `ToolCallbackProvider` is turned into MCP sync tool specifications the way the starter does it, and each tool is called to get a real `CallToolResult` | T18–T34 |
| `ProductMcpContextTest` | 2: context | `@SpringBootTest`, no stub | T35, T36 |
| `StdioServerProcessTest` | 3: stdio process | Starts the server as a child process (`java -cp <test classpath> com.aieng.productmcpserver.ProductMcpServerApplication`), with a JDK `HttpServer` stub backend on a random port, and talks raw JSON-RPC over its stdin/stdout | T37–T41 |
| *(manual)* | acceptance | Claude Code against the real running backend | T42, T43 |

The plan pins the exact mechanisms: how `MockRestServiceServer` is bound in the Spring context, how the stdio transport
is kept off `System.in` in layer-2 contexts, and the Spring AI helper that turns tool callbacks into
`McpServerFeatures.SyncToolSpecification`s.

**Layer-1 defaults:** base URL `http://localhost:8080`, admin credentials (`admin`/`admin123`) unless the test says
otherwise. Each test ends with `server.verify()`, so an unexpected or missing request fails the test.

**Layer-2 defaults:** test properties `product-api.base-url=http://localhost:8080`, `product-api.username=admin`,
`product-api.password=admin123`. `server.verify()` runs after each test and the server is reset between tests.
"Call `<tool>` `<args>`" means invoking that tool's specification with the given JSON arguments. "Result `R`" means
`isError` is `false` and the content is exactly one `TextContent` with text `R`. "Error `E`" means `isError` is `true`,
one `TextContent` with text `E`, and the call returned normally (no exception, so no JSON-RPC protocol error).

**Layer-3 defaults:** child env `PRODUCT_API_BASE_URL=http://localhost:<stubPort>/` (trailing slash on purpose),
`PRODUCT_API_USERNAME=admin`, `PRODUCT_API_PASSWORD=admin123`, plus `-Djava.io.tmpdir=<@TempDir>`. Every line read
from stdout is parsed with Jackson. Each test sends `initialize` and `notifications/initialized` first. Each test
times out after 30 s, and the process is destroyed in `@AfterEach`. The stub records each request (method, path,
`Authorization` header) and answers from a per-test route table.

**Stub backend responses** (the JSON a real backend would send, used in all layers):

| Name | Status | Body |
|------|--------|------|
| `SEED_LIST` | 200 | `[{"id":1,"name":"Wireless Mouse","description":"2.4 GHz wireless mouse, black","price":799.00,"quantity":25},{"id":2,"name":"Mechanical Keyboard","description":"Blue switches, full size","price":2499.00,"quantity":10},{"id":3,"name":"USB-C Hub","description":"6-in-1 hub with HDMI","price":1299.50,"quantity":0}]` |
| `MOUSE` | 200 | `{"id":1,"name":"Wireless Mouse","description":"2.4 GHz wireless mouse, black","price":799.00,"quantity":25}` |
| `STAND_CREATED` | 201 | `{"id":4,"name":"Laptop Stand","description":null,"price":1599.00,"quantity":7}` |
| `MOUSE_PRO` | 200 | `{"id":1,"name":"Wireless Mouse Pro","description":null,"price":899.00,"quantity":20}` |
| `NOT_FOUND_<id>` | 404 | `{"type":"about:blank","title":"Not Found","status":404,"detail":"Product with id <id> not found","instance":"/api/products/<id>"}` |
| `INVALID_3` | 400 | `{"type":"about:blank","title":"Validation failed","status":400,"detail":"Request has invalid fields","instance":"/api/products","errors":{"quantity":"must be greater than or equal to 0","name":"must not be blank","price":"must be greater than 0"}}` (keys deliberately not sorted) |
| `UNAUTHORIZED` | 401 | `{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Authentication required","instance":"/api/products/1"}` |
| `FORBIDDEN` | 403 | `{"type":"about:blank","title":"Forbidden","status":403,"detail":"Access Denied","instance":"/api/products/3"}` |

All error bodies use `Content-Type: application/problem+json`, and success bodies use `application/json`.

**Basic header values:** admin → `Basic YWRtaW46YWRtaW4xMjM=`, user → `Basic dXNlcjp1c2VyMTIz`.

**Naming:** each test method starts with its ID, e.g. `void T07_adminCredentials_sentOnEveryCall()`. Grouped
cases use `T13a_…`, `T13b_…` or a `@ParameterizedTest` with display name `T13 [<case>]`.

## Layer 1: HTTP client (`ProductApiClientTest`)

### Requests (S05–S09)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| MCP-SERVER-T01 | S05 | Stub: `GET http://localhost:8080/api/products` → `SEED_LIST` | `list()` | Exactly one `GET` to that URL with `Accept` including `application/json`. Returns 3 products in order 1, 2, 3; product 3 has `price` `1299.50` (scale 2) and `quantity` 0 | `SEED_LIST` |
| MCP-SERVER-T02 | S05 | Stub: `GET /api/products` → 200 `[]` | `list()` | Returns an empty list | empty catalogue |
| MCP-SERVER-T03 | S06 | Stub: `GET /api/products/1` → `MOUSE` | `get(1L)` | Returns `Product(1, "Wireless Mouse", "2.4 GHz wireless mouse, black", 799.00, 25)` with `price.scale() == 2` | `MOUSE` |
| MCP-SERVER-T04 | S07 | Stub: `POST /api/products` → `STAND_CREATED` | `create(new ProductInput("Laptop Stand", null, new BigDecimal("1599.00"), 7))` | Request has `Content-Type: application/json` and body exactly `{"name":"Laptop Stand","description":null,"price":1599.00,"quantity":7}` (`description` written as `null`). Returns the product with id 4 | AC-02 data |
| MCP-SERVER-T05 | S08 | Stub: `PUT /api/products/1` → `MOUSE_PRO` | `update(1L, new ProductInput("Wireless Mouse Pro", null, new BigDecimal("899.00"), 20))` | One `PUT` to `/api/products/1` with body exactly `{"name":"Wireless Mouse Pro","description":null,"price":899.00,"quantity":20}`. Returns the product with name `Wireless Mouse Pro` and `description` `null` | FR-PROD-03 |
| MCP-SERVER-T06 | S09 | Stub: `DELETE /api/products/3` → 204, no body | `delete(3L)` | One `DELETE` to `/api/products/3`. Returns normally | AC-08 |

### Configuration and credentials (S03, S04)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| MCP-SERVER-T07 | S04 | Admin credentials. Stubs for all five calls | Each of `list()`, `get(1L)`, `create(…)`, `update(1L, …)`, `delete(3L)` (parameterized) | Each request has header `Authorization: Basic YWRtaW46YWRtaW4xMjM=`, reads included | `admin`/`admin123` |
| MCP-SERVER-T08 | S04 | Credentials per case: (`null`,`null`), (`""`,`""`), (`"admin"`,`null`), (`"admin"`,`""`), (`null`,`"admin123"`), (`"   "`,`"admin123"`) | `list()` (parameterized) | The request has **no** `Authorization` header. Returns `SEED_LIST` | partial credentials (MD-05) |
| MCP-SERVER-T09 | S03 | Base URL `http://localhost:9090/` (trailing slash) | `list()` and `get(1L)` | Requests go to exactly `http://localhost:9090/api/products` and `http://localhost:9090/api/products/1` (no `//`) | MD-12 |

### Error mapping (S10–S13)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| MCP-SERVER-T10 | S11 | Stub: `GET /api/products/999` → `NOT_FOUND_999` | `get(999L)` | Throws `ProductApiException` with message exactly `404 Not Found: Product with id 999 not found` | EC-09 |
| MCP-SERVER-T11 | S11 | Stub: `POST /api/products` → `INVALID_3` | `create(new ProductInput("", null, new BigDecimal("0"), -1))` | Message exactly `400 Bad Request: Request has invalid fields (name: must not be blank; price: must be greater than 0; quantity: must be greater than or equal to 0)`. Fields sorted even though the body lists `quantity` first | EC-05 |
| MCP-SERVER-T12 | S11 | (a) Stub: `DELETE /api/products/1` → `UNAUTHORIZED`; (b) `DELETE /api/products/3` → `FORBIDDEN` | `delete(1L)`; `delete(3L)` | (a) `401 Unauthorized: Authentication required`; (b) `403 Forbidden: Access Denied` | EC-21 |
| MCP-SERVER-T13 | S12 | Per case: (a) 502, `text/html` body `<html><body>Bad Gateway</body></html>`; (b) 500, empty body; (c) 400, `application/problem+json` body `{"title":"Validation failed","status":400}` (no `detail`); (d) 503, body `not json` | `list()` (parameterized) | Messages exactly (a) `502 Bad Gateway`, (b) `500 Internal Server Error`, (c) `400 Bad Request`, (d) `503 Service Unavailable` | MD-07 |
| MCP-SERVER-T14 | S10 | Stubs for `POST /api/products`, `GET /api/products/0`, `GET /api/products/-1` (→ `INVALID_3`, `NOT_FOUND_0`, `NOT_FOUND_-1`) | `create(new ProductInput("  Pen  ", "", new BigDecimal("10.555"), -1))`; `get(0L)`; `get(-1L)` | POST body exactly `{"name":"  Pen  ","description":"","price":10.555,"quantity":-1}` (no trimming, blank-to-null or rounding). GETs go to `/api/products/0` and `/api/products/-1`. Messages `404 Not Found: Product with id 0 not found` and `404 Not Found: Product with id -1 not found` | EC-06, MD-04 |
| MCP-SERVER-T15 | S13 | Real `RestClient` (no mock). Base URL `http://localhost:<p>`, where `<p>` was taken from a `ServerSocket(0)` that is then closed | `list()` | Throws `ProductApiException` with message exactly `Product API unreachable at http://localhost:<p>` | EC-22 |
| MCP-SERVER-T16 | S13 | Real `RestClient`. A JDK `HttpServer` that accepts `GET /api/products` and never answers | `list()` | Message exactly `Product API unreachable at http://localhost:<port>`. Elapsed time ≥ 9.5 s and < 15 s (10 s read timeout) | MD-08 |
| MCP-SERVER-T17 | S04 | Admin credentials. Per case: `UNAUTHORIZED`, `FORBIDDEN`, `INVALID_3`, unreachable (closed port) | The call that triggers it | The exception message contains neither `admin123` nor `YWRtaW46YWRtaW4xMjM=` | credentials never leak |

## Layer 2: MCP tools in-JVM (`ProductToolsMcpTest`)

### Catalogue (S02)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| MCP-SERVER-T18 | S02 | Application context | Read the tool specifications | Exactly 5 tools named `list_products`, `get_product`, `create_product`, `update_product`, `delete_product`. Each description equals the spec §3 catalogue text character for character | catalogue |
| MCP-SERVER-T19 | S02 | Application context | Parse each tool's input JSON schema | `list_products`: no properties. `get_product`, `delete_product`: `id` of type `integer`, `required: [id]`. `create_product`: `name` string, `description` string, `price` number, `quantity` integer, `required` exactly `[name, price, quantity]` (any order). `update_product`: `id` plus the same four, `required` exactly `[id, name, price, quantity]`. Every property's `description` equals the catalogue text. No property name contains `user`, `pass`, `auth` or `cred` (case-insensitive) | FR-MCP-06 |

### Success results (S05–S09, S16)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| MCP-SERVER-T20 | S05, S16 | Stub `GET /api/products` → `SEED_LIST` | Call `list_products` `{}` | Result exactly the `SEED_LIST` body text (same field order, `799.00`, `1299.50`, no whitespace) | AC-07 data |
| MCP-SERVER-T21 | S05 | Stub `GET /api/products` → 200 `[]` | Call `list_products` `{}` | Result `[]` | empty catalogue |
| MCP-SERVER-T22 | S06, S16 | Stub `GET /api/products/1` → `MOUSE` | Call `get_product` `{"id":1}` | Result `{"id":1,"name":"Wireless Mouse","description":"2.4 GHz wireless mouse, black","price":799.00,"quantity":25}` | `MOUSE` |
| MCP-SERVER-T23 | S07, S16 | Stub `POST /api/products` → `STAND_CREATED` | Call `create_product` `{"name":"Laptop Stand","price":1599.00,"quantity":7}` (no `description`) | Request body has `name` `Laptop Stand`, `description` `null` (key present), `price` numerically equal to 1599, `quantity` 7. Result `{"id":4,"name":"Laptop Stand","description":null,"price":1599.00,"quantity":7}` | AC-02 data |
| MCP-SERVER-T24 | S08, S16 | Stub `PUT /api/products/1` → `MOUSE_PRO` | Call `update_product` `{"id":1,"name":"Wireless Mouse Pro","price":899.00,"quantity":20}` | `PUT /api/products/1` with `description` `null` (key present). Result `{"id":1,"name":"Wireless Mouse Pro","description":null,"price":899.00,"quantity":20}` | FR-PROD-03 |
| MCP-SERVER-T25 | S09 | Stub `DELETE /api/products/3` → 204 | Call `delete_product` `{"id":3}` | Result `Product 3 deleted` | AC-08 |
| MCP-SERVER-T26 | S16 | Stub `GET /api/products` → 200 `[{"id":5,"name":"Pen","description":null,"price":10.00,"quantity":1,"createdAt":"2026-11-25"}]` | Call `list_products` `{}` | Result exactly `[{"id":5,"name":"Pen","description":null,"price":10.00,"quantity":1}]`: `null` written out, scale kept, unknown field dropped | spec §4 |

### Error results (S09–S14)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| MCP-SERVER-T27 | S06, S11 | Stub `GET /api/products/999` → `NOT_FOUND_999` | Call `get_product` `{"id":999}` | Error `404 Not Found: Product with id 999 not found` | EC-09 |
| MCP-SERVER-T28 | S08, S11 | Stub `PUT /api/products/999` → `NOT_FOUND_999` | Call `update_product` `{"id":999,"name":"Pen","price":10,"quantity":1}` | Error `404 Not Found: Product with id 999 not found` | EC-12 |
| MCP-SERVER-T29 | S10, S11 | Stub `POST /api/products` → `INVALID_3` | Call `create_product` `{"name":"","price":0,"quantity":-1}` | Request body has `name` `""`, `price` 0, `quantity` -1 (unchanged). Error `400 Bad Request: Request has invalid fields (name: must not be blank; price: must be greater than 0; quantity: must be greater than or equal to 0)` | EC-05 |
| MCP-SERVER-T30 | S09, S11 | Per case: (a) `DELETE /api/products/3` → `FORBIDDEN`; (b) `DELETE /api/products/1` → `UNAUTHORIZED`; (c) `DELETE /api/products/999` → `NOT_FOUND_999` | Call `delete_product` with `{"id":3}`, `{"id":1}`, `{"id":999}` | Errors (a) `403 Forbidden: Access Denied`, (b) `401 Unauthorized: Authentication required`, (c) `404 Not Found: Product with id 999 not found` | EC-21, spec §5 |
| MCP-SERVER-T31 | S10 | Stubs `GET /api/products/0` → `NOT_FOUND_0`, `GET /api/products/-1` → `NOT_FOUND_-1` | Call `get_product` `{"id":0}`, then `{"id":-1}` | Both requests are sent. Errors `404 Not Found: Product with id 0 not found` and `404 Not Found: Product with id -1 not found` | spec §5 |
| MCP-SERVER-T32 | S10 | No stubs expected | Call `get_product` `{}`, `delete_product` `{}`, `update_product` `{"name":"Pen","price":10,"quantity":1}` | Each: error `id is required`. `server.verify()` passes with **zero** requests | MD-06 |
| MCP-SERVER-T33 | S13 | Stub `GET /api/products` → I/O failure (`ConnectException`) | Call `list_products` `{}` | Error `Product API unreachable at http://localhost:8080` | EC-22 |
| MCP-SERVER-T34 | S14 | Stubs: `GET /api/products/1` → 200 `application/json` body `not json`; then `GET /api/products/999` → `NOT_FOUND_999`; then `GET /api/products` → `SEED_LIST` | Call `get_product` `{"id":1}`, then `get_product` `{"id":999}`, then `list_products` `{}` | 1st: `isError` true, text starts with `Unexpected error: ` and contains no `\tat ` (no stack trace). 2nd: error `404 Not Found: Product with id 999 not found`. 3rd: result exactly the `SEED_LIST` body | MD-13 |

## Layer 2: context (`ProductMcpContextTest`)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| MCP-SERVER-T35 | S01 | Application context with the main `application.properties` | Inspect the context | It is **not** a `WebServerApplicationContext` (no HTTP port). `spring.ai.mcp.server.name` is `product-mcp-server` and `spring.ai.mcp.server.version` is `0.0.1` | |
| MCP-SERVER-T36 | S03 | Application context with no `product-api.*` overrides. Skipped (`assumeTrue`) if any `PRODUCT_API_*` env var is set in the build environment | Read the `ProductApiProperties` bean | `baseUrl` is `http://localhost:8080`. `username` and `password` are null or empty | defaults |

## Layer 3: stdio process (`StdioServerProcessTest`)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| MCP-SERVER-T37 | S01, S15 | Child process started, stub idle | Send `initialize` (protocol version supported by the pinned SDK, `clientInfo` `{name:"test",version:"1"}`) | The response has the request's id, `result.serverInfo.name` `product-mcp-server`, `result.serverInfo.version` `0.0.1`, and a `result.capabilities.tools` object. Every stdout line received so far parses as JSON with `"jsonrpc":"2.0"`. No line contains `Spring` or `:: Spring Boot ::` | FR-MCP-07 |
| MCP-SERVER-T38 | S02 | After initialize | Send `tools/list` | `result.tools` has exactly the 5 names from T18 | AC-07 (automated part) |
| MCP-SERVER-T39 | S03, S04, S05 | Stub `GET /api/products` → `SEED_LIST` | Send `tools/call` `list_products` `{}` | The stub got exactly one request: `GET /api/products` (no `//`, so the trailing slash in the env var was ignored) with `Authorization: Basic YWRtaW46YWRtaW4xMjM=`. `result.isError` is false (or absent) and `result.content[0].text` equals the `SEED_LIST` body | env binding |
| MCP-SERVER-T40 | S11, S14 | Stubs `GET /api/products/999` → `NOT_FOUND_999`, `GET /api/products` → `SEED_LIST` | `tools/call` `get_product` `{"id":999}`, then `tools/call` `list_products` `{}` | 1st: a JSON-RPC **result** (not an `error` member) with `isError: true` and text `404 Not Found: Product with id 999 not found`. 2nd: the seed list. `process.isAlive()` is still true | error then success |
| MCP-SERVER-T41 | S15 | After T37's exchange plus one `tools/call` | Close the child's stdin, wait for exit (≤ 10 s, else destroy), read the rest of stdout | Every stdout line from the whole run parses as JSON-RPC. The file `<@TempDir>/product-mcp-server.log` exists and is not empty | MD-09 |

## Manual acceptance (NFR-06, spec §7)

Run once after implementation, with `product-backend` started (`mvnw.cmd -pl product-backend spring-boot:run`) and
the server registered in Claude Code using the spec §4 snippet. Record the outcome in the session log.

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| MCP-SERVER-T42 | S01, S02, S05 | Fresh backend, server registered with admin env vars | In Claude Code: `/mcp`, then ask "list all products" | `products` server connected with 5 tools. `list_products` returns the 3 seed products | AC-07 |
| MCP-SERVER-T43 | S04, S09, S11 | Fresh backend | (a) With admin env vars, ask to delete product 3. (b) Change env to `user`/`user123`, restart the server, and ask to delete product 2 | (a) `Product 3 deleted`, and listing shows ids 1–2. (b) Tool error `403 Forbidden: Access Denied`, product 2 still listed | AC-08, EC-21 |

## Coverage

| Spec rule | Tests |
|-----------|-------|
| MCP-SERVER-S01 | T35, T37, T42; module wiring also checked by `./mvnw test` itself (AC-06) |
| MCP-SERVER-S02 | T18, T19, T38, T42 |
| MCP-SERVER-S03 | T09, T36, T39 |
| MCP-SERVER-S04 | T07, T08, T17, T39, T43 |
| MCP-SERVER-S05 | T01, T02, T20, T21, T39, T42 |
| MCP-SERVER-S06 | T03, T22, T27 |
| MCP-SERVER-S07 | T04, T23 |
| MCP-SERVER-S08 | T05, T24, T28 |
| MCP-SERVER-S09 | T06, T25, T30, T43 |
| MCP-SERVER-S10 | T14, T29, T31, T32 |
| MCP-SERVER-S11 | T10, T11, T12, T27–T30, T40, T43 |
| MCP-SERVER-S12 | T13 |
| MCP-SERVER-S13 | T15, T16, T33 |
| MCP-SERVER-S14 | T34, T40 |
| MCP-SERVER-S15 | T37, T41 |
| MCP-SERVER-S16 | T20, T22, T23, T24, T26 |

Edge cases (spec §5): EC-21 (T12, T30a, T43b), EC-22 (T15, T33), delete with no creds (T12a, T30b), wrong creds on a
read (T12a: the backend sends the same 401 body for anonymous and wrong credentials, SECURITY-T17), only username set (T08), admin deletes unknown id (T30c),
several invalid fields (T11, T29), price with 3 decimals (T14), id 0 or negative (T14, T31), missing id (T32), update
omits description (T05, T24), empty catalogue (T02, T21), HTML error body (T13a), trailing slash (T09, T39), error
then success (T34, T40).
Acceptance criteria: AC-06 (the whole suite runs from the root build), AC-07 (T38 automated, T42 manual), AC-08 (T43
manual; mapping automated in T12, T25, T30).
Requirements: FR-MCP-01 (T35, T37), FR-MCP-02 (T18, T20–T25), FR-MCP-03 (T07–T09, T36, T39), FR-MCP-04 (T10–T14,
T27–T32, T34, T40), FR-MCP-05 (T15, T16, T33), FR-MCP-06 (T18, T19), FR-MCP-07 (T37, T41), NFR-02 (every rule above),
NFR-06 (no test needs the backend; only T42–T43 use it, by hand).

Fixture files: none. Stub bodies are inline constants in a shared test helper (`StubResponses`). The backend's
`reset-products.sql` and `data.sql` are not used.

## Not tested here

- Backend validation and authorization themselves: `product` and `security` test.md (PRODUCT-T…, SECURITY-T…).
- The connect timeout value (5 s) separately from "unreachable": a refused connection (T15) fails at once, and a real
  connect timeout needs an unroutable address, which is unreliable on CI. Read timeout is covered by T16.
- Log file contents and log levels: only "exists, not empty, not on stdout" (T41).
- MCP resources, prompts, other transports, retries: out of scope (spec §7).
- Claude Desktop registration: same stdio contract as Claude Code (T42).

## Decision log

| ID | Question | Decision |
|----|----------|----------|
| TD-01 | Q1: how to test what the AI client sees | Three layers: `ProductApiClient` with `MockRestServiceServer`; in-JVM MCP tool specifications returning real `CallToolResult`s; one stdio child-process test class with a JDK `HttpServer` stub. Plus two manual acceptance tests |
| TD-02 | *(decided without asking)* How the child process is started | From the test classpath (`java -cp ${java.class.path}`), not the jar, because `./mvnw test` runs before `package` |
| TD-03 | *(decided without asking)* JSON-RPC client in layer 3 | Raw line-by-line JSON over stdin/stdout, not the MCP SDK client, so any stray non-JSON stdout line fails the test |
| TD-04 | *(decided without asking)* Unreachable backend | Layer 1 uses a real closed port (T15). Layer 2 simulates an I/O failure in the mock (T33) |
| TD-05 | *(decided without asking)* Read timeout | Tested for real once (T16, about 10 s of wall time). Connect timeout not tested separately |
| TD-06 | *(decided without asking)* Exact output assertions | Tool result text is compared as an exact string (field order, `null`, price scale). Error texts are exact strings |
| TD-07 | *(decided without asking)* Price in outbound bodies at the tool layer | Compared by numeric value (T23, T24, T29), because the tool-argument JSON conversion may not keep trailing zeros. Layer 1 gets a `BigDecimal` directly and asserts the exact JSON (T04, T05, T14) |
| TD-08 | *(decided without asking)* "No HTTP call" checks | `MockRestServiceServer.verify()` with no expectations (T32) |
| TD-09 | *(decided without asking)* Default-config test vs a developer's env | T36 is skipped via `assumeTrue` when a `PRODUCT_API_*` env var is set |
| TD-10 | *(decided without asking)* Trigger for "unexpected error" | A 200 response whose body is not JSON (T34) |
| TD-11 | *(decided without asking)* AC-07 / AC-08 | Manual tests T42, T43 with IDs and steps; outcome recorded in the implementation session's log |
| TD-12 | *(decided without asking)* Naming | `T<NN>_<behaviour>()` in package `com.aieng.productmcpserver`; grouped cases `T13a_…` or parameterized `T13 [<case>]` |
