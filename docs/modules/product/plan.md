# product: Implementation plan
Status: draft (session 03, 2026-11-25)
Derived from: `docs/modules/product/spec.md` (approved, `3cce158`) and `docs/modules/product/test.md` (`d8c24be`)

This plan covers the build skeleton (root Maven project + `product-backend`) and the `product` module. The
`security` and `mcp-server` modules get their own plans later.

## 1. Classes / files

Paths are relative to the repo root. `…/product/` means `product-backend/src/main/java/com/aieng/productbackend/product/`
for main code and `product-backend/src/test/java/com/aieng/productbackend/product/` for tests.

**Build skeleton**

| Name | Location | Responsibility | Spec rules |
|------|----------|----------------|------------|
| `pom.xml` | root | Aggregator (`packaging pom`), parent `spring-boot-starter-parent`, `java.version=21`, module `product-backend` | NFR-01 |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | root | Maven wrapper (script-only, no jar) pinned to Maven 3.9.16 | NFR-01 |
| `pom.xml` | `product-backend/` | Starters: web, data-jpa, validation; `h2` (runtime); `spring-boot-starter-test` (test). Boot plugin for `spring-boot:run` | NFR-01, NFR-03 |
| `ProductBackendApplication` | `product-backend/src/main/java/com/aieng/productbackend/` | `@SpringBootApplication` main class | NFR-03 |
| `application.properties` | `product-backend/src/main/resources/` | H2, JPA, SQL init and Jackson settings (see below) | S06, S12 |

**Main code (`com.aieng.productbackend.product`)**

| Name | Location | Responsibility | Spec rules |
|------|----------|----------------|------------|
| `Product` | `…/product/Product.java` | JPA entity. `@Id @GeneratedValue(IDENTITY)`; `@Column(nullable=false, length=100)` name; `length=500` description; `precision=12, scale=2` price; `nullable=false` quantity. Protected no-arg constructor plus a constructor/setters used by the service | S06, §4 table |
| `ProductRepository` | `…/product/ProductRepository.java` | `JpaRepository<Product, Long>` | S02–S05 |
| `ProductRequest` | `…/product/ProductRequest.java` | Record with Bean Validation annotations (S10). The compact constructor trims `name`/`description`, turns a blank description into `null`, and is null-safe | S07, S09, S10 |
| `ProductResponse` | `…/product/ProductResponse.java` | Record + `static from(Product)` | S01–S04, NFR-05 |
| `ProductNotFoundException` | `…/product/ProductNotFoundException.java` | `RuntimeException`, message `Product with id <id> not found` | S14 |
| `ProductService` | `…/product/ProductService.java` | `@Service`, `@Transactional` writes. `create` ignores any id and sets price scale to 2 (`setScale(2, UNNECESSARY)`, safe because `@Digits` allows at most 2 decimals). `findAll` uses `findAll(Sort.by("id"))`. `findById`/`update`/`delete` throw `ProductNotFoundException` when missing (`delete` checks `existsById` first) | S01–S08, S13, S14 |
| `ProductController` | `…/product/ProductController.java` | `@RestController @RequestMapping("/api/products")`. `@Valid @RequestBody ProductRequest` on POST/PUT, `@PathVariable Long id`. POST returns `ResponseEntity.created(URI.create("/api/products/" + id))`; DELETE returns `204` | S01–S05, S07, S15, S17 |
| `GlobalExceptionHandler` | `…/product/GlobalExceptionHandler.java` | `@RestControllerAdvice extends ResponseEntityExceptionHandler`. See "Error handling" below | S11, S12, S14–S16, S18, S19 |

**Resources and test fixtures**

| Name | Location | Responsibility | Spec rules |
|------|----------|----------------|------------|
| `data.sql` | `product-backend/src/main/resources/` | 3 seed `INSERT`s **without** ids | S06, FR-PROD-05 |
| `reset-products.sql` | `product-backend/src/test/resources/` | `TRUNCATE TABLE product RESTART IDENTITY;` then the same 3 inserts as `data.sql` | test.md fixtures |
| `ProductApiIntegrationTest` | `…/product/` (test) | T01–T38, `@SpringBootTest @AutoConfigureMockMvc`, `@Sql("/reset-products.sql")` before each method | S01–S18 |
| `ProductRequestTest` | `…/product/` (test) | T39–T42 | S09 |
| `ProductControllerErrorTest` | `…/product/` (test) | T43, `@WebMvcTest(ProductController.class)` + `@MockitoBean ProductService` | S19 |

**`application.properties`**

