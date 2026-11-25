# AIEng-MCPServer: Requirements
Status: approved baseline (session 01, 2026-11-25)

## 1. Purpose and scope

The project builds a small Product catalogue backend and then an MCP server around it, so an AI client can manage products through MCP tools.

- **Phase 1, `product-backend`:** a Spring Boot REST API with CRUD on `Product`. Create, read and update are open to anyone. Delete needs the `ADMIN` role.
- **Phase 2, `product-mcp-server`:** a separate Spring AI MCP server (stdio). It exposes the five Product operations as MCP tools by calling the backend over HTTP.

**Out of scope:**
- user registration and management
- JWT/OAuth
- persistent database
- pagination, search, filtering and sorting options
- PATCH
- categories, images and stock movements
- UI
- deployment and containers
- the H2 web console
- HTTPS

## 2. Glossary

| Term | Meaning |
|------|---------|
| Product | A catalogue item with `id`, `name`, `description`, `price`, `quantity` |
| ADMIN / USER | Roles of the in-memory users; only ADMIN can delete |
| Anonymous | A request with no `Authorization` header |
| ProblemDetail | RFC 7807 JSON error body (`type`, `title`, `status`, `detail`, `instance`) |
| MCP tool | A function exposed by the MCP server to an AI client |
| Seed data | The 3 products inserted at backend startup |

## 3. Inputs / data formats

- **FR-IN-01:** Request and response bodies are JSON (`application/json`). Error bodies are `application/problem+json`.
- **FR-IN-02:** Product request body (create and update):

  | Field | Type | Required | Rule |
  |-------|------|----------|------|
  | `name` | string | yes | not blank, max 100 chars |
  | `description` | string | no | max 500 chars; missing means `null` |
  | `price` | number (BigDecimal) | yes | > 0, at most 10 integer digits and 2 decimals |
  | `quantity` | integer | yes | >= 0 |

  Any `id` sent in a request body is ignored. Unknown JSON fields are ignored.
- **FR-IN-03:** Product response body: `{ "id": Long, "name", "description", "price", "quantity" }`.
- **FR-IN-04:** `{id}` path variables are positive integers (Long).
- **FR-IN-05:** Authentication uses the HTTP Basic `Authorization` header. The in-memory users are:

  | Username | Password | Role |
  |----------|----------|------|
  | `admin` | `admin123` | ADMIN |
  | `user` | `user123` | USER |

## 4. Endpoints

Base path: `/api/products`. Backend port: `8080`.

| ID | Method and path | Auth | Success | Failures |
|----|-----------------|------|---------|----------|
| FR-API-01 | `POST /api/products` | none | 201, created product, `Location: /api/products/{id}` | 400 validation or malformed JSON |
| FR-API-02 | `GET /api/products` | none | 200, JSON array of all products, ordered by `id` ascending (empty array if none) | none |
| FR-API-03 | `GET /api/products/{id}` | none | 200, product | 404 not found, 400 non-numeric id |
| FR-API-04 | `PUT /api/products/{id}` | none | 200, updated product | 400 validation, 404 not found |
| FR-API-05 | `DELETE /api/products/{id}` | ADMIN | 204, no body | 401 anonymous or bad credentials, 403 authenticated non-ADMIN, 404 not found (ADMIN only) |

## 5. Product rules

- **FR-PROD-01:** The server generates `id` (auto-increment). The client never chooses it.
- **FR-PROD-02:** Names need not be unique. Two products called "Wireless Mouse" can exist with different ids.
- **FR-PROD-03 (update is a full replace):** `PUT` validates exactly like create and replaces all four fields. A missing `description` sets it to `null`. An unknown id returns 404; it never creates a product (no upsert).
  - Example: product 1 is `{name:"Wireless Mouse", description:"2.4 GHz, black", price:799.00, quantity:25}`. After `PUT /api/products/1` with `{name:"Wireless Mouse Pro", price:899.00, quantity:20}`, the result is `{id:1, name:"Wireless Mouse Pro", description:null, price:899.00, quantity:20}`.
- **FR-PROD-04:** A deleted product no longer appears in the list, and `GET` on its id returns 404. Ids are never reused.
- **FR-PROD-05 (seed data):** At startup the backend inserts:

  | id | name | description | price | quantity |
  |----|------|-------------|-------|----------|
  | 1 | Wireless Mouse | 2.4 GHz wireless mouse, black | 799.00 | 25 |
  | 2 | Mechanical Keyboard | Blue switches, full size | 2499.00 | 10 |
  | 3 | USB-C Hub | 6-in-1 hub with HDMI | 1299.50 | 0 |

  The next product created gets `id` 4. Data resets when the backend restarts.

