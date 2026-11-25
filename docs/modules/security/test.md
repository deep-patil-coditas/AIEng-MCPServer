# security: Test cases
Status: approved (session 06, 2026-11-25)
Derived from: `docs/modules/security/spec.md` (approved, commit `6308c07`)

## Test location and fixtures

All new tests live in `product-backend/src/test/java/com/aieng/productbackend/security/`.

| Class | Kind | Covers |
|-------|------|--------|
| `SecurityApiIntegrationTest` | `@SpringBootTest` + `@AutoConfigureMockMvc`, real filter chain, real H2 and seed data | T01–T07, T09–T24 |
| `SecurityMatrixIntegrationTest` | Same setup, one `@ParameterizedTest` over the spec §4 authorization matrix | T25 |
| `SecurityUsersTest` | `@SpringBootTest` (no MockMvc); autowires `UserDetailsService` and `PasswordEncoder` | T08 |

**Credentials:** every authenticated request sends a real `Authorization: Basic …` header built with
`SecurityMockMvcRequestPostProcessors.httpBasic(username, password)` from `spring-security-test`, so it goes through
`BasicAuthenticationFilter` and the real in-memory users. `@WithMockUser` is **not** used. "Anonymous" means no
`Authorization` header at all. Raw headers (`Basic !!!`, `Bearer abc`) are set with `.header("Authorization", …)`.

**CSRF:** no test adds `.with(csrf())`. Passing without a token is part of what S09 checks.

**State reset:** the same `@Sql(scripts = "/reset-products.sql", executionPhase = BEFORE_TEST_METHOD)` as the product
module, so every test (and every parameterized invocation of T25) starts with ids 1–3 and the next id is 4.

**Seed data:** 1 Wireless Mouse, 2 Mechanical Keyboard, 3 USB-C Hub (see `product/test.md`). Id 999 never exists.

**Callers** (shorthand used below):

| Shorthand | Header |
|-----------|--------|
| anon | none |
| user | `httpBasic("user", "user123")` |
| admin | `httpBasic("admin", "admin123")` |
| wrongpw | `httpBasic("admin", "wrong")` |

**Naming:** each test method starts with its ID, e.g. `void T10_anonymousDelete_returns401WithChallenge()`.
"401 challenge" means status 401, header `WWW-Authenticate: Basic realm="Realm"` and the 401 ProblemDetail from S07.
"Product N still exists" means a follow-up anonymous `GET /api/products/N` returns 200.

## Open endpoints (S01)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| SECURITY-T01 | S01 | Seed data | anon `GET /api/products` | 200, 3 items. No `WWW-Authenticate` header | seed |
| SECURITY-T02 | S01, S09 | Seed data | anon `POST /api/products`, no CSRF token | 201 with id 4, `Location: /api/products/4` | `{name:"Laptop Stand", price:1599.00, quantity:7}` (AC-02) |
| SECURITY-T03 | S01 | Seed data | user `PUT /api/products/1`, then admin `GET /api/products/1` | PUT 200 with `name:"Wireless Mouse Pro"`. GET 200 with the same values. Valid credentials change nothing on open endpoints | `{name:"Wireless Mouse Pro", price:899.00, quantity:20}` (FR-PROD-03) |
| SECURITY-T04 | S01 | none | anon `GET /foo` | 404 ProblemDetail (`title:"Not Found"`, `instance:"/foo"`), not 401 | |

## Admin delete (S02)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| SECURITY-T05 | S02, S04, S06 | Seed data | `DELETE /api/products/2` as anon, then as user, then as admin; then anon `GET /api/products/2` | 401, 403, 204 (empty body), then 404 | AC-04 |
| SECURITY-T06 | S02 | Seed data | admin `DELETE /api/products/999` | 404 ProblemDetail, detail `Product with id 999 not found`. List still has 3 items | EC-17 |
| SECURITY-T07 | S02 | Seed data | admin `DELETE /api/products/abc` | 400 ProblemDetail from the product module, detail `Invalid value 'abc' for parameter 'id'` | edge-case table |

