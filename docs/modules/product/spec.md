# product: Specification
Status: approved (session 02, 2026-11-25)
Implements: FR-IN-01..04, FR-API-01..05 (non-security part), FR-PROD-01..05, FR-ERR-01..04, NFR-01..03, NFR-05, EC-01..14, EC-20, AC-01..03, AC-05

## 1. Responsibility

The `product` module is the Product catalogue inside `product-backend`. It covers:

- the `Product` JPA entity and its H2 table, with the seed data from `data.sql`
- `ProductRepository` (Spring Data JPA)
- `ProductService`, which holds the business rules (create, list, get, full-replace update, delete)
- `ProductController`, which serves the 5 endpoints under `/api/products`
- request and response DTOs, request normalisation (trimming), Bean Validation and strict JSON typing
- `ProblemDetail` error responses for 400, 404, 405, 415 and 500

It does **not** cover authentication or authorization. Who may call `DELETE`, the 401 and 403 responses, CSRF and
the session policy all belong to the `security` module. Until that module exists, `DELETE` is open to anyone.
This spec only defines what happens once a delete request reaches the controller.

## 2. Public interface

Base package: `com.aieng.productbackend.product`. Application class: `com.aieng.productbackend.ProductBackendApplication`.

```java
// JPA entity (never returned by the controller, see NFR-05)
@Entity @Table(name = "product")
class Product {
    Long id;              // @Id @GeneratedValue(strategy = IDENTITY)
    String name;          // NOT NULL, length 100
    String description;   // nullable, length 500
    BigDecimal price;     // NOT NULL, DECIMAL(12,2)
    Integer quantity;     // NOT NULL
}

interface ProductRepository extends JpaRepository<Product, Long> {}

// Request DTO, used by both POST and PUT
record ProductRequest(String name, String description, BigDecimal price, Integer quantity) {
    // The compact constructor trims name and description. A description that is blank after trimming becomes null.
}

// Response DTO
record ProductResponse(Long id, String name, String description, BigDecimal price, Integer quantity) {
    static ProductResponse from(Product p);
}

class ProductService {
    ProductResponse create(ProductRequest request);
    List<ProductResponse> findAll();                         // ordered by id ascending
    ProductResponse findById(Long id);                       // throws ProductNotFoundException
    ProductResponse update(Long id, ProductRequest request); // throws ProductNotFoundException
    void delete(Long id);                                    // throws ProductNotFoundException
}

class ProductNotFoundException extends RuntimeException {
    ProductNotFoundException(Long id);   // message: "Product with id <id> not found"
}

@RestController @RequestMapping("/api/products")
class ProductController { /* 5 handlers, see PRODUCT-S01..S05 */ }

@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler { /* see section 6 */ }
```

## 3. Behaviour rules

Seed products (FR-PROD-05), used in the examples below:

| id | name | description | price | quantity |
|----|------|-------------|-------|----------|
| 1 | Wireless Mouse | 2.4 GHz wireless mouse, black | 799.00 | 25 |
| 2 | Mechanical Keyboard | Blue switches, full size | 2499.00 | 10 |
| 3 | USB-C Hub | 6-in-1 hub with HDMI | 1299.50 | 0 |