## 6. Security rules

- **FR-SEC-01:** `POST`, both `GET`s and `PUT` on `/api/products/**` are open to anonymous callers.
- **FR-SEC-02:** `DELETE /api/products/{id}` requires an authenticated user with role `ADMIN`.
  - An anonymous caller gets 401 with a `WWW-Authenticate: Basic` header.
  - USER gets 403.
- **FR-SEC-03:** Wrong credentials get 401 on any endpoint, even an open one. This is Spring Security's default behaviour for HTTP Basic.
- **FR-SEC-04:** The API is stateless. It creates no HTTP session, and CSRF protection is disabled.
- **FR-SEC-05:** Authorization is checked before the product lookup. A USER deleting a non-existent id gets 403, not 404.

## 7. Error rules

- **FR-ERR-01:** Every 4xx/5xx from the application is a ProblemDetail with a human-readable `detail`.
- **FR-ERR-02:** Validation failures return 400 with title `Validation failed`. They also include an `errors` object with field → message, e.g. `{"price": "must be greater than 0"}`. All invalid fields are reported together, not just the first one.
- **FR-ERR-03:** Not found returns 404 with detail `Product with id 999 not found`.
- **FR-ERR-04:** Malformed JSON or a non-numeric path id returns 400 ProblemDetail.
- **FR-ERR-05:** 401 and 403 responses from the security layer return a ProblemDetail body.

## 8. MCP server rules (phase 2)

- **FR-MCP-01:** The MCP server is a separate Maven module (`product-mcp-server`). It is a Spring AI MCP server with stdio transport.
- **FR-MCP-02:** It exposes exactly 5 tools, each mapped to one endpoint:

  | Tool | Endpoint | Parameters |
  |------|----------|------------|
  | `list_products` | FR-API-02 | none |
  | `get_product` | FR-API-03 | `id` |
  | `create_product` | FR-API-01 | `name`, `description?`, `price`, `quantity` |
  | `update_product` | FR-API-04 | `id`, `name`, `description?`, `price`, `quantity` |
  | `delete_product` | FR-API-05 | `id` |

- **FR-MCP-03:** The backend base URL and credentials come from configuration and environment variables:
  - `PRODUCT_API_BASE_URL` (default `http://localhost:8080`)
  - `PRODUCT_API_USERNAME`
  - `PRODUCT_API_PASSWORD`

  When both credentials are set, every call sends HTTP Basic. Credentials are never tool parameters.
- **FR-MCP-04:** When the backend returns an error, the tool reports an error result containing the HTTP status and the ProblemDetail `detail`, e.g. `403 Forbidden: Access Denied`. It does not crash the server.
- **FR-MCP-05:** If the backend is unreachable, the tool returns an error result such as `Product API unreachable at http://localhost:8080`.
- **FR-MCP-06:** Every tool has a clear description, and each parameter has a description too, so an AI client can choose and call it correctly.
- **FR-MCP-07:** In stdio mode, nothing other than MCP protocol messages may be written to stdout. That means no banner and no console logging to stdout.

## Non-functional requirements

- **NFR-01:** Java 21, Spring Boot 3.5.x, Maven multi-module build with the wrapper. `./mvnw test` from the repo root builds and tests everything.
- **NFR-02:** Every spec rule has at least one automated test (JUnit 5, MockMvc, spring-security-test). Tests reference their `test.md` IDs.
- **NFR-03:** The backend starts with `./mvnw -pl product-backend spring-boot:run` and needs no external services.
- **NFR-04:** Passwords are stored with a `PasswordEncoder` (BCrypt), never `{noop}`.
- **NFR-05:** Layering: controller → service → repository. Controllers use request/response DTOs and never expose the JPA entity directly.
- **NFR-06:** MCP server tests use a mocked HTTP backend (e.g. `MockRestServiceServer`) and do not need the backend running.

## Edge cases

