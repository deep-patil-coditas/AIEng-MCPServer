# security: Implementation plan
Status: approved (session 07, 2026-11-25)
Derived from: `docs/modules/security/spec.md` (approved, `6308c07`) and `docs/modules/security/test.md` (approved, `c371c28`)

This plan adds Spring Security to `product-backend`. It changes no product behaviour. The only product files it
touches are two test classes (test.md "Changes to existing product tests").

## 1. Classes / files

`…/security/` means `product-backend/src/main/java/com/aieng/productbackend/security/` for main code and
`product-backend/src/test/java/com/aieng/productbackend/security/` for tests.

**Build**

| Name | Location | Change | Spec rules |
|------|----------|--------|------------|
| `pom.xml` | `product-backend/` | Add `spring-boot-starter-security` (compile) and `spring-security-test` (test). Versions come from the Boot BOM | S01–S10 |

**Main code (`com.aieng.productbackend.security`)**

| Name | Location | Responsibility | Spec rules |
|------|----------|----------------|------------|
| `SecurityConfig` | `…/security/SecurityConfig.java` | `public` `@Configuration @EnableWebSecurity`. Three beans: `securityFilterChain`, `passwordEncoder` (`new BCryptPasswordEncoder()`), `userDetailsService` (`InMemoryUserDetailsManager` with `admin`/`admin123` role `ADMIN` and `user`/`user123` role `USER`, passwords run through `encoder.encode(...)` at startup). Filter chain below | S01–S06, S08–S10 |
| `ProblemDetailAuthenticationEntryPoint` | `…/security/ProblemDetailAuthenticationEntryPoint.java` | `public @Component`. Sets status 401 and header `WWW-Authenticate: Basic realm="Realm"`, then writes `ProblemDetail.forStatusAndDetail(UNAUTHORIZED, "Authentication required")` with title `Unauthorized` and `instance = URI.create(request.getRequestURI())` | S04, S05, S07 |
| `ProblemDetailAccessDeniedHandler` | `…/security/ProblemDetailAccessDeniedHandler.java` | `public @Component`. Sets status 403 and writes `ProblemDetail` with title `Forbidden`, detail `Access Denied` and the same `instance`. No `WWW-Authenticate` header | S06, S07 |

Both handlers get the application `ObjectMapper` by constructor injection, call
`response.setContentType("application/problem+json")` and write with `objectMapper.writeValue(response.getOutputStream(), problem)`.
They never call `response.sendError(...)`, so there is no dispatch to Boot's `/error` page (spec §6 guarantee).
A small package-private helper `ProblemResponses.write(response, objectMapper, problem)` holds the three shared lines.

**Filter chain (`securityFilterChain`)**

```java
http
    .csrf(csrf -> csrf.disable())                                            // S09
    .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))              // S09
    .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))          // S03, S05 (bad credentials)
    .exceptionHandling(e -> e
        .authenticationEntryPoint(entryPoint)                                // S04 (anonymous on DELETE)
        .accessDeniedHandler(accessDeniedHandler))                           // S06
    .authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN")   // S02, S08
        .anyRequest().permitAll());                                          // S01
```

Form login, logout and remember-me are not configured, so Spring adds none of them. Anonymous authentication stays
on (default), which is what lets `permitAll` work and sends anonymous `DELETE`s to the entry point instead of the
access-denied handler.

Why each rule holds without extra code:

| Rule | Mechanism |
|------|-----------|
| S05 wrong credentials on open endpoints | `BasicAuthenticationFilter` authenticates any `Basic` header before authorization runs, and on failure calls the entry point given to `httpBasic(...)`. This includes an undecodable header (`Basic !!!`), which the converter rejects with `BadCredentialsException` |
| S08 auth before lookup | The URL rule runs in `AuthorizationFilter`, before `DispatcherServlet`, so neither the product lookup nor `Long` conversion of `abc` happens |
| S09 no session | `STATELESS` makes Spring Security use `RequestAttributeSecurityContextRepository` and a `NullRequestCache`, so nothing calls `getSession(true)` |
| S10 Bearer ignored | `BasicAuthenticationConverter` returns `null` for non-`Basic` schemes; the request stays anonymous |
| No generated password | Declaring our own `UserDetailsService` turns off Boot's `UserDetailsServiceAutoConfiguration` |

