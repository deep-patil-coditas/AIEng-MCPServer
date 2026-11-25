package com.aieng.productbackend.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec section 4 authorization matrix, all 28 cells (SECURITY-T25). Seed data is reset before every invocation. */
@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/reset-products.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class SecurityMatrixIntegrationTest {

    private static final String LAPTOP_STAND = """
            {"name":"Laptop Stand","price":1599.00,"quantity":7}""";

    private static final List<String> CALLERS = List.of("anon", "user", "admin", "wrongpw");

    /** Rows of spec section 4: method, path, then the expected status for anon, user, admin, wrongpw. */
    private static final Object[][] MATRIX = {
            {"GET", "/api/products", 200, 200, 200, 401},
            {"GET", "/api/products/1", 200, 200, 200, 401},
            {"POST", "/api/products", 201, 201, 201, 401},
            {"PUT", "/api/products/1", 200, 200, 200, 401},
            {"DELETE", "/api/products/1", 401, 403, 204, 401},
            {"DELETE", "/api/products/999", 401, 403, 404, 401},
            {"GET", "/foo", 404, 404, 404, 401},
    };

    @Autowired
    private MockMvc mockMvc;

    static Stream<Arguments> matrix() {
        List<Arguments> cells = new ArrayList<>();
        for (Object[] row : MATRIX) {
            for (int c = 0; c < CALLERS.size(); c++) {
                cells.add(Arguments.of(row[0], row[1], CALLERS.get(c), row[2 + c]));
            }
        }
        return cells.stream();
    }

    // SECURITY-T25 (S01, S02, S04, S05, S06, S08)
    @ParameterizedTest(name = "T25 [{0} {1} as {2}] -> {3}")
    @MethodSource("matrix")
    void T25_authorizationMatrix(String method, String path, String caller, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), path);
        if (method.equals("POST") || method.equals("PUT")) {
            request.contentType(MediaType.APPLICATION_JSON).content(LAPTOP_STAND);
        }
        switch (caller) {
            case "user" -> request.with(httpBasic("user", "user123"));
            case "admin" -> request.with(httpBasic("admin", "admin123"));
            case "wrongpw" -> request.with(httpBasic("admin", "wrong"));
            default -> { /* anon: no Authorization header */ }
        }

        mockMvc.perform(request).andExpect(status().is(expectedStatus));
    }
}