| ID | Rule | Requirement(s) | Example (input → result) |
|----|------|----------------|--------------------------|
| PRODUCT-S01 | `POST /api/products` with a valid body creates a product and returns **201**. The body is the created `ProductResponse` and the header is `Location: /api/products/{id}` (a relative path). | FR-API-01, AC-02 | `POST {name:"Laptop Stand", price:1599.00, quantity:7}` on fresh data → 201, `{id:4, name:"Laptop Stand", description:null, price:1599.00, quantity:7}`, `Location: /api/products/4` |
| PRODUCT-S02 | `GET /api/products` returns **200** with a JSON array of every product, ordered by `id` ascending. If no products exist it returns `[]`. | FR-API-02, AC-01, EC-20 | Fresh start → 200, `[{id:1,…},{id:2,…},{id:3,…}]` |
| PRODUCT-S03 | `GET /api/products/{id}` returns **200** with the product. | FR-API-03 | `GET /api/products/3` → 200, `{id:3, name:"USB-C Hub", description:"6-in-1 hub with HDMI", price:1299.50, quantity:0}` |
| PRODUCT-S04 | `PUT /api/products/{id}` with a valid body replaces `name`, `description`, `price` and `quantity`, then returns **200** with the updated product. A missing `description` is stored as `null`. The `id` does not change. | FR-API-04, FR-PROD-03, AC-03, EC-13 | `PUT /api/products/1 {name:"Wireless Mouse Pro", price:899.00, quantity:20}` → 200, `{id:1, name:"Wireless Mouse Pro", description:null, price:899.00, quantity:20}`. A later `GET /api/products/1` returns the same values. |
| PRODUCT-S05 | `DELETE /api/products/{id}` on an existing product removes it and returns **204** with no body. Afterwards the product is missing from the list and `GET` on its id returns 404. | FR-API-05, FR-PROD-04 | `DELETE /api/products/2` → 204. `GET /api/products/2` → 404, and the list shows ids 1 and 3. |
| PRODUCT-S06 | The server generates ids with auto-increment, and the seed rows take ids 1–3, so the first product created after a fresh start gets id **4**. Ids are never reused after a delete. | FR-PROD-01, FR-PROD-04, FR-PROD-05 | Delete id 3, then `POST` a new product → id 4 (not 3). A second `POST` → id 5. |
| PRODUCT-S07 | The server ignores any `id` in a request body. On `POST` it generates the id. On `PUT` the path id wins. Unknown JSON fields are ignored. | FR-IN-02, EC-14 | `POST {id:50, name:"Pen", price:10, quantity:1, color:"red"}` → 201 with id 4. `PUT /api/products/1 {id:99, name:"Pen", price:10, quantity:1}` → 200 with id 1, and no product 99 exists. |
| PRODUCT-S08 | Duplicate names are allowed. | FR-PROD-02, EC-08 | `POST {name:"Wireless Mouse", price:799.00, quantity:1}` → 201 with id 4. Product 1 is unchanged. |
| PRODUCT-S09 | **Normalisation** runs before validation: `name` and `description` are trimmed of leading and trailing whitespace. A `description` that is empty after trimming is stored as `null`. Length limits apply to the trimmed value. | FR-IN-02 (Q2) | `POST {name:"  Pen  ", description:"   ", price:10, quantity:1}` → 201, `{name:"Pen", description:null, …}`. A name of 100 `a` characters wrapped in 2 spaces on each side is valid. |
| PRODUCT-S10 | **Validation** (on POST and PUT): `name` must not be blank (`@NotBlank`) and is at most 100 characters (`@Size(max=100)`). `description` is at most 500 characters. `price` is not null, greater than 0 (`@DecimalMin(value="0", inclusive=false)`) and has at most 10 integer digits and 2 decimals (`@Digits(integer=10, fraction=2)`). `quantity` is not null and at least 0 (`@Min(0)`). | FR-IN-02, EC-01..04, EC-06, EC-07 | `{name:"Pen", price:0, quantity:5}` → 400, `errors.price = "must be greater than 0"`. `{name:"Pen", price:10, quantity:0}` → 201. |
| PRODUCT-S11 | A validation failure returns **400** ProblemDetail with title `Validation failed` and detail `Request has invalid fields`. It also has an `errors` object that maps **every** invalid field to its message (the default Bean Validation message). If a field breaks more than one constraint, only one message is reported for it. Nothing is saved. | FR-ERR-02, EC-05 | `{name:"", price:-1, quantity:-1}` → 400, `errors` has the keys `name`, `price` and `quantity`. |
| PRODUCT-S12 | **Strict JSON typing:** Jackson does not coerce types. `quantity` must be a JSON integer, so `1.5`, `"5"` and `true` are rejected. `price` must be a JSON number, so `"10.00"` is rejected. Either case returns **400** ProblemDetail with title `Validation failed` and an `errors` entry for the bad field (`"must be an integer"` or `"must be a number"`). Jackson stops at the first type error, so only that field is reported. | FR-IN-02, FR-ERR-02 (Q3) | `{name:"Pen", price:10, quantity:1.5}` → 400, `errors.quantity = "must be an integer"`. `{name:"Pen", price:"10.00", quantity:1}` → 400, `errors.price = "must be a number"`. |
| PRODUCT-S13 | `price` is always returned with 2 decimals. | FR-IN-03 | `POST {name:"Pen", price:10, quantity:1}` → response `price: 10.00` |
| PRODUCT-S14 | An `{id}` that does not exist returns **404** ProblemDetail with title `Not Found` and detail `Product with id <id> not found`. This applies to GET, PUT and DELETE. Zero and negative ids are treated as unknown ids and return 404. A PUT on an unknown id creates nothing. | FR-API-03..05, FR-ERR-03, FR-PROD-03, EC-09, EC-12, EC-17 (Q1) | `GET /api/products/999` → 404 `Product with id 999 not found`. `GET /api/products/0` → 404 `Product with id 0 not found`. A valid `PUT /api/products/999` → 404, and the list still has 3 items. |
| PRODUCT-S15 | A non-numeric (or out-of-Long-range) `{id}` returns **400** ProblemDetail with title `Bad Request` and detail `Invalid value 'abc' for parameter 'id'`. | FR-IN-04, FR-ERR-04, EC-10 | `GET /api/products/abc` → 400 |
| PRODUCT-S16 | A malformed JSON body or a missing body on POST/PUT returns **400** ProblemDetail with title `Malformed request` and detail `Request body is missing or not valid JSON`. | FR-ERR-04, EC-11 | `POST` with body `{"name": "Pen",` → 400 |
| PRODUCT-S17 | Validation runs before the existence check on PUT, so an invalid body sent to an unknown id returns 400, not 404. | FR-PROD-03 | `PUT /api/products/999 {name:"", price:10, quantity:1}` → 400 |
| PRODUCT-S18 | Every error response from this module has `Content-Type: application/problem+json` and the fields `type` (`about:blank`), `title`, `status`, `detail` and `instance` (the request path). | FR-IN-01, FR-ERR-01, AC-05 | The 404 above has `instance: "/api/products/999"` |
| PRODUCT-S19 | Any unexpected exception returns **500** ProblemDetail with title `Internal Server Error` and detail `Unexpected error`. No stack trace or exception message is sent to the client, but the error is logged on the server. | FR-ERR-01 | A repository failure → 500 with the generic detail |