**Tests**

| Name | Location | Covers |
|------|----------|--------|
| `SecurityApiIntegrationTest` | `…/security/` (test) | T01–T07, T09–T24 (T17 and T24 split into `a`/`b` methods) |
| `SecurityMatrixIntegrationTest` | `…/security/` (test) | T25, `@ParameterizedTest(name = "T25 [{0} {1} as {2}] -> {3}")` with a `@MethodSource` of 28 rows |
| `SecurityUsersTest` | `…/security/` (test) | T08 |
| `ProductApiIntegrationTest` | `…/product/` (test) | T04, T10, T11, T36: each `delete(...)` gets `.with(httpBasic("admin", "admin123"))` |
| `ProductControllerErrorTest` | `…/product/` (test) | T43: add `@Import({SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class, ProblemDetailAccessDeniedHandler.class})` |

Both integration classes use `@SpringBootTest @AutoConfigureMockMvc` and
`@Sql(scripts = "/reset-products.sql", executionPhase = BEFORE_TEST_METHOD)`. `@AutoConfigureMockMvc` adds the
`springSecurityFilterChain` to MockMvc automatically, so no manual `apply(springSecurity())` is needed.

## 2. Dependencies

| Library | Version | Use |
|---------|---------|-----|
| `spring-boot-starter-security` | 3.5.16 (managed) | filter chain, HTTP Basic, BCrypt, in-memory users |
| Spring Security | 6.5.11 (managed by Boot 3.5.16) | core, web, config |
| `spring-security-test` | 6.5.11 (managed), test scope | `httpBasic(...)` request post-processor |
| Jackson | 2.21.4 (managed, already present) | handlers serialise `ProblemDetail` |

Other modules used: `product` (unchanged; its `GlobalExceptionHandler` still produces every non-security error).

## 3. Steps

Each step ends in the stated test state and is its own commit. Test command: `mvnw.cmd test` from the repo root.

| # | Step | Ends with | Commit |
|---|------|-----------|--------|
| 1 | **Dependencies and stubs.** Add both dependencies. Add `SecurityConfig` with the real `passwordEncoder` and `userDetailsService` beans, but a stub chain: CSRF off, `httpBasic()` with Spring's default entry point, `anyRequest().permitAll()` and no session policy. Add both handler classes as `@Component`s whose methods throw `UnsupportedOperationException` (not wired yet). Without the stub chain, adding the starter would lock every endpoint behind Boot's default login and break the product tests | All existing product tests still green (re-checks that the starter alone changes nothing once permit-all is in place) | `feat(security): add Spring Security dependency and config skeleton` |
| 2 | **Tests first.** Write T01–T25 exactly as in test.md and apply the product test changes (T04, T10, T11, T36, T43). Run and check each failure has the right cause: tests expecting 401 on `DELETE` or 403 get 204/404/400 instead (no rule yet), T17/T18 bodies don't match. T22 may already pass (Basic auth doesn't always create a session), which is fine because step 3 sets `STATELESS` explicitly. Wrong-credential tests (T09, T11–T15) already pass because Basic is on. T08 already passes because the users are real | Tests compile; expected failures only; pass/fail count recorded in the commit body | `test(security): add tests SECURITY-T01..T25` |
| 3 | **Access rules.** Real filter chain from §1 (DELETE rule, `STATELESS`, both handlers wired) and the real `ProblemDetailAuthenticationEntryPoint` / `ProblemDetailAccessDeniedHandler` | All security tests green (26 methods + 28 T25 invocations) and all product tests green | `feat(security): require ADMIN for DELETE with ProblemDetail 401/403` |
| 4 | **Manual check.** `mvnw.cmd -pl product-backend spring-boot:run`, then `curl` the AC-04 sequence on product 2: anonymous `DELETE` → 401 with `WWW-Authenticate`, `-u user:user123` → 403, `-u admin:admin123` → 204, `GET` → 404. Also `curl -u admin:wrong /api/products` → 401 | AC-04 confirmed by hand | none, unless a fix is needed |

