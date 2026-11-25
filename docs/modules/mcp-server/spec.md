# mcp-server: Specification
Status: draft (session 09, 2026-11-25)
Implements: FR-MCP-01..07, NFR-01, NFR-02, NFR-06, EC-21, EC-22, AC-06, AC-07, AC-08

## 1. Responsibility

The `mcp-server` module is a separate Maven module, `product-mcp-server`. It is a Spring AI MCP server that uses the
stdio transport and exposes the five Product endpoints of `product-backend` as five MCP tools. It covers:

- the five tools: names, descriptions, parameter schemas, and how each one maps to one backend endpoint
- the HTTP client (`RestClient`) that calls the running backend, with its base URL and Basic credentials taken from
  configuration
- turning backend responses into tool results. Success returns product JSON (or a short text for delete). An error
  becomes an MCP tool error with the text `<status> <reason>: <detail>`.
- keeping stdout clean for the stdio protocol

It does **not** validate product input or enforce security. Both stay in `product-backend`, which is the single
source of truth (PRODUCT-S10..S12, SECURITY-S01..S10). The MCP server passes the AI's input through and reports
whatever the backend answers. It has no database and no state of its own, and it doesn't start or manage the backend.

## 2. Public interface

Maven module `product-mcp-server` (artifact `product-mcp-server`), listed in the root `pom.xml` `<modules>` after
`product-backend`. Package: `com.aieng.productmcpserver`.

```java
@SpringBootApplication
class ProductMcpServerApplication { static void main(String[] args); }

// Bound from configuration (MCP-SERVER-S03)
@ConfigurationProperties(prefix = "product-api")
record ProductApiProperties(String baseUrl, String username, String password) { }

// Thin typed HTTP client over RestClient (MCP-SERVER-S04, S11..S14)
@Component
class ProductApiClient {
    List<Product> list();                                  // GET    /api/products
    Product get(Long id);                                  // GET    /api/products/{id}
    Product create(ProductInput input);                    // POST   /api/products
    Product update(Long id, ProductInput input);           // PUT    /api/products/{id}
    void delete(Long id);                                  // DELETE /api/products/{id}
    // Every method throws ProductApiException on a backend error or a connection failure.
}

// The MCP tools (MCP-SERVER-S02, S05..S10)
@Component
class ProductTools {
    @Tool(name = "list_products",  description = ...) String listProducts();
    @Tool(name = "get_product",    description = ...) String getProduct(@ToolParam(...) Long id);
    @Tool(name = "create_product", description = ...) String createProduct(@ToolParam(...) String name,
                                                                          @ToolParam(required = false, ...) String description,
                                                                          @ToolParam(...) BigDecimal price,
                                                                          @ToolParam(...) Integer quantity);
    @Tool(name = "update_product", description = ...) String updateProduct(@ToolParam(...) Long id,
                                                                          @ToolParam(...) String name,
                                                                          @ToolParam(required = false, ...) String description,
                                                                          @ToolParam(...) BigDecimal price,
                                                                          @ToolParam(...) Integer quantity);
    @Tool(name = "delete_product", description = ...) String deleteProduct(@ToolParam(...) Long id);
}

// Registers ProductTools with the MCP server
@Configuration
class McpToolConfig { @Bean ToolCallbackProvider productToolCallbacks(ProductTools tools); }

// Carries the tool-error text (MCP-SERVER-S11..S14)
class ProductApiException extends RuntimeException { ProductApiException(String message); }

record Product(Long id, String name, String description, BigDecimal price, Integer quantity) { }
record ProductInput(String name, String description, BigDecimal price, Integer quantity) { }
```

The exact Spring AI version and the way a thrown exception becomes an MCP `CallToolResult` with `isError: true` are
settled in the plan. The behaviour the AI client sees is fixed here.

## 3. Behaviour rules

Tool catalogue (MCP-SERVER-S02). The descriptions below are the exact text:

