# product: Test cases
Status: approved (session 03, 2026-11-25)
Derived from: `docs/modules/product/spec.md` (approved, commit `3cce158`)

## Test location and fixtures

All tests live in `product-backend/src/test/java/com/aieng/productbackend/product/`.

| Class | Kind | Covers |
|-------|------|--------|
| `ProductApiIntegrationTest` | `@SpringBootTest` + `@AutoConfigureMockMvc`, real H2 and seed data | T01–T38 |
| `ProductRequestTest` | plain JUnit 5 unit test | T39–T42 |
| `ProductControllerErrorTest` | `@WebMvcTest(ProductController.class)` with a mocked `ProductService` (`@MockitoBean`) | T43 |

**State reset:** before every integration test, `@Sql(scripts = "/reset-products.sql", executionPhase = BEFORE_TEST_METHOD)`
runs. It truncates `product`, restarts the identity at 1 and re-inserts the 3 seed rows, so every test starts with
ids 1–3 and the next generated id is 4. (`@Transactional` rollback can't be used because identity values aren't rolled back.)

**Seed data** (FR-PROD-05):

| id | name | description | price | quantity |
|----|------|-------------|-------|----------|
| 1 | Wireless Mouse | 2.4 GHz wireless mouse, black | 799.00 | 25 |
| 2 | Mechanical Keyboard | Blue switches, full size | 2499.00 | 10 |
| 3 | USB-C Hub | 6-in-1 hub with HDMI | 1299.50 | 0 |

**Naming:** each test method starts with its ID, e.g. `void T01_createValidProduct_returns201WithLocation()`.
JSON bodies below use a shorthand; the tests send real JSON with `Content-Type: application/json`.
"ProblemDetail" means `Content-Type: application/problem+json`. Prices are compared as JSON numbers with 2 decimals.

## Happy paths

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| PRODUCT-T01 | S01, S06 | Seed data | `POST /api/products` | 201. `Location: /api/products/4`. Body `{id:4, name:"Laptop Stand", description:null, price:1599.00, quantity:7}` | `{name:"Laptop Stand", price:1599.00, quantity:7}` (AC-02) |
| PRODUCT-T02 | S01 | Seed data | `POST` with a description, then `GET /api/products/4` | 201, then 200 with the same body, including `description:"Adjustable aluminium"` | `{name:"Laptop Stand", description:"Adjustable aluminium", price:1599.00, quantity:7}` |
| PRODUCT-T03 | S02 | Seed data | `GET /api/products` | 200. A 3-item array with ids `[1,2,3]` in that order, every field matching the seed table | seed (AC-01) |
| PRODUCT-T04 | S02, S05 | Seed data | `DELETE` ids 1, 2 and 3, then `GET /api/products` | Each delete returns 204. The list is 200 `[]` | EC-20 |
| PRODUCT-T05 | S02 | Seed data plus `POST` "Laptop Stand" (id 4) | `GET /api/products` | 4 items ordered `[1,2,3,4]` | |
| PRODUCT-T06 | S03 | Seed data | `GET /api/products/3` | 200 `{id:3, name:"USB-C Hub", description:"6-in-1 hub with HDMI", price:1299.50, quantity:0}` | seed id 3 |
| PRODUCT-T07 | S04 | Seed data | `PUT /api/products/1` | 200 `{id:1, name:"Wireless Mouse Pro", description:null, price:899.00, quantity:20}` | `{name:"Wireless Mouse Pro", price:899.00, quantity:20}` (FR-PROD-03, EC-13) |
| PRODUCT-T08 | S04 | After T07's PUT | `GET /api/products/1` | 200 with the updated values (AC-03). The list still has 3 items | |
| PRODUCT-T09 | S04 | Seed data | `PUT /api/products/2` with all 4 fields | 200, all 4 fields replaced, id stays 2 | `{name:"Keyboard TKL", description:"Red switches", price:1999.99, quantity:3}` |
| PRODUCT-T10 | S05 | Seed data | `DELETE /api/products/2` | 204 with an empty body. `GET /api/products/2` returns 404. The list ids are `[1,3]` | (AC-04, admin part) |
| PRODUCT-T11 | S06 | Seed data | `DELETE /api/products/3`, then `POST` twice | The two new products get ids 4 and 5. Id 3 is not reused | `{name:"Pen", price:10, quantity:1}` ×2 |
| PRODUCT-T12 | S07 | Seed data | `POST` with `id:50` and an unknown field `color` | 201 with id 4. `GET /api/products/50` returns 404 | `{id:50, name:"Pen", price:10, quantity:1, color:"red"}` (EC-14) |
| PRODUCT-T13 | S07 | Seed data | `PUT /api/products/1` with `id:99` in the body | 200 with id 1. `GET /api/products/99` returns 404. The list has 3 items | `{id:99, name:"Pen", price:10, quantity:1}` |
| PRODUCT-T14 | S08 | Seed data | `POST` a duplicate name | 201 with id 4. Product 1 is unchanged | `{name:"Wireless Mouse", price:799.00, quantity:1}` (EC-08) |
| PRODUCT-T15 | S13 | Seed data | `POST` with an integer price | Response `price` is `10.00` (2 decimals) | `{name:"Pen", price:10, quantity:1}` |