## Users and passwords (S03)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| SECURITY-T08 | S03 | Application context | `userDetailsService.loadUserByUsername("admin")` and `("user")` | `admin`: password starts with `$2`, `passwordEncoder.matches("admin123", hash)` is true, authorities are exactly `[ROLE_ADMIN]`. `user`: password starts with `$2`, matches `user123`, authorities exactly `[ROLE_USER]`. `passwordEncoder` is a `BCryptPasswordEncoder`. Neither stored password contains `{noop}` | NFR-04 |
| SECURITY-T09 | S03, S05 | Seed data | `GET /api/products` with `httpBasic("admin", "ADMIN123")` | 401 challenge (passwords are case-sensitive) | |

## 401 and 403 (S04, S05, S06)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| SECURITY-T10 | S04 | Seed data | anon `DELETE /api/products/1` | 401 challenge. Product 1 still exists | EC-15 |
| SECURITY-T11 | S05 | Seed data | wrongpw `GET /api/products` | 401 challenge | EC-19 |
| SECURITY-T12 | S05 | Seed data | `GET /api/products` with `httpBasic("nobody", "x")` | 401 challenge | unknown user |
| SECURITY-T13 | S05 | Seed data | `POST /api/products` with `httpBasic("user", "wrong")` | 401 challenge. List still has 3 items (nothing created) | `{name:"Laptop Stand", price:1599.00, quantity:7}` |
| SECURITY-T14 | S05 | Seed data | wrongpw `DELETE /api/products/1` | 401 challenge. Product 1 still exists | |
| SECURITY-T15 | S05 | Seed data | `GET /api/products` with header `Authorization: Basic !!!` | 401 challenge | undecodable header |
| SECURITY-T16 | S06 | Seed data | user `DELETE /api/products/1` | 403. No `WWW-Authenticate` header. Product 1 still exists | EC-16 |

## Error bodies (S07)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| SECURITY-T17 | S07, S04, S05 | Seed data | (a) anon `DELETE /api/products/1`; (b) wrongpw `GET /api/products` | Both: `Content-Type: application/problem+json`, body exactly `{type:"about:blank", title:"Unauthorized", status:401, detail:"Authentication required", instance:<path>}` with instance `/api/products/1` and `/api/products`. Both details are identical (doesn't reveal which part was wrong) | |
| SECURITY-T18 | S07, S06 | Seed data | user `DELETE /api/products/1` | `Content-Type: application/problem+json`, body exactly `{type:"about:blank", title:"Forbidden", status:403, detail:"Access Denied", instance:"/api/products/1"}` | spec §4 |

## Authorization before lookup (S08)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| SECURITY-T19 | S08 | Seed data | user `DELETE /api/products/999` | 403 `Access Denied` (not 404) | EC-18 |
| SECURITY-T20 | S08 | Seed data | user `DELETE /api/products/abc` | 403 `Access Denied` (not 400) | |
| SECURITY-T21 | S08, S04 | Seed data | anon `DELETE /api/products/999` | 401 challenge (not 404) | |

## Stateless and CSRF (S09)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| SECURITY-T22 | S09 | Seed data | admin `DELETE /api/products/3`; user `GET /api/products` | 204 and 200. Neither response has a `Set-Cookie` header, and `result.getRequest().getSession(false)` is `null` for both | |
| SECURITY-T23 | S09 | Seed data | anon `PUT /api/products/1` without a CSRF token | 200 (CSRF disabled for PUT too; POST is covered by T02, DELETE by T05/T22) | `{name:"Pen", price:10, quantity:1}` |

## Non-Basic schemes (S10)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| SECURITY-T24 | S10 | Seed data | (a) `GET /api/products` and (b) `DELETE /api/products/1`, both with `Authorization: Bearer abc` | (a) 200 with 3 items. (b) 401 challenge, product 1 still exists | |

## Authorization matrix (spec §4)

| ID | Spec rule(s) | Given | When | Then | Data |
|----|--------------|-------|------|------|------|
| SECURITY-T25 | S01, S02, S04, S05, S06, S08 | Seed data (reset before each invocation) | Each of the 28 cells: 7 requests × 4 callers (anon, user, admin, wrongpw) | Status equals the matrix cell below. Display name `T25 [<method> <path> as <caller>] -> <status>` | `@MethodSource` table |

Matrix (copied from spec §4; POST/PUT bodies are `{name:"Laptop Stand", price:1599.00, quantity:7}`):