| Tool | Description | Parameters (name: type, required, description) |
|------|-------------|-------------------------------------------------|
| `list_products` | `List all products in the catalogue, ordered by id. Returns a JSON array of products, each with id, name, description, price and quantity.` | none |
| `get_product` | `Get one product by its id. Returns the product as JSON, or an error if no product has that id.` | `id`: integer, required, `Product id (positive integer)` |
| `create_product` | `Create a new product. The server assigns the id. Product names do not need to be unique. Returns the created product as JSON.` | `name`: string, required, `Product name, 1-100 characters`<br>`description`: string, optional, `Optional description, up to 500 characters`<br>`price`: number, required, `Price greater than 0 with at most 2 decimal places, e.g. 799.00`<br>`quantity`: integer, required, `Stock quantity, an integer of 0 or more` |
| `update_product` | `Replace all fields of an existing product. This is a full replace, not a partial update: omitting description clears it. Fails if no product has that id; it never creates a product. Returns the updated product as JSON.` | `id` as in `get_product`, then `name`, `description`, `price` and `quantity` as in `create_product` |
| `delete_product` | `Delete a product by its id. This cannot be undone. Requires the MCP server to be configured with ADMIN credentials; otherwise the backend refuses with 401 or 403.` | `id` as in `get_product` |