## Normalisation and validation

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| PRODUCT-T16 | S09 | Seed data | `POST` | 201 with `name:"Pen"` and `description:null` | `{name:"  Pen  ", description:"   ", price:10, quantity:1}` |
| PRODUCT-T17 | S09, S10 | Seed data | `POST` a name of 100 `a` characters with 2 spaces on each side (104 chars raw) | 201, stored name is the 100 `a` characters | `"  " + "a"×100 + "  "` |
| PRODUCT-T18 | S09, S04 | Seed data | `PUT /api/products/1` with a padded name and description | 200 with `name:"Mouse"` and `description:"Black"` | `{name:" Mouse ", description:" Black ", price:10, quantity:1}` |
| PRODUCT-T19 | S10, S11 | Seed data | `POST` with price 0 | 400 ProblemDetail. `errors.price = "must be greater than 0"`. The list still has 3 items | `{name:"Pen", price:0, quantity:5}` (EC-01) |
| PRODUCT-T20 | S10, S11 | Seed data | `POST` with a negative quantity | 400. `errors.quantity = "must be greater than or equal to 0"` | `{name:"Pen", price:10, quantity:-1}` (EC-02) |
| PRODUCT-T21 | S09, S10 | Seed data | `POST` with a whitespace-only name | 400. `errors.name = "must not be blank"` | `{name:"   ", price:10, quantity:1}` (EC-03) |
| PRODUCT-T22 | S10 | Seed data | `POST` with a 101-character name | 400. `errors` contains the key `name` | `"a"×101` (EC-04) |
| PRODUCT-T23 | S10 | Seed data | `POST` with a 501-character description | 400. `errors` contains the key `description` | `"a"×501` |
| PRODUCT-T24 | S10 | Seed data | `POST` with a 500-character description | 201 | `"a"×500` |
| PRODUCT-T25 | S10 | Seed data | `POST` with price 10.555 | 400. `errors` contains the key `price` | `{name:"Pen", price:10.555, quantity:1}` (EC-06) |
| PRODUCT-T26 | S10 | Seed data | `POST` with the largest allowed price, then with 11 integer digits | First 201 with `price: 9999999999.99`. Second 400 with `errors` containing `price` | `9999999999.99` / `10000000000` |
| PRODUCT-T27 | S10 | Seed data | `POST` with quantity 0 | 201 with `quantity:0` | `{name:"Pen", price:10, quantity:0}` (EC-07) |
| PRODUCT-T28 | S10, S11 | Seed data | `POST` with null or missing price and quantity | 400. `errors.price = "must not be null"` and `errors.quantity = "must not be null"` | `{name:"Pen", price:null}` |
| PRODUCT-T29 | S11 | Seed data | `POST` with several invalid fields | 400. Title `Validation failed`, detail `Request has invalid fields`. `errors` has exactly the keys `name`, `price` and `quantity`. The list still has 3 items | `{name:"", price:-1, quantity:-1}` (EC-05) |
| PRODUCT-T30 | S10, S11 | Seed data | `PUT /api/products/1` with an invalid body | 400 with `errors.price`. `GET /api/products/1` is unchanged | `{name:"Pen", price:0, quantity:1}` |
| PRODUCT-T31 | S12 | Seed data | `POST` with a non-integer quantity in three variants | Each returns 400, title `Validation failed`, `errors.quantity = "must be an integer"` | `quantity: 1.5` / `"5"` / `true` (with `name:"Pen", price:10`) |
| PRODUCT-T32 | S12 | Seed data | `POST` with a string price | 400. `errors.price = "must be a number"` | `{name:"Pen", price:"10.00", quantity:1}` |

## Not found and request errors

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| PRODUCT-T33 | S14, S18 | Seed data | `GET /api/products/999` | 404 ProblemDetail: `type:"about:blank"`, `title:"Not Found"`, `status:404`, `detail:"Product with id 999 not found"`, `instance:"/api/products/999"` | EC-09 |
| PRODUCT-T34 | S14 | Seed data | `GET /api/products/0`, then `GET /api/products/-5` | 404 with details `Product with id 0 not found` and `Product with id -5 not found` | |
| PRODUCT-T35 | S14 | Seed data | Valid `PUT /api/products/999` | 404 `Product with id 999 not found`. The list has 3 items and none has id 999 | `{name:"Pen", price:10, quantity:1}` (EC-12) |
| PRODUCT-T36 | S14 | Seed data | `DELETE /api/products/999` | 404 `Product with id 999 not found`. The list has 3 items | (EC-17, ignoring auth) |
| PRODUCT-T37 | S15, S16, S17, S18 | Seed data | Four bad requests: (a) `GET /api/products/abc`; (b) `POST` with body `{"name": "Pen",`; (c) `POST` with no body; (d) `PUT /api/products/999` with `{name:"", price:10, quantity:1}` | (a) 400 `Bad Request`, detail `Invalid value 'abc' for parameter 'id'`. (b) and (c) 400 `Malformed request`, detail `Request body is missing or not valid JSON`. (d) 400 `Validation failed` with `errors.name` (not 404). All return ProblemDetail with `instance` set to the request path | EC-10, EC-11 |
| PRODUCT-T38 | S18 | Seed data | `PATCH /api/products/1`; `POST` with `Content-Type: text/plain` | 405 and 415, each with an `application/problem+json` body | |