## 4. Data shapes

**Table `product`** (created by Hibernate `ddl-auto=create-drop`; `data.sql` runs after it):

| Column | Type | Null | Notes |
|--------|------|------|-------|
| `id` | BIGINT IDENTITY | no | PK. The seed rows are inserted **without** an explicit id, so the identity sequence continues at 4. |
| `name` | VARCHAR(100) | no | trimmed |
| `description` | VARCHAR(500) | yes | trimmed. Blank values are stored as null. |
| `price` | DECIMAL(12,2) | no | > 0 |
| `quantity` | INTEGER | no | >= 0 |

**ProductRequest JSON:**

| Field | JSON type | Required | Notes |
|-------|-----------|----------|-------|
| `name` | string | yes | trimmed, 1–100 chars after trimming |
| `description` | string or null | no | trimmed, max 500 chars. Blank becomes null. |
| `price` | number | yes | > 0, up to 10 integer digits and 2 decimals. Strings are rejected. |
| `quantity` | integer | yes | >= 0. Fractions, strings and booleans are rejected. |

**ProductResponse JSON:** `{ "id": number, "name": string, "description": string|null, "price": number (2 decimals), "quantity": number }`.
`description` is always present and is `null` when there is no value.

**Validation ProblemDetail:**
```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request has invalid fields",
  "instance": "/api/products",
  "errors": { "name": "must not be blank", "price": "must be greater than 0", "quantity": "must be greater than or equal to 0" }
}
```

## 5. Edge cases

| Case | Behaviour | Sample data | Rule |
|------|-----------|-------------|------|
| EC-01: price 0 | 400, `errors.price` | `{name:"Pen", price:0, quantity:5}` | S10, S11 |
| EC-02: negative quantity | 400, `errors.quantity` | `{name:"Pen", price:10, quantity:-1}` | S10, S11 |
| EC-03: blank name | 400, `errors.name` | `{name:"   ", price:10, quantity:1}` | S09, S10 |
| EC-04: name of 101 chars | 400, `errors.name` | `"a"` × 101 | S10 |
| EC-05: several invalid fields | 400, all of them in `errors` | `{name:"", price:-1, quantity:-1}` | S11 |
| EC-06: price with 3 decimals | 400, `errors.price` | `{name:"Pen", price:10.555, quantity:1}` | S10 |
| EC-07: quantity 0 | 201 | `{name:"Pen", price:10, quantity:0}` | S10 |
| EC-08: duplicate name | 201, id 4 | `{name:"Wireless Mouse", price:799.00, quantity:1}` | S08 |
| EC-09: unknown id | 404 | `GET /api/products/999` | S14 |
| EC-10: non-numeric id | 400 | `GET /api/products/abc` | S15 |
| EC-11: malformed JSON | 400 | `{"name": "Pen",` | S16 |
| EC-12: PUT unknown id | 404, nothing created | `PUT /api/products/999` with a valid body | S14 |
| EC-13: PUT without description | description becomes null | FR-PROD-03 example on id 1 | S04 |
| EC-14: id in body | ignored | `POST {id:50, …}` → id 4 | S07 |
| EC-20: list after deleting everything | 200 `[]` | delete ids 1–3 | S02, S05 |
| Zero or negative id | 404 | `GET /api/products/0`, `GET /api/products/-5` | S14 |
| Padded name, blank description | trimmed; description null | `{name:"  Pen  ", description:"   ", …}` | S09 |
| quantity 1.5 / price "10.00" | 400 | see S12 | S12 |
| price 10 integer digits | 201 | `price: 9999999999.99` | S10 |
| price 11 integer digits | 400 | `price: 10000000000` | S10 |
| description of 501 chars | 400, `errors.description` | `"a"` × 501 | S10 |
| null price or quantity | 400, `errors.price` / `errors.quantity` = `must not be null` | `{name:"Pen", price:null, quantity:null}` | S10 |
| invalid body on unknown id | 400 | `PUT /api/products/999 {name:""…}` | S17 |
| PATCH / unsupported method | 405 ProblemDetail (Spring default) | `PATCH /api/products/1` | S18 |
| Wrong Content-Type | 415 ProblemDetail (Spring default) | `POST` with `text/plain` | S18 |