```properties
spring.application.name=product-backend
spring.datasource.url=jdbc:h2:mem:products;DB_CLOSE_DELAY=-1
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.defer-datasource-initialization=true   # data.sql runs after Hibernate creates the table
spring.sql.init.mode=always
spring.jpa.open-in-view=false
spring.jackson.mapper.allow-coercion-of-scalars=false        # "5" -> Integer and "10.00" -> BigDecimal fail (S12)
spring.jackson.deserialization.accept-float-as-int=false     # 1.5 -> Integer fails (S12)
spring.jackson.deserialization.fail-on-unknown-properties=false  # S07 (already Boot's default, stated for clarity)
```

**Error handling (`GlobalExceptionHandler`)**

| Override / handler | Result |
|--------------------|--------|
| `handleMethodArgumentNotValid` | 400, title `Validation failed`, detail `Request has invalid fields`, property `errors` = `LinkedHashMap` built from `getFieldErrors()` with `putIfAbsent` (one message per field) |
| `handleHttpMessageNotReadable` | If the cause is a `MismatchedInputException` whose path has a field name: 400 `Validation failed` with `errors` = `{field: "must be an integer"}` when the target type is `Integer`/`Long`, otherwise `"must be a number"`. Anything else (syntax error, missing body, no field in the path): 400 `Malformed request`, detail `Request body is missing or not valid JSON` |
| `handleTypeMismatch` (`MethodArgumentTypeMismatchException` is a `TypeMismatchException`) | 400 `Bad Request`, detail `Invalid value '<v>' for parameter '<name>'` |
| `@ExceptionHandler(ProductNotFoundException)` | 404 `Not Found`, detail = exception message |
| `@ExceptionHandler(Exception)` | Logs at ERROR, returns 500 `Internal Server Error`, detail `Unexpected error` |
| `handleExceptionInternal` | For every ProblemDetail body (including Spring's 405/415): set `instance` to the request URI when it is empty, so S18 holds everywhere |

All handlers build a `ProblemDetail`, so Spring writes them as `application/problem+json`.

## 2. Dependencies

| Library | Version | Use |
|---------|---------|-----|
| Spring Boot (parent / BOM) | 3.5.16 | everything below is managed by it |
| Spring Framework | 6.2.19 (managed) | Web MVC, `ProblemDetail`, `ResponseEntityExceptionHandler` |
| Hibernate ORM | 6.6.53.Final (managed) | JPA |
| H2 | 2.3.232 (managed) | in-memory DB (runtime scope) |
| Jackson | 2.21.4 (managed) | JSON, strict coercion settings |
| Hibernate Validator | managed | Bean Validation |
| JUnit 5, Mockito 5.17.0, AssertJ, MockMvc | managed via `spring-boot-starter-test` | tests |
| Byte Buddy | 1.17.8 (managed) | used by Mockito/Hibernate; see risk R1 |
| Maven / wrapper | 3.9.16 / wrapper 3.3.4 | build |

Other modules used: none. `spring-boot-starter-security` is **not** added here. The `security` module adds it, so
this module's tests run without authentication.

## 3. Steps

Each step ends in the stated test state and is its own commit. The test command is `mvnw.cmd test` from the repo root.

| # | Step | Ends with | Commit |
|---|------|-----------|--------|
| 1 | **Build skeleton.** Root `pom.xml`, wrapper files (script-only wrapper fetched from Maven Central, since no global Maven is installed), `product-backend/pom.xml`, `ProductBackendApplication`, `application.properties`. Check that `mvnw.cmd test` downloads Maven and runs the build on JDK 25 with release 21 | Build succeeds, 0 tests | `chore: add Maven multi-module build with product-backend` |
| 2 | **Compile stubs.** Add every type in spec §2 with the right signatures: the records with their validation annotations but **no** trimming, the entity, the repository, the exception, a service whose methods throw `UnsupportedOperationException`, a controller with the 5 mappings calling the service, and an empty `GlobalExceptionHandler`. Add `data.sql` | Build succeeds, 0 tests | `feat(product): add API skeleton with stub service` |
| 3 | **Tests first.** Write T01–T43 exactly as in test.md (method names `T01_…`, T37 split into `T37a`–`T37d`), plus `reset-products.sql`. Run them and check they fail for the right reason: service calls give 500 (not compile errors or context failures), T39/T40 fail on untrimmed values | Tests compile; most fail as expected; record the pass/fail count in the commit body | `test(product): add tests PRODUCT-T01..T43` |
| 4 | **Happy path.** `ProductRequest` trimming, `ProductService` logic, price scale, `Location` header | T01–T18, T27, T39–T42 green. Error-format tests may still fail | `feat(product): implement product CRUD and input normalisation` |
| 5 | **Errors.** `GlobalExceptionHandler` in full and the Jackson settings | All 43 tests green (47 methods counting T37a–d) | `feat(product): add ProblemDetail error handling and strict JSON typing` |
| 6 | **Manual check.** `mvnw.cmd -pl product-backend spring-boot:run`, then `curl` AC-01 (3 seed products) and AC-02 (id 4, `Location`) against the running app | AC-01/AC-02 confirmed by hand (NFR-03) | none, unless a fix is needed |

If a step shows a spec gap, stop, update spec.md/test.md, commit, then carry on (CLAUDE.md rule).

## 4. Risks / open points

| ID | Risk | Mitigation |
|----|------|------------|
| R1 | JDK 25 runs the build. Mockito inline mocks (T43) and Hibernate use Byte Buddy, which must understand Java 25 class files. Mockito also warns about dynamic agent loading | Byte Buddy 1.17.8 is expected to support Java 25. If T43 fails with a Byte Buddy error, override `byte-buddy.version` in the root pom to a newer 1.18.x, or add `-Dnet.bytebuddy.experimental=true` to the surefire `argLine`. The warning is harmless |
| R2 | Price output: a JSON number like `1599.00` is read by MockMvc `jsonPath` as a `Double` (`1599.0`), which hides the scale | Tests check scale on the raw body (e.g. `content().string(containsString("\"price\":1599.00"))`) or parse prices as `BigDecimal`; the service always stores scale 2 |
| R3 | Strict typing relies on two Jackson settings. If one doesn't reject a case (T31 `"5"`/`true`, T32 `"10.00"`) | T31/T32 detect it. Fallback: a `Jackson2ObjectMapperBuilderCustomizer` that sets `coercionConfigDefaults().setCoercion(...)` to `Fail` for String → Integer/Float and Float → Integer |
| R4 | Some `HttpMessageNotReadableException`s that wrap a `MismatchedInputException` are not field-type errors (e.g. an empty body) | Only treat it as a typing error when the exception path has a field name; otherwise use `Malformed request` (T37b/c check this) |
| R5 | The DB's IDENTITY must continue at 4 after the seed, and restart at 1 in tests | `data.sql` inserts without ids; `reset-products.sql` uses `TRUNCATE … RESTART IDENTITY`. T01/T11 check it |
| R6 | `instance` is not set on Spring's built-in 405/415 ProblemDetails by default | The `handleExceptionInternal` override fills it; T38 checks the content type |
| R7 | Getting the Maven wrapper without a global Maven install | Download the wrapper scripts (wrapper 3.3.4, `distributionType=only-script`) from Maven Central; the `.gitignore` already excludes the wrapper jar |

## 5. Definition of done

- All test.md IDs PRODUCT-T01..T43 are implemented, named with their IDs, and pass with `mvnw.cmd test` from the root.
- Every spec rule S01–S19 is covered (test.md coverage table) and the spec is unchanged, or was updated and committed first.
- AC-01 and AC-02 checked by hand against the running backend (step 6).
- No JPA entity in any controller signature (NFR-05). No `.idea/`, `target/` or `*.iml` committed.
- Commits follow step 3 in order, and the session log is exported.

## 6. Decision log

| ID | Question | Decision |
|----|----------|----------|
| PL-01 | *(decided without asking)* Spring Boot version | 3.5.16, the latest 3.5.x on Maven Central on 2026-11-25 |
| PL-02 | *(decided without asking)* Maven wrapper | Wrapper 3.3.4, script-only, Maven 3.9.16 |
| PL-03 | *(decided without asking)* Strict typing mechanism (spec §6 left it to the plan) | `allow-coercion-of-scalars=false` + `accept-float-as-int=false`; fallback in R3 |
| PL-04 | *(decided without asking)* Where Spring Security is added | Not in this module; the `security` module adds the dependency and its config |
| PL-05 | *(decided without asking)* Tests before code with compile stubs | Step 2 adds stubs so step 3's tests compile and fail at runtime, not at compile time |
| PL-06 | *(decided without asking)* `instance` on every ProblemDetail | Set in `handleExceptionInternal` from the request URI |
| PL-07 | *(decided without asking)* Price scale | Service sets scale 2 on write; the column is `DECIMAL(12,2)` |
| PL-08 | *(decided without asking)* Delete on an unknown id | `existsById` check then `deleteById`, throwing `ProductNotFoundException` when missing |