If a step shows a spec gap, stop, update spec.md/test.md, commit, then carry on (CLAUDE.md rule).

## 4. Risks / open points

| ID | Risk | Mitigation |
|----|------|------------|
| R1 | `ProblemDetail` serialised by a plain `ObjectMapper` would add `"properties":null` | Inject Boot's `ObjectMapper`: it is built by `Jackson2ObjectMapperBuilder`, which registers Spring's `ProblemDetailJacksonMixin`. T17/T18 check the exact fields; if `properties` shows up, register the mixin in the handlers |
| R2 | The entry point given only to `exceptionHandling` is not used for bad credentials, so `BasicAuthenticationFilter` would answer with Spring's default (empty body, `realm="Realm"`) | Pass the same entry point to `httpBasic(...)` too (§1). T17b checks the body on a wrong password |
| R3 | `instance` must be the request path, but an `/error` dispatch would change it | Handlers write the body directly and never call `sendError`; T17/T18 check `instance` |
| R4 | `ProductControllerErrorTest` lives in the `product` package and must `@Import` the security classes | `SecurityConfig` and both handlers are `public` (the spec's §2 sketch left visibility open) |
| R5 | `/api/products/**` also matches `DELETE /api/products` (no id) | Accepted: anonymous gets 401, admin gets the product module's 405. Not in the spec's matrix and not tested |
| R6 | `@WebMvcTest` with the imported config needs the `ObjectMapper` bean | Provided by the slice's Jackson auto-configuration; no extra import |
| R7 | JDK 25 and Byte Buddy (product plan R1) | No new mocking in this module; same mitigation applies if T43 breaks |

## 5. Definition of done

- SECURITY-T01..T25 are implemented, named with their IDs, and pass with `mvnw.cmd test` from the root, together with
  every product test (product test IDs and expected results unchanged).
- Every spec rule S01–S10 is covered (test.md coverage table), and the spec is unchanged or was updated and committed first.
- AC-04 confirmed by hand against the running backend (step 4).
- Passwords are BCrypt only; no `{noop}` anywhere. No secrets other than the spec's fixed demo users. No `.idea/`,
  `target/` or `*.iml` committed.
- Commits follow §3 in order, and the session log is exported.

## 6. Decision log

| ID | Question | Decision |
|----|----------|----------|
| PL-01 | *(decided without asking)* Spring Security version | 6.5.11, managed by Boot 3.5.16; no override |
| PL-02 | *(decided without asking)* Stub before tests | Step 1 adds a permit-all chain so the starter doesn't lock the product API, and the real users so the product `DELETE` tests can send `admin` credentials in step 2. T08 and the wrong-credential tests therefore pass before step 3 |
| PL-03 | *(decided without asking)* Wiring the 401 entry point | Set on both `httpBasic(...)` and `exceptionHandling(...)` (R2) |
| PL-04 | *(decided without asking)* Class visibility | `SecurityConfig` and both handlers are `public` so the `product` test slice can import them (R4) |
| PL-05 | *(decided without asking)* Writing the error body | Handlers write `application/problem+json` directly with Boot's `ObjectMapper`; no `sendError`, shared helper `ProblemResponses` |
| PL-06 | *(decided without asking)* One implementation step | Rules and handlers land together in step 3, since splitting them would leave a commit with Spring's empty 401/403 bodies and no tests to tell it apart |
| PL-07 | *(decided without asking)* Manual check | `curl` the AC-04 sequence and one wrong-password call against the running app |