## 6. Errors

| Trigger | Exception | Status | title | detail | Extra |
|---------|-----------|--------|-------|--------|-------|
| Bean Validation failure | `MethodArgumentNotValidException` | 400 | `Validation failed` | `Request has invalid fields` | `errors` map |
| Wrong JSON type for a field | `HttpMessageNotReadableException` caused by `MismatchedInputException` / `InvalidFormatException` | 400 | `Validation failed` | `Request has invalid fields` | `errors` map with one field |
| Malformed or missing body | `HttpMessageNotReadableException` (other causes) | 400 | `Malformed request` | `Request body is missing or not valid JSON` | none |
| Non-numeric path id | `MethodArgumentTypeMismatchException` | 400 | `Bad Request` | `Invalid value '<v>' for parameter 'id'` | none |
| Unknown id | `ProductNotFoundException` | 404 | `Not Found` | `Product with id <id> not found` | none |
| Unsupported method / media type | Spring MVC built-ins | 405 / 415 | Spring default | Spring default | none |
| Anything else | `Exception` | 500 | `Internal Server Error` | `Unexpected error` | logged server-side |

Guarantees: the module never returns an HTML error page or a stack trace, and failed requests make no partial writes.
Strict typing comes from setting `spring.jackson.mapper.allow-coercion-of-scalars=false` and
`spring.jackson.deserialization.accept-float-as-int=false` (the plan confirms the exact mechanism).

## 7. Out of scope

- Authentication, authorization, 401/403, CSRF and the session policy (`security` module)
- The MCP server (`mcp-server` module)
- Pagination, search, filtering, sorting parameters and PATCH (requirements §1)
- Optimistic locking or concurrency control
- The H2 console

## 8. Decision log

| ID | Question | Decision |
|----|----------|----------|
| PD-01 | Q1: response for a zero or negative path id | 404 like any unknown id; no extra validation |
| PD-02 | Q2: whitespace in `name`/`description` | Trim both before validation. A blank description becomes null. Length is checked on the trimmed value. |
| PD-03 | Q3: Jackson type coercion | Strict. `quantity:1.5`, `"5"` and `price:"10.00"` all return 400 with an `errors` entry. |
| PD-04 | *(decided without asking)* Base package | `com.aieng.productbackend.product` |
| PD-05 | *(decided without asking)* `Location` header form | Relative path `/api/products/{id}`, as written in AC-02 |
| PD-06 | *(decided without asking)* PUT with an invalid body on an unknown id | 400 (validation before lookup) |
| PD-07 | *(decided without asking)* Error titles/details for 400 malformed, 400 bad id, 404 and 500 | As listed in section 6 |
| PD-08 | *(decided without asking)* Validation messages | Bean Validation defaults; one message per field |
| PD-09 | *(decided without asking)* Price output scale | Always 2 decimals (`DECIMAL(12,2)`) |
| PD-10 | *(decided without asking)* Seed insertion | `data.sql` inserts without explicit ids so IDENTITY continues at 4 |
| PD-11 | *(decided without asking)* 500 handling | Generic ProblemDetail, no exception details leaked, logged server-side |
| PD-12 | *(decided without asking)* DELETE before the security module | Open to anyone; the `security` module adds the ADMIN rule |