| ID | Rule | Requirement(s) | Example (input → result) |
|----|------|----------------|--------------------------|
| MCP-SERVER-S01 | **Module and transport.** `product-mcp-server` is its own Maven module in the root build, so `./mvnw test` builds and tests it with the backend. It uses the Spring AI MCP server starter with the **stdio** transport and runs as a non-web application, so it opens no HTTP port. Server name `product-mcp-server`, version `0.0.1`. | FR-MCP-01, NFR-01, AC-06 | `mvnw.cmd test` from the repo root runs the tests of both modules. |
| MCP-SERVER-S02 | **Exactly five tools** are registered, with the names, descriptions and parameters in the catalogue above. Required parameters are marked required in the input JSON schema. `description` is optional. No tool has a credential parameter. | FR-MCP-02, FR-MCP-03, FR-MCP-06, AC-07 | `tools/list` → `list_products`, `get_product`, `create_product`, `update_product`, `delete_product`. The `create_product` schema has `required: [name, price, quantity]`. |
| MCP-SERVER-S03 | **Configuration.** Properties `product-api.base-url`, `product-api.username` and `product-api.password` are bound from the env vars `PRODUCT_API_BASE_URL` (default `http://localhost:8080`), `PRODUCT_API_USERNAME` and `PRODUCT_API_PASSWORD` (no defaults). A trailing `/` on the base URL is ignored. | FR-MCP-03 | `PRODUCT_API_BASE_URL=http://localhost:9090/` → calls go to `http://localhost:9090/api/products`. Nothing set → `http://localhost:8080/api/products`. |
| MCP-SERVER-S04 | **Credentials.** When username and password are **both** set and not blank, every backend call (all five tools) sends `Authorization: Basic base64(username:password)`. Otherwise no `Authorization` header is sent and the calls are anonymous. Credentials never appear in tool parameters, tool results, error texts or logs. | FR-MCP-03 | `admin`/`admin123` → `Authorization: Basic YWRtaW46YWRtaW4xMjM=` on `GET /api/products` too. Only `PRODUCT_API_USERNAME=admin` set → no header. |
| MCP-SERVER-S05 | **`list_products`** calls `GET /api/products` and returns the JSON array exactly as ordered by the backend (by id). An empty catalogue returns `[]`. | FR-MCP-02, FR-API-02, AC-07 | Fresh backend → `[{"id":1,"name":"Wireless Mouse","description":"2.4 GHz wireless mouse, black","price":799.00,"quantity":25}, {"id":2,…}, {"id":3,"name":"USB-C Hub",…,"price":1299.50,"quantity":0}]` |
| MCP-SERVER-S06 | **`get_product`** calls `GET /api/products/{id}` and returns the product as JSON. | FR-MCP-02, FR-API-03 | `id=1` → `{"id":1,"name":"Wireless Mouse","description":"2.4 GHz wireless mouse, black","price":799.00,"quantity":25}`. `id=999` → error `404 Not Found: Product with id 999 not found`. |
| MCP-SERVER-S07 | **`create_product`** calls `POST /api/products` with body `{name, description, price, quantity}` and returns the created product (backend 201) as JSON. An omitted `description` is sent as `null`. | FR-MCP-02, FR-API-01 | `name="Laptop Stand", price=1599.00, quantity=7` on a fresh backend → `{"id":4,"name":"Laptop Stand","description":null,"price":1599.00,"quantity":7}` |
| MCP-SERVER-S08 | **`update_product`** calls `PUT /api/products/{id}` with the full body and returns the updated product as JSON. An omitted `description` is sent as `null`, so it clears the description (full replace, FR-PROD-03). | FR-MCP-02, FR-API-04, FR-PROD-03 | `id=1, name="Wireless Mouse Pro", price=899.00, quantity=20` → `{"id":1,"name":"Wireless Mouse Pro","description":null,"price":899.00,"quantity":20}`. `id=999` → error `404 Not Found: Product with id 999 not found`. |
| MCP-SERVER-S09 | **`delete_product`** calls `DELETE /api/products/{id}`. On backend 204 it returns the text `Product <id> deleted`. | FR-MCP-02, FR-API-05, AC-08 | Admin creds, `id=3` → `Product 3 deleted`. User creds, `id=3` → error `403 Forbidden: Access Denied` (EC-21). No creds → error `401 Unauthorized: Authentication required`. |
| MCP-SERVER-S10 | **No client-side validation.** Tool arguments are passed to the backend unchanged (no trimming, range checks or rounding). The backend's 400 comes back as a tool error (S11). An `id` <= 0 is sent as-is, and the backend answers 404. The one exception: a missing (`null`) `id` returns the error `id is required` without calling the backend, because no URL can be built. | FR-MCP-04, (Q4) | `create_product name="", price=0, quantity=-1` → backend 400 → error per S11. `get_product id=0` → `404 Not Found: Product with id 0 not found`. `get_product` with no id → `id is required`. |
| MCP-SERVER-S11 | **Backend errors become tool errors.** A 4xx/5xx response produces an MCP tool result with `isError: true` and one text content `<status> <reason phrase>: <detail>`, where `detail` is the ProblemDetail `detail`. If the body has an `errors` map, ` (<field>: <message>; …)` is appended, with fields sorted alphabetically. | FR-MCP-04, (Q1), (Q2) | `{name:"", price:0, quantity:-1}` → `400 Bad Request: Request has invalid fields (name: must not be blank; price: must be greater than 0; quantity: must be greater than or equal to 0)`. Backend 403 → `403 Forbidden: Access Denied`. |
| MCP-SERVER-S12 | **Non-ProblemDetail error body.** If an error response has no body, a body that isn't JSON, or no `detail`, the text is just `<status> <reason phrase>`. | FR-MCP-04 | Backend 502 with an HTML body → `502 Bad Gateway`. Backend 500 with an empty body → `500 Internal Server Error`. |
| MCP-SERVER-S13 | **Backend unreachable.** A connection failure or timeout (connect 5 s, read 10 s) produces the tool error `Product API unreachable at <base-url>`. | FR-MCP-05, EC-22 | No backend on 8080 → `list_products` → `Product API unreachable at http://localhost:8080` |
| MCP-SERVER-S14 | **Never crash.** A failed tool call never stops the server. Any other unexpected exception produces the tool error `Unexpected error: <exception message>`, and the next tool call works normally. | FR-MCP-04 | `get_product id=999` (error), then `list_products` → the product list. |
| MCP-SERVER-S15 | **Clean stdout.** Only MCP JSON-RPC messages are written to stdout: the Spring banner is off and console logging is off. Logs go to the file `${java.io.tmpdir}/product-mcp-server.log`. | FR-MCP-07 | Starting the server and sending `initialize` → every line on stdout parses as a JSON-RPC message. |
| MCP-SERVER-S16 | **Product JSON shape.** Tool results serialise products with the fields `id`, `name`, `description` (with `null` written out, not omitted), `price` (with the backend's scale kept, e.g. `799.00`) and `quantity`, in that order. | FR-IN-03 | `{"id":3,"name":"USB-C Hub","description":"6-in-1 hub with HDMI","price":1299.50,"quantity":0}` |

## 4. Data shapes

**`ProductInput`** (request body sent to `POST` and `PUT`):

| Field | Type | Nullable | Notes |
|-------|------|----------|-------|
| `name` | String | yes (backend rejects it) | passed through unchanged |
| `description` | String | yes | `null` when the tool argument is omitted |
| `price` | BigDecimal | yes (backend rejects it) | JSON number |
| `quantity` | Integer | yes (backend rejects it) | JSON integer |

**`Product`** (backend response and tool result): `id` Long, `name` String, `description` String (nullable),
`price` BigDecimal, `quantity` Integer. Unknown fields in the backend response are ignored.

**Backend error body read by the client** (ProblemDetail): only `detail` (String, optional) and `errors`
(object of String → String, optional) are used. The status comes from the HTTP status line.

**Tool results:**

| Outcome | `isError` | Content (one text item) |
|---------|-----------|-------------------------|
| list / get / create / update success | false | product JSON or JSON array (S16) |
| delete success | false | `Product <id> deleted` |
| backend 4xx/5xx | true | `<status> <reason>: <detail>[ (<field>: <msg>; …)]` or `<status> <reason>` (S11, S12) |
| unreachable | true | `Product API unreachable at <base-url>` |
| missing id | true | `id is required` |
| unexpected | true | `Unexpected error: <message>` |

**Configuration** (`application.properties`):

```properties
spring.main.web-application-type=none
spring.main.banner-mode=off
spring.ai.mcp.server.name=product-mcp-server
spring.ai.mcp.server.version=0.0.1
spring.ai.mcp.server.stdio=true
logging.pattern.console=
logging.file.name=${java.io.tmpdir}/product-mcp-server.log
product-api.base-url=${PRODUCT_API_BASE_URL:http://localhost:8080}
product-api.username=${PRODUCT_API_USERNAME:}
product-api.password=${PRODUCT_API_PASSWORD:}
```

**Claude Code registration** (AC-07; for the README/plan, not tested automatically):

```json
{ "mcpServers": { "products": {
    "command": "java",
    "args": ["-jar", "product-mcp-server/target/product-mcp-server-0.0.1-SNAPSHOT.jar"],
    "env": { "PRODUCT_API_BASE_URL": "http://localhost:8080",
             "PRODUCT_API_USERNAME": "admin", "PRODUCT_API_PASSWORD": "admin123" } } } }
```

## 5. Edge cases

| Case | Behaviour | Sample data | Rule |
|------|-----------|-------------|------|
| EC-21: delete with user creds | tool error `403 Forbidden: Access Denied` | `PRODUCT_API_USERNAME=user`, `delete_product id=3` | S09, S11 |
| EC-22: backend down | tool error `Product API unreachable at http://localhost:8080` | no backend on 8080 | S13 |
| Delete with no creds | tool error `401 Unauthorized: Authentication required` | env vars unset, `delete_product id=1` | S04, S09 |
| Wrong creds on a read | tool error `401 Unauthorized: Authentication required` | `admin`/`wrong`, `list_products` | S04, S11 |
| Only username set | anonymous calls; reads work, delete gets 401 | `PRODUCT_API_USERNAME=admin` only | S04 |
| Admin deletes unknown id | `404 Not Found: Product with id 999 not found` | admin creds, `delete_product id=999` | S09, S11 |
| Several invalid fields | all listed, sorted | `create_product name="", price=0, quantity=-1` | S10, S11 |
| Price with 3 decimals | backend 400 passed through: `… (price: <backend message>)` | `price=10.555` | S10, S11 |
| Id 0 or negative | backend 404 passed through | `get_product id=-1` → `404 Not Found: Product with id -1 not found` | S10 |
| Missing id | `id is required`, no HTTP call | `get_product` with no `id` | S10 |
| Update omits description | description cleared | FR-PROD-03 example on id 1 | S08 |
| Empty catalogue | `[]` | after deleting ids 1–3 | S05 |
| Error with an HTML body | `<status> <reason>` only | backend/proxy 502 HTML | S12 |
| Base URL with trailing slash | slash ignored | `http://localhost:8080/` | S03 |
| Error then success | server keeps serving | `get_product 999`, then `list_products` | S14 |

## 6. Errors

| Trigger | Detected by | Tool result text | Server keeps running |
|---------|-------------|------------------|----------------------|
| Backend 4xx/5xx with a ProblemDetail | `RestClient` status handler | `<status> <reason>: <detail>` + optional `(errors…)` | yes |
| Backend 4xx/5xx without a usable body | `RestClient` status handler | `<status> <reason>` | yes |
| Connection refused / timeout | `ResourceAccessException` | `Product API unreachable at <base-url>` | yes |
| `id` argument missing | tool method | `id is required` | yes |
| Anything else | catch-all in the tool layer | `Unexpected error: <message>` | yes |

Guarantees: no stack trace or credential appears in a tool result, and nothing but protocol messages goes to stdout.
A tool error is reported as an MCP tool result with `isError: true`, not as a JSON-RPC protocol error.

## 7. Out of scope

- Input validation and authorization (`product-backend`: PRODUCT-S10..S12, SECURITY-S01..S10)
- HTTP/SSE or streamable-HTTP MCP transports. Only stdio is supported.
- MCP resources, prompts, sampling and completions. Only tools are exposed.
- Retries, caching and circuit breakers
- Starting, stopping or health-checking the backend
- Choosing credentials per call or per user. One identity per server process.
- Pagination and search tools (requirements §1)
- An automated end-to-end test against a running backend. AC-07 and AC-08 are checked manually (NFR-06).

## 8. Decision log

| ID | Question | Decision |
|----|----------|----------|
| MD-01 | Q1: tool-error text format (CLAUDE.md `<status>: <detail>` vs FR-MCP-04 `403 Forbidden: Access Denied`) | `<status> <reason phrase>: <detail>`, e.g. `403 Forbidden: Access Denied`. CLAUDE.md is updated to match. |
| MD-02 | Q2: field errors in 400 tool errors | Appended as ` (field: message; …)`, sorted by field name, only when the body has an `errors` map |
| MD-03 | Q3: success output | get/create/update return product JSON, list returns a JSON array, delete returns `Product <id> deleted` |
| MD-04 | Q4: client-side validation | None. The backend is the single source of truth, and its 400 is passed through (S10). |
| MD-05 | *(decided without asking)* Partial credentials | Basic is sent only when username and password are both non-blank; otherwise calls are anonymous |
| MD-06 | *(decided without asking)* Missing `id` | Tool error `id is required`, no HTTP call (no URL can be built) |
| MD-07 | *(decided without asking)* Error body without `detail` | Fallback text `<status> <reason phrase>` |
| MD-08 | *(decided without asking)* Timeouts | Connect 5 s, read 10 s; a timeout is reported like unreachable |
| MD-09 | *(decided without asking)* Logging | Banner off, console logging off, log file `${java.io.tmpdir}/product-mcp-server.log` |
| MD-10 | *(decided without asking)* Tool mechanism | Spring AI `@Tool`/`@ToolParam` methods on `ProductTools`, registered through a `ToolCallbackProvider` bean. Version pinned in the plan. |
| MD-11 | *(decided without asking)* Server identity | name `product-mcp-server`, version `0.0.1`; package `com.aieng.productmcpserver` |
| MD-12 | *(decided without asking)* Trailing slash in base URL | Stripped |
| MD-13 | *(decided without asking)* Unexpected exceptions | Tool error `Unexpected error: <message>`; the server keeps running |
