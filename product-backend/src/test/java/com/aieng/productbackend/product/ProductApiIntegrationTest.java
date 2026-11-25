package com.aieng.productbackend.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP + persistence tests for the product API (PRODUCT-T01..T38), against real H2.
 * Every test starts from the 3 seed rows with the next id at 4 (reset-products.sql).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/reset-products.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ProductApiIntegrationTest {

    private static final String PRODUCTS = "/api/products";
    /** DELETE needs ADMIN since the security module (spec SD-08). */
    private static final RequestPostProcessor ADMIN = httpBasic("admin", "admin123");
    private static final String PEN = """
            {"name":"Pen","price":10,"quantity":1}""";

    @Autowired
    private MockMvc mockMvc;

    // ---------- helpers ----------

    private ResultActions postJson(String body) throws Exception {
        return mockMvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putJson(long id, String body) throws Exception {
        return mockMvc.perform(put(PRODUCTS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getOne(long id) throws Exception {
        return mockMvc.perform(get(PRODUCTS + "/" + id));
    }

    private ResultActions getAll() throws Exception {
        return mockMvc.perform(get(PRODUCTS));
    }

    /** Checks the raw JSON so the 2-decimal scale is visible (plan risk R2). */
    private static ResultMatcher price(String value) {
        return content().string(containsString("\"price\":" + value));
    }

    private static ResultMatcher problem(int status, String title) {
        return result -> {
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.type").value("about:blank").match(result);
            jsonPath("$.title").value(title).match(result);
            jsonPath("$.status").value(status).match(result);
        };
    }

    private static ResultMatcher validationProblem(String instance) {
        return result -> {
            problem(400, "Validation failed").match(result);
            jsonPath("$.detail").value("Request has invalid fields").match(result);
            jsonPath("$.instance").value(instance).match(result);
        };
    }

    private static ResultMatcher notFound(long id, String instance) {
        return result -> {
            problem(404, "Not Found").match(result);
            jsonPath("$.detail").value("Product with id " + id + " not found").match(result);
            jsonPath("$.instance").value(instance).match(result);
        };
    }

    private void expectListSize(int size) throws Exception {
        getAll().andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(size)));
    }

    // ---------- Happy paths ----------

    // PRODUCT-T01 (S01, S06, AC-02)
    @Test
    void T01_createValidProduct_returns201WithLocation() throws Exception {
        postJson("""
                {"name":"Laptop Stand","price":1599.00,"quantity":7}""")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/products/4"))
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.name").value("Laptop Stand"))
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(price("1599.00"))
                .andExpect(jsonPath("$.quantity").value(7));
    }

    // PRODUCT-T02 (S01)
    @Test
    void T02_createWithDescription_thenGetReturnsSameBody() throws Exception {
        postJson("""
                {"name":"Laptop Stand","description":"Adjustable aluminium","price":1599.00,"quantity":7}""")
                .andExpect(status().isCreated());

        getOne(4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.name").value("Laptop Stand"))
                .andExpect(jsonPath("$.description").value("Adjustable aluminium"))
                .andExpect(price("1599.00"))
                .andExpect(jsonPath("$.quantity").value(7));
    }

    // PRODUCT-T03 (S02, AC-01)
    @Test
    void T03_listReturnsSeedProductsInIdOrder() throws Exception {
        getAll()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].id", contains(1, 2, 3)))
                .andExpect(jsonPath("$[0].name").value("Wireless Mouse"))
                .andExpect(jsonPath("$[0].description").value("2.4 GHz wireless mouse, black"))
                .andExpect(jsonPath("$[0].quantity").value(25))
                .andExpect(jsonPath("$[1].name").value("Mechanical Keyboard"))
                .andExpect(jsonPath("$[1].description").value("Blue switches, full size"))
                .andExpect(jsonPath("$[1].quantity").value(10))
                .andExpect(jsonPath("$[2].name").value("USB-C Hub"))
                .andExpect(jsonPath("$[2].description").value("6-in-1 hub with HDMI"))
                .andExpect(jsonPath("$[2].quantity").value(0))
                .andExpect(price("799.00"))
                .andExpect(price("2499.00"))
                .andExpect(price("1299.50"));
    }

    // PRODUCT-T04 (S02, S05, EC-20)
    @Test
    void T04_deleteAll_thenListIsEmpty() throws Exception {
        for (long id = 1; id <= 3; id++) {
            mockMvc.perform(delete(PRODUCTS + "/" + id).with(ADMIN)).andExpect(status().isNoContent());
        }

        getAll()
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // PRODUCT-T05 (S02)
    @Test
    void T05_listIncludesCreatedProductInIdOrder() throws Exception {
        postJson("""
                {"name":"Laptop Stand","price":1599.00,"quantity":7}""")
                .andExpect(status().isCreated());

        getAll()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(1, 2, 3, 4)));
    }

    // PRODUCT-T06 (S03)
    @Test
    void T06_getById_returnsProduct() throws Exception {
        getOne(3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.name").value("USB-C Hub"))
                .andExpect(jsonPath("$.description").value("6-in-1 hub with HDMI"))
                .andExpect(price("1299.50"))
                .andExpect(jsonPath("$.quantity").value(0));
    }

    // PRODUCT-T07 (S04, FR-PROD-03, EC-13)
    @Test
    void T07_putWithoutDescription_replacesAndNullsDescription() throws Exception {
        putJson(1, """
                {"name":"Wireless Mouse Pro","price":899.00,"quantity":20}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Wireless Mouse Pro"))
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(price("899.00"))
                .andExpect(jsonPath("$.quantity").value(20));
    }

    // PRODUCT-T08 (S04, AC-03)
    @Test
    void T08_afterPut_getReturnsUpdatedValues() throws Exception {
        putJson(1, """
                {"name":"Wireless Mouse Pro","price":899.00,"quantity":20}""")
                .andExpect(status().isOk());

        getOne(1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Wireless Mouse Pro"))
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(price("899.00"))
                .andExpect(jsonPath("$.quantity").value(20));
        expectListSize(3);
    }

    // PRODUCT-T09 (S04)
    @Test
    void T09_putWithAllFields_replacesAll() throws Exception {
        putJson(2, """
                {"name":"Keyboard TKL","description":"Red switches","price":1999.99,"quantity":3}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.name").value("Keyboard TKL"))
                .andExpect(jsonPath("$.description").value("Red switches"))
                .andExpect(price("1999.99"))
                .andExpect(jsonPath("$.quantity").value(3));
    }

    // PRODUCT-T10 (S05)
    @Test
    void T10_delete_returns204AndRemovesProduct() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/2").with(ADMIN))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        getOne(2).andExpect(status().isNotFound());
        getAll().andExpect(jsonPath("$[*].id", contains(1, 3)));
    }

    // PRODUCT-T11 (S06)
    @Test
    void T11_idsAreNotReusedAfterDelete() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/3").with(ADMIN)).andExpect(status().isNoContent());

        postJson(PEN).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(4));
        postJson(PEN).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(5));
    }

    // PRODUCT-T12 (S07, EC-14)
    @Test
    void T12_postIgnoresBodyIdAndUnknownFields() throws Exception {
        postJson("""
                {"id":50,"name":"Pen","price":10,"quantity":1,"color":"red"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(4));

        getOne(50).andExpect(status().isNotFound());
    }

    // PRODUCT-T13 (S07)
    @Test
    void T13_putIgnoresBodyId() throws Exception {
        putJson(1, """
                {"id":99,"name":"Pen","price":10,"quantity":1}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        getOne(99).andExpect(status().isNotFound());
        expectListSize(3);
    }

    // PRODUCT-T14 (S08, EC-08)
    @Test
    void T14_duplicateNameIsAllowed() throws Exception {
        postJson("""
                {"name":"Wireless Mouse","price":799.00,"quantity":1}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(4));

        getOne(1)
                .andExpect(jsonPath("$.name").value("Wireless Mouse"))
                .andExpect(jsonPath("$.description").value("2.4 GHz wireless mouse, black"))
                .andExpect(price("799.00"))
                .andExpect(jsonPath("$.quantity").value(25));
    }

    // PRODUCT-T15 (S13)
    @Test
    void T15_integerPriceIsReturnedWithTwoDecimals() throws Exception {
        postJson(PEN)
                .andExpect(status().isCreated())
                .andExpect(price("10.00"));
    }

    // ---------- Normalisation and validation ----------

    // PRODUCT-T16 (S09)
    @Test
    void T16_trimsNameAndBlankDescriptionBecomesNull() throws Exception {
        postJson("""
                {"name":"  Pen  ","description":"   ","price":10,"quantity":1}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Pen"))
                .andExpect(jsonPath("$.description").value(nullValue()));
    }

    // PRODUCT-T17 (S09, S10)
    @Test
    void T17_lengthLimitAppliesToTrimmedName() throws Exception {
        String hundred = "a".repeat(100);

        postJson("{\"name\":\"  " + hundred + "  \",\"price\":10,\"quantity\":1}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(hundred));
    }

    // PRODUCT-T18 (S09, S04)
    @Test
    void T18_putTrimsNameAndDescription() throws Exception {
        putJson(1, """
                {"name":" Mouse ","description":" Black ","price":10,"quantity":1}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mouse"))
                .andExpect(jsonPath("$.description").value("Black"));
    }

    // PRODUCT-T19 (S10, S11, EC-01)
    @Test
    void T19_zeroPrice_returns400() throws Exception {
        postJson("""
                {"name":"Pen","price":0,"quantity":5}""")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS))
                .andExpect(jsonPath("$.errors.price").value("must be greater than 0"));

        expectListSize(3);
    }

    // PRODUCT-T20 (S10, S11, EC-02)
    @Test
    void T20_negativeQuantity_returns400() throws Exception {
        postJson("""
                {"name":"Pen","price":10,"quantity":-1}""")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS))
                .andExpect(jsonPath("$.errors.quantity").value("must be greater than or equal to 0"));
    }

    // PRODUCT-T21 (S09, S10, EC-03)
    @Test
    void T21_whitespaceOnlyName_returns400() throws Exception {
        postJson("""
                {"name":"   ","price":10,"quantity":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS))
                .andExpect(jsonPath("$.errors.name").value("must not be blank"));
    }

    // PRODUCT-T22 (S10, EC-04)
    @Test
    void T22_nameOf101Chars_returns400() throws Exception {
        postJson("{\"name\":\"" + "a".repeat(101) + "\",\"price\":10,\"quantity\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS))
                .andExpect(jsonPath("$.errors", hasKey("name")));
    }

    // PRODUCT-T23 (S10)
    @Test
    void T23_descriptionOf501Chars_returns400() throws Exception {
        postJson("{\"name\":\"Pen\",\"description\":\"" + "a".repeat(501) + "\",\"price\":10,\"quantity\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS))
                .andExpect(jsonPath("$.errors", hasKey("description")));
    }

    // PRODUCT-T24 (S10)
    @Test
    void T24_descriptionOf500Chars_returns201() throws Exception {
        postJson("{\"name\":\"Pen\",\"description\":\"" + "a".repeat(500) + "\",\"price\":10,\"quantity\":1}")
                .andExpect(status().isCreated());
    }

    // PRODUCT-T25 (S10, EC-06)
    @Test
    void T25_priceWithThreeDecimals_returns400() throws Exception {
        postJson("""
                {"name":"Pen","price":10.555,"quantity":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS))
                .andExpect(jsonPath("$.errors", hasKey("price")));
    }

    // PRODUCT-T26 (S10)
    @Test
    void T26_priceIntegerDigitLimit() throws Exception {
        postJson("""
                {"name":"Pen","price":9999999999.99,"quantity":1}""")
                .andExpect(status().isCreated())
                .andExpect(price("9999999999.99"));

        postJson("""
                {"name":"Pen","price":10000000000,"quantity":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS))
                .andExpect(jsonPath("$.errors", hasKey("price")));
    }

    // PRODUCT-T27 (S10, EC-07)
    @Test
    void T27_quantityZero_returns201() throws Exception {
        postJson("""
                {"name":"Pen","price":10,"quantity":0}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(0));
    }

    // PRODUCT-T28 (S10, S11)
    @Test
    void T28_nullOrMissingPriceAndQuantity_returns400() throws Exception {
        postJson("""
                {"name":"Pen","price":null}""")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS))
                .andExpect(jsonPath("$.errors.price").value("must not be null"))
                .andExpect(jsonPath("$.errors.quantity").value("must not be null"));
    }

    // PRODUCT-T29 (S11, EC-05)
    @Test
    void T29_severalInvalidFields_allReported() throws Exception {
        postJson("""
                {"name":"","price":-1,"quantity":-1}""")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS))
                .andExpect(jsonPath("$.errors", aMapWithSize(3)))
                .andExpect(jsonPath("$.errors", hasKey("name")))
                .andExpect(jsonPath("$.errors", hasKey("price")))
                .andExpect(jsonPath("$.errors", hasKey("quantity")));

        expectListSize(3);
    }

    // PRODUCT-T30 (S10, S11)
    @Test
    void T30_putWithInvalidBody_returns400AndLeavesProductUnchanged() throws Exception {
        putJson(1, """
                {"name":"Pen","price":0,"quantity":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS + "/1"))
                .andExpect(jsonPath("$.errors", hasKey("price")));

        getOne(1)
                .andExpect(jsonPath("$.name").value("Wireless Mouse"))
                .andExpect(price("799.00"))
                .andExpect(jsonPath("$.quantity").value(25));
    }

    // PRODUCT-T31 (S12)
    @Test
    void T31_nonIntegerQuantity_returns400() throws Exception {
        for (String quantity : new String[] {"1.5", "\"5\"", "true"}) {
            postJson("{\"name\":\"Pen\",\"price\":10,\"quantity\":" + quantity + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(validationProblem(PRODUCTS))
                    .andExpect(jsonPath("$.errors.quantity").value("must be an integer"));
        }
        expectListSize(3);
    }

    // PRODUCT-T32 (S12)
    @Test
    void T32_stringPrice_returns400() throws Exception {
        postJson("""
                {"name":"Pen","price":"10.00","quantity":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem(PRODUCTS))
                .andExpect(jsonPath("$.errors.price").value("must be a number"));
    }

    // ---------- Not found and request errors ----------

    // PRODUCT-T33 (S14, S18, EC-09)
    @Test
    void T33_unknownId_returns404ProblemDetail() throws Exception {
        getOne(999)
                .andExpect(status().isNotFound())
                .andExpect(notFound(999, "/api/products/999"));
    }

    // PRODUCT-T34 (S14)
    @Test
    void T34_zeroAndNegativeIds_return404() throws Exception {
        getOne(0)
                .andExpect(status().isNotFound())
                .andExpect(notFound(0, "/api/products/0"));
        getOne(-5)
                .andExpect(status().isNotFound())
                .andExpect(notFound(-5, "/api/products/-5"));
    }

    // PRODUCT-T35 (S14, EC-12)
    @Test
    void T35_putUnknownId_returns404AndCreatesNothing() throws Exception {
        putJson(999, PEN)
                .andExpect(status().isNotFound())
                .andExpect(notFound(999, "/api/products/999"));

        getAll()
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].id", not(contains(999))));
    }

    // PRODUCT-T36 (S14, EC-17 ignoring auth)
    @Test
    void T36_deleteUnknownId_returns404() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/999").with(ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(notFound(999, "/api/products/999"));

        expectListSize(3);
    }

    // PRODUCT-T37a (S15, S18, EC-10)
    @Test
    void T37a_nonNumericId_returns400() throws Exception {
        mockMvc.perform(get(PRODUCTS + "/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(problem(400, "Bad Request"))
                .andExpect(jsonPath("$.detail").value("Invalid value 'abc' for parameter 'id'"))
                .andExpect(jsonPath("$.instance").value("/api/products/abc"));
    }

    // PRODUCT-T37b (S16, S18, EC-11)
    @Test
    void T37b_malformedJson_returns400() throws Exception {
        postJson("{\"name\": \"Pen\",")
                .andExpect(status().isBadRequest())
                .andExpect(problem(400, "Malformed request"))
                .andExpect(jsonPath("$.detail").value("Request body is missing or not valid JSON"))
                .andExpect(jsonPath("$.instance").value(PRODUCTS));
    }

    // PRODUCT-T37c (S16, S18)
    @Test
    void T37c_missingBody_returns400() throws Exception {
        mockMvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(problem(400, "Malformed request"))
                .andExpect(jsonPath("$.detail").value("Request body is missing or not valid JSON"))
                .andExpect(jsonPath("$.instance").value(PRODUCTS));
    }

    // PRODUCT-T37d (S17, S18)
    @Test
    void T37d_invalidBodyOnUnknownId_returns400Not404() throws Exception {
        putJson(999, """
                {"name":"","price":10,"quantity":1}""")
                .andExpect(status().isBadRequest())
                .andExpect(validationProblem("/api/products/999"))
                .andExpect(jsonPath("$.errors", hasKey("name")));
    }

    // PRODUCT-T38 (S18)
    @Test
    void T38_unsupportedMethodAndMediaType_returnProblemDetail() throws Exception {
        mockMvc.perform(patch(PRODUCTS + "/1").contentType(MediaType.APPLICATION_JSON).content(PEN))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.instance").value("/api/products/1"));

        mockMvc.perform(post(PRODUCTS).contentType(MediaType.TEXT_PLAIN).content("Pen"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.instance").value(PRODUCTS));
    }
}
