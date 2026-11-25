# security: Specification
Status: approved (session 05, 2026-11-25)
Implements: FR-IN-05, FR-API-05 (auth part), FR-SEC-01..05, FR-ERR-05, NFR-02, NFR-04, EC-15..19, AC-04

## 1. Responsibility

The `security` module adds Spring Security to `product-backend`. It covers:

- the `SecurityFilterChain`: HTTP Basic, stateless sessions, CSRF disabled
- the two in-memory users (`admin` / ADMIN, `user` / USER) with BCrypt-encoded passwords
- the authorization rule: only `DELETE /api/products/**` needs the `ADMIN` role; every other request is open
- ProblemDetail bodies for the 401 and 403 responses the security layer produces

It does **not** change any product behaviour. Once a request passes the filter chain, the `product` module
(PRODUCT-S01..S19) handles it exactly as before. It also doesn't cover how the MCP server sends credentials
(`mcp-server` module).

## 2. Public interface

Package: `com.aieng.productbackend.security`.

```java
@Configuration @EnableWebSecurity
class SecurityConfig {
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                  ProblemDetailAuthenticationEntryPoint entryPoint,
                                                  ProblemDetailAccessDeniedHandler accessDeniedHandler);
    @Bean PasswordEncoder passwordEncoder();            // BCryptPasswordEncoder
    @Bean UserDetailsService userDetailsService(PasswordEncoder encoder);
                                                        // InMemoryUserDetailsManager with admin and user
}

// Writes the 401 ProblemDetail (SECURITY-S04, S05)
@Component
class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {
    void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex);
}

// Writes the 403 ProblemDetail (SECURITY-S06)
@Component
class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {
    void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex);
}
```

Both handlers serialise a Spring `ProblemDetail` with the application's `ObjectMapper`.

## 3. Behaviour rules

Users (FR-IN-05):

| Username | Password | Role (authority) |
|----------|----------|------------------|
| `admin` | `admin123` | ADMIN (`ROLE_ADMIN`) |
| `user` | `user123` | USER (`ROLE_USER`) |