| Request | anon | user | admin | wrongpw |
|---------|------|------|-------|---------|
| `GET /api/products` | 200 | 200 | 200 | 401 |
| `GET /api/products/1` | 200 | 200 | 200 | 401 |
| `POST /api/products` | 201 | 201 | 201 | 401 |
| `PUT /api/products/1` | 200 | 200 | 200 | 401 |
| `DELETE /api/products/1` | 401 | 403 | 204 | 401 |
| `DELETE /api/products/999` | 401 | 403 | 404 | 401 |
| `GET /foo` | 404 | 404 | 404 | 401 |

## Changes to existing product tests

Adding Spring Security changes how some `product` tests must send requests. Their IDs and expected results stay the
same (spec SD-08).

| Test | Change |
|------|--------|
| PRODUCT-T04, T10, T11, T36 (`ProductApiIntegrationTest`) | Each `DELETE` adds `.with(httpBasic("admin", "admin123"))` |
| PRODUCT-T43 (`ProductControllerErrorTest`, `@WebMvcTest`) | Adds `@Import({SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class, ProblemDetailAccessDeniedHandler.class})`. Otherwise the slice uses Boot's default security and the anonymous `GET` returns 401 instead of 500 |

Every other product test stays anonymous and must keep passing unchanged, which also re-checks S01.

## Coverage

| Spec rule | Tests |
|-----------|-------|
| SECURITY-S01 | T01, T02, T03, T04, T25 |
| SECURITY-S02 | T05, T06, T07, T25 |
| SECURITY-S03 | T08, T09 |
| SECURITY-S04 | T05, T10, T17a, T21, T25 |
| SECURITY-S05 | T09, T11, T12, T13, T14, T15, T17b, T25 |
| SECURITY-S06 | T05, T16, T18, T25 |
| SECURITY-S07 | T17, T18 |
| SECURITY-S08 | T19, T20, T21, T25 |
| SECURITY-S09 | T02, T22, T23 |
| SECURITY-S10 | T24 |

Edge cases covered: EC-15 (T10), EC-16 (T16), EC-17 (T06), EC-18 (T19), EC-19 (T11).
Acceptance criteria: AC-04 (T05). AC-05 for the security errors (T17, T18).
Requirements: FR-IN-05 (T08, T24), FR-SEC-01 (T01–T04), FR-SEC-02 (T05, T10, T16), FR-SEC-03 (T11–T15),
FR-SEC-04 (T22, T23), FR-SEC-05 (T19–T21), FR-ERR-05 (T17, T18), NFR-04 (T08).

Fixture files the module touches: `src/test/resources/reset-products.sql` (reused, unchanged).

## Not tested here

- Spring Security's default response headers (`X-Content-Type-Options`, `Cache-Control`, `X-Frame-Options`): spec SD-07.
- Username case-insensitivity: spec SD-09.
- The MCP server sending credentials and mapping 401/403 to tool errors (EC-21, AC-08): `mcp-server` module's test.md.

## Decision log

| ID | Question | Decision |
|----|----------|----------|
| TD-01 | Q1: how tests send credentials | Real HTTP Basic headers (`httpBasic(...)`) through the real filter chain via `@SpringBootTest` + MockMvc; no `@WithMockUser`. Targeted tests per rule plus one parameterized test over all 28 matrix cells (T25) |
| TD-02 | *(decided without asking)* State isolation | Reuse `reset-products.sql` before every test method and every parameterized invocation, as in the product module |
| TD-03 | *(decided without asking)* Testing BCrypt (S03) | Context test on the `UserDetailsService` and `PasswordEncoder` beans, plus a wrong-case password over HTTP (T09) |
| TD-04 | *(decided without asking)* "Nothing deleted / created" checks | A follow-up anonymous `GET` (single product or list count) after every rejected write |
| TD-05 | *(decided without asking)* Exact body assertions | 401 and 403 bodies are asserted field by field with exact strings (T17, T18); other 401/403 tests check status, `WWW-Authenticate` presence and `detail` only |
| TD-06 | *(decided without asking)* Session check (S09) | No `Set-Cookie` header and `getSession(false) == null` on the MockMvc request |
| TD-07 | *(decided without asking)* `@WebMvcTest` slice (PRODUCT-T43) | Import the real security config and handlers into the slice rather than excluding security, so the slice matches production |
| TD-08 | *(decided without asking)* Test method naming | `T<NN>_<behaviour>()` in the `security` package; grouped cases use `T17a_…`/`T17b_…`, `T24a_…`/`T24b_…` |