| ID | Case | Expected | Sample data |
|----|------|----------|-------------|
| EC-01 | Create with `price: 0` | 400, `errors.price` | `{name:"Pen", price:0, quantity:5}` |
| EC-02 | Create with negative quantity | 400, `errors.quantity` | `{name:"Pen", price:10, quantity:-1}` |
| EC-03 | Create with blank name `"   "` | 400, `errors.name` | `{name:"   ", price:10, quantity:1}` |
| EC-04 | Name of 101 characters | 400, `errors.name` | `"a"` × 101 |
| EC-05 | Several invalid fields | 400, with all of them in `errors` | `{name:"", price:-1, quantity:-1}` |
| EC-06 | `price` with 3 decimals | 400, `errors.price` | `{name:"Pen", price:10.555, quantity:1}` |
| EC-07 | `quantity: 0` is valid | 201 | `{name:"Pen", price:10, quantity:0}` (like seed id 3) |
| EC-08 | Duplicate name | 201 with a new id | `POST {name:"Wireless Mouse", price:799.00, quantity:1}` → id 4 |
| EC-09 | GET an unknown id | 404 `Product with id 999 not found` | `GET /api/products/999` |
| EC-10 | Non-numeric id | 400 | `GET /api/products/abc` |
| EC-11 | Malformed JSON body | 400 | `{"name": "Pen",` |
| EC-12 | PUT an unknown id | 404, nothing created | `PUT /api/products/999` with a valid body |
| EC-13 | PUT without description | description becomes `null` | FR-PROD-03 example on id 1 |
| EC-14 | Body contains `id` | ignored; the server id is used | `POST {id:50, name:"Pen", price:10, quantity:1}` → id 4 |
| EC-15 | DELETE anonymous | 401 + `WWW-Authenticate` | `DELETE /api/products/1` |
| EC-16 | DELETE as user | 403, product still exists | `user/user123`, id 1 |
| EC-17 | DELETE as admin, unknown id | 404 | `admin/admin123`, id 999 |
| EC-18 | DELETE as user, unknown id | 403 (auth first) | `user/user123`, id 999 |
| EC-19 | Wrong password on an open endpoint | 401 | `GET /api/products` with `admin/wrong` |
| EC-20 | List after deleting every product | 200 `[]` | delete ids 1–3 as admin |
| EC-21 | MCP `delete_product` with user creds | tool error with `403` | `PRODUCT_API_USERNAME=user` |
| EC-22 | MCP tool with the backend down | tool error "unreachable" | no backend on 8080 |

## Acceptance criteria

| ID | Criterion |
|----|-----------|
| AC-01 | After a fresh start, `GET /api/products` returns the 3 seed products (ids 1–3) |
| AC-02 | Anonymous `POST` of `{name:"Laptop Stand", price:1599.00, quantity:7}` returns 201 with id 4 and `Location: /api/products/4` |
| AC-03 | Anonymous `PUT /api/products/1` (FR-PROD-03 example) returns 200, and a later `GET` shows the new values |
| AC-04 | `DELETE /api/products/2` returns 401 anonymous, 403 as `user`, then 204 as `admin`, after which `GET /api/products/2` returns 404 |
| AC-05 | Every error case in the edge-case table returns ProblemDetail JSON |
| AC-06 | `./mvnw test` passes from the repo root |
| AC-07 | Registered in Claude Code, the MCP server lists 5 tools, and `list_products` returns the seed products from the running backend |
| AC-08 | With admin credentials configured, `delete_product` on id 3 succeeds; with user credentials it returns a 403 tool error |

## Decision log

| ID | Question | Decision |
|----|----------|----------|
| D-01 | Stack | Spring Boot 3.5, Java 21, Maven wrapper, multi-module |
| D-02 | Storage | H2 in-memory + Spring Data JPA |
| D-03 | Auth mechanism | HTTP Basic, in-memory users `admin` (ADMIN) and `user` (USER) |
| D-04 | Product fields | name, description, price, quantity (+ generated id) |
| D-05 | Read endpoints | Both list-all and get-by-id, so 5 endpoints in total |
| D-06 | MCP server form | Separate Spring AI MCP server module, stdio, calls the backend via `RestClient` |
| D-07 | Error format | RFC 7807 ProblemDetail + `errors` map for validation |
| D-08 | Duplicate names | Allowed |
| D-09 | PUT semantics | Full replace, same validation as create, 404 on an unknown id (no upsert) |
| D-10 | Seed data | 3 products via `data.sql` at startup |
| D-11 | MCP tools and credentials | 5 tools; Basic credentials from env/config; backend errors returned as tool errors |
| D-12 | *(decided without asking)* Wrong credentials on an open endpoint | 401 (Spring Security default) |
| D-13 | *(decided without asking)* USER deleting an unknown id | 403, because authorization is checked first |
| D-14 | *(decided without asking)* Price precision | Max 10 integer digits, 2 decimals |
| D-15 | *(decided without asking)* List order and POST response | Ordered by id ascending; POST returns 201 + `Location` |
| D-16 | *(decided without asking)* Session and CSRF | Stateless, CSRF disabled |