| ID | Rule | Requirement(s) | Example (input → result) |
|----|------|----------------|--------------------------|
| SECURITY-S01 | Every request that is not a `DELETE` under `/api/products/**` is **permitted to anonymous callers**. This includes `POST`, both `GET`s and `PUT` on `/api/products/**`, and any path outside `/api/products/**`, which reaches MVC and gets its normal response (e.g. 404 ProblemDetail). Valid credentials on an open endpoint are accepted and change nothing. | FR-SEC-01, (Q1) | Anonymous `GET /api/products` → 200 with 3 products. Anonymous `POST {name:"Laptop Stand", price:1599.00, quantity:7}` → 201, id 4. `user/user123` `PUT /api/products/1` (FR-PROD-03 example) → 200. Anonymous `GET /foo` → 404 ProblemDetail. |
| SECURITY-S02 | `DELETE /api/products/**` requires an authenticated user with role **ADMIN**. An admin's request reaches the controller and gets the `product` module's response. | FR-SEC-02, FR-API-05, AC-04 | `admin/admin123` `DELETE /api/products/2` → 204, then `GET /api/products/2` → 404. `admin/admin123` `DELETE /api/products/999` → 404 `Product with id 999 not found` (EC-17). |
| SECURITY-S03 | Authentication is **HTTP Basic** against the two in-memory users. Passwords are stored as BCrypt hashes, never `{noop}`. Passwords are case-sensitive. | FR-IN-05, NFR-04 | The `UserDetailsService` returns `admin` with a BCrypt hash (starts with `$2`), and `passwordEncoder().matches("admin123", hash)` is true. |
| SECURITY-S04 | An **anonymous** `DELETE /api/products/**` returns **401** with header `WWW-Authenticate: Basic realm="Realm"` and a ProblemDetail body (see S07). Nothing is deleted. | FR-SEC-02, FR-ERR-05, EC-15, AC-04 | Anonymous `DELETE /api/products/1` → 401, and `GET /api/products/1` still returns 200. |
| SECURITY-S05 | **Wrong credentials** (unknown user, wrong password or an undecodable Basic header) return **401** on **any** endpoint, open ones included, with the same header and body as S04. The request never reaches the controller. | FR-SEC-03, EC-19 | `GET /api/products` with `admin/wrong` → 401. `POST` with `nobody/x` → 401 and no product is created. `DELETE /api/products/1` with `admin/wrong` → 401 and product 1 still exists. |
| SECURITY-S06 | An authenticated **non-ADMIN** `DELETE /api/products/**` returns **403** with a ProblemDetail body (see S07). Nothing is deleted. | FR-SEC-02, FR-ERR-05, EC-16, AC-04 | `user/user123` `DELETE /api/products/1` → 403, and `GET /api/products/1` still returns 200. |
| SECURITY-S07 | **Error bodies.** 401 and 403 from the security layer have `Content-Type: application/problem+json` and the fields `type` (`about:blank`), `title`, `status`, `detail` and `instance` (the request path). 401: title `Unauthorized`, detail `Authentication required` (the same for anonymous and wrong credentials). 403: title `Forbidden`, detail `Access Denied`. | FR-ERR-05, FR-MCP-04, AC-05, (Q2) | `user/user123` `DELETE /api/products/1` → `{"type":"about:blank","title":"Forbidden","status":403,"detail":"Access Denied","instance":"/api/products/1"}` |
| SECURITY-S08 | **Authorization runs before the product lookup** (and before path-variable conversion), because it happens in the filter chain. A non-ADMIN gets 403 whether or not the id exists or is numeric. | FR-SEC-05, EC-18 | `user/user123` `DELETE /api/products/999` → 403 (not 404). `user/user123` `DELETE /api/products/abc` → 403 (not 400). Anonymous `DELETE /api/products/999` → 401. |
| SECURITY-S09 | The API is **stateless**: session creation policy `STATELESS`, so no `JSESSIONID` cookie is set and every request must carry its own credentials. **CSRF is disabled**, so `POST`, `PUT` and `DELETE` work without a CSRF token. | FR-SEC-04 | `admin/admin123` `DELETE /api/products/3` → 204 with no `Set-Cookie` header. Anonymous `POST` without a CSRF token → 201. |
| SECURITY-S10 | An `Authorization` header with a scheme other than Basic (e.g. `Bearer abc`) is ignored, and the request is treated as anonymous. | FR-IN-05 | `GET /api/products` with `Bearer abc` → 200. `DELETE /api/products/1` with `Bearer abc` → 401. |

## 4. Data shapes

**401 ProblemDetail** (`application/problem+json`, plus `WWW-Authenticate: Basic realm="Realm"`):
```json
{ "type": "about:blank", "title": "Unauthorized", "status": 401,
  "detail": "Authentication required", "instance": "/api/products/1" }
```

**403 ProblemDetail** (`application/problem+json`):
```json
{ "type": "about:blank", "title": "Forbidden", "status": 403,
  "detail": "Access Denied", "instance": "/api/products/1" }
```

**Authorization matrix** (seed data, product 1 exists, 999 does not):

| Request | Anonymous | `user/user123` | `admin/admin123` | Wrong password |
|---------|-----------|----------------|------------------|----------------|
| `GET /api/products` | 200 | 200 | 200 | 401 |
| `GET /api/products/1` | 200 | 200 | 200 | 401 |
| `POST /api/products` (valid) | 201 | 201 | 201 | 401 |
| `PUT /api/products/1` (valid) | 200 | 200 | 200 | 401 |
| `DELETE /api/products/1` | 401 | 403 | 204 | 401 |
| `DELETE /api/products/999` | 401 | 403 | 404 | 401 |
| `GET /foo` | 404 | 404 | 404 | 401 |

## 5. Edge cases