## Unit tests: `ProductRequest` normalisation

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| PRODUCT-T39 | S09 | none | `new ProductRequest("  Pen  ", "  Blue ink  ", 10, 1)` | `name()` is `"Pen"` and `description()` is `"Blue ink"` | |
| PRODUCT-T40 | S09 | none | `new ProductRequest("Pen", "   ", 10, 1)` | `description()` is `null` | |
| PRODUCT-T41 | S09 | none | `new ProductRequest("Pen", null, 10, 1)` | `description()` is `null` (no NPE) | |
| PRODUCT-T42 | S09 | none | `new ProductRequest(null, null, null, null)` | All fields are `null` (no NPE; validation reports them later) | |

## Server error

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| PRODUCT-T43 | S19 | `ProductService.findAll()` mocked to throw `RuntimeException("db exploded")` | `GET /api/products` | 500 ProblemDetail, title `Internal Server Error`, detail `Unexpected error`. The body does not contain `db exploded` | `@WebMvcTest` |

## Coverage

| Spec rule | Tests |
|-----------|-------|
| PRODUCT-S01 | T01, T02 |
| PRODUCT-S02 | T03, T04, T05 |
| PRODUCT-S03 | T06 |
| PRODUCT-S04 | T07, T08, T09, T18 |
| PRODUCT-S05 | T04, T10 |
| PRODUCT-S06 | T01, T11 |
| PRODUCT-S07 | T12, T13 |
| PRODUCT-S08 | T14 |
| PRODUCT-S09 | T16, T17, T18, T21, T39–T42 |
| PRODUCT-S10 | T17, T19–T28, T30 |
| PRODUCT-S11 | T19, T20, T28, T29, T30 |
| PRODUCT-S12 | T31, T32 |
| PRODUCT-S13 | T15 |
| PRODUCT-S14 | T33, T34, T35, T36 |
| PRODUCT-S15 | T37a |
| PRODUCT-S16 | T37b, T37c |
| PRODUCT-S17 | T37d |
| PRODUCT-S18 | T33, T37, T38 |
| PRODUCT-S19 | T43 |

Edge cases covered: EC-01 (T19), EC-02 (T20), EC-03 (T21), EC-04 (T22), EC-05 (T29), EC-06 (T25), EC-07 (T27),
EC-08 (T14), EC-09 (T33), EC-10 (T37a), EC-11 (T37b), EC-12 (T35), EC-13 (T07), EC-14 (T12), EC-20 (T04).
Acceptance criteria: AC-01 (T03), AC-02 (T01), AC-03 (T07, T08). AC-05 is covered for this module's errors (T19–T38, T43).

Fixture files the module touches: `src/main/resources/data.sql` (seed) and `src/test/resources/reset-products.sql` (per-test reset).

## Not tested here

- 401/403, `WWW-Authenticate`, wrong credentials on open endpoints, authorization before lookup (EC-15..19, AC-04
  auth part): covered by the `security` module's test.md.
- MCP tools (EC-21, EC-22, AC-07, AC-08): covered by the `mcp-server` module's test.md.
- `./mvnw test` passing from the root (AC-06): checked at the end of each implementation session, not by a test.
- Fresh-start seeding by `data.sql` itself: the reset script re-seeds before each test. T03 also runs against the
  same seed values, and the plan keeps `data.sql` and `reset-products.sql` in sync.

## Decision log

| ID | Question | Decision |
|----|----------|----------|
| TD-01 | *(decided without asking)* Main test style | `@SpringBootTest` + MockMvc against real H2, because most rules are HTTP and persistence behaviour |
| TD-02 | *(decided without asking)* Isolation between tests | `@Sql` reset script before each test (truncate, restart identity, re-seed). No `@DirtiesContext` (slow) and no `@Transactional` (identity isn't rolled back) |
| TD-03 | *(decided without asking)* Testing the 500 path | `@WebMvcTest` with a mocked service throwing `RuntimeException` |
| TD-04 | *(decided without asking)* Test method naming | `T<NN>_<behaviour>()` in the `product` package; the `PRODUCT-` prefix is implied by the class |
| TD-05 | *(decided without asking)* Grouped request-error test | T37 covers 4 small cases (a–d), written as separate methods `T37a_…` to `T37d_…` |
| TD-06 | *(decided without asking)* Exact message assertions | Exact strings for messages the spec fixes. Only the key's presence for `@Size` / `@Digits` messages (default wording is verbose) |