| Case | Behaviour | Sample data | Rule |
|------|-----------|-------------|------|
| EC-15: DELETE anonymous | 401 + `WWW-Authenticate`, product kept | `DELETE /api/products/1` | S04 |
| EC-16: DELETE as user | 403, product kept | `user/user123`, id 1 | S06 |
| EC-17: DELETE as admin, unknown id | 404 from the product module | `admin/admin123`, id 999 | S02 |
| EC-18: DELETE as user, unknown id | 403 | `user/user123`, id 999 | S08 |
| EC-19: wrong password on an open endpoint | 401 | `GET /api/products` with `admin/wrong` | S05 |
| Unknown user | 401 | `nobody/x` on `GET /api/products` | S05 |
| Wrong password on POST | 401, nothing created | `POST` with `user/wrong` | S05 |
| Non-numeric id as user | 403 | `user/user123` `DELETE /api/products/abc` | S08 |
| Non-numeric id as admin | 400 from the product module | `admin/admin123` `DELETE /api/products/abc` | S02 |
| Undecodable Basic header | 401 | `Authorization: Basic !!!` | S05 |
| Bearer token | ignored, treated as anonymous | `Authorization: Bearer abc` | S10 |
| Path outside `/api/products` | open, normal MVC response | anonymous `GET /foo` → 404 | S01 |
| Session cookie | none set | admin `DELETE` → no `Set-Cookie` | S09 |

## 6. Errors

| Trigger | Produced by | Status | title | detail | Headers |
|---------|-------------|--------|-------|--------|---------|
| Anonymous request to a protected path | `ProblemDetailAuthenticationEntryPoint` | 401 | `Unauthorized` | `Authentication required` | `WWW-Authenticate: Basic realm="Realm"` |
| Bad or undecodable Basic credentials, any path | `BasicAuthenticationFilter` → entry point | 401 | `Unauthorized` | `Authentication required` | `WWW-Authenticate: Basic realm="Realm"` |
| Authenticated without ADMIN on `DELETE /api/products/**` | `ProblemDetailAccessDeniedHandler` | 403 | `Forbidden` | `Access Denied` | none |

Guarantees: the security layer never returns an HTML page, a Spring Security default login form or a stack trace.
A 401 or 403 never reaches the controller, so it makes no writes. The response body never says which part of the
credentials was wrong.

## 7. Out of scope

- User registration, user management and changing passwords (requirements §1)
- JWT, OAuth, form login and remember-me
- Account lockout, rate limiting and audit logging of failed logins
- CORS configuration
- HTTPS (requirements §1)
- How the MCP server stores and sends credentials (`mcp-server` module)

## 8. Decision log

| ID | Question | Decision |
|----|----------|----------|
| SD-01 | Q1: security for paths outside `/api/products/**` | Permit all; only `DELETE /api/products/**` is protected. Unknown paths get the normal 404 ProblemDetail. |
| SD-02 | Q2: 401/403 ProblemDetail text | 401 `Unauthorized` / `Authentication required` (same for anonymous and bad credentials); 403 `Forbidden` / `Access Denied` |
| SD-03 | *(decided without asking)* Where the ADMIN rule lives | URL rule in the `SecurityFilterChain` (`requestMatchers(DELETE, "/api/products/**").hasRole("ADMIN")`), not `@PreAuthorize`. That gives auth-before-lookup (FR-SEC-05) without touching the product module. |
| SD-04 | *(decided without asking)* User definition | Hardcoded in `SecurityConfig` as in FR-IN-05, passwords encoded with BCrypt at startup. Not configurable via properties. |
| SD-05 | *(decided without asking)* Realm | Spring default `Realm`, so the header is `WWW-Authenticate: Basic realm="Realm"` |
| SD-06 | *(decided without asking)* Non-Basic `Authorization` schemes | Ignored; the request is anonymous (Spring default) |
| SD-07 | *(decided without asking)* Default security headers | Spring Security defaults stay on (`X-Content-Type-Options`, `Cache-Control`, `X-Frame-Options`); not tested |
| SD-08 | *(decided without asking)* Existing product tests | Product tests that call `DELETE` (PRODUCT-T04, T10, T11, T36) will send `admin/admin123` via `spring-security-test`. Their expected results do not change. |
| SD-09 | *(decided without asking)* Username case | Spring's `InMemoryUserDetailsManager` default: usernames are matched case-insensitively, passwords are case-sensitive. Not tested. |
