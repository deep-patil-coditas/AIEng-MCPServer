package com.aieng.productbackend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security rules over HTTP through the real filter chain (SECURITY-T01..T07, T09..T24).
 * Every test starts from the 3 seed rows with the next id at 4 (reset-products.sql).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/reset-products.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class SecurityApiIntegrationTest {

    private static final String PRODUCTS = "/api/products";
    private static final String CHALLENGE = "Basic realm=\"Realm\"";
    private static final String LAPTOP_STAND = """
            {"name":"Laptop Stand","price":1599.00,"quantity":7}""";

    private static final RequestPostProcessor USER = httpBasic("user", "user123");
    private static final RequestPostProcessor ADMIN = httpBasic("admin", "admin123");
    private static final RequestPostProcessor WRONG_PW = httpBasic("admin", "wrong");

    @Autowired
    private MockMvc mockMvc;

    // ---------- helpers ----------

    /** "401 challenge": status 401, Basic challenge header and the 401 ProblemDetail detail (S07). */
    private static ResultMatcher challenge401() {
        return result -> {
            status().isUnauthorized().match(result);
            header().string(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.detail").value("Authentication required").match(result);
        };
    }

    private static ResultMatcher forbidden403() {
        return result -> {
            status().isForbidden().match(result);
            header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE).match(result);
            jsonPath("$.detail").value("Access Denied").match(result);
        };
    }

    private void expectProductExists(long id) throws Exception {
        mockMvc.perform(get(PRODUCTS + "/" + id)).andExpect(status().isOk());
    }

    private void expectListSize(int size) throws Exception {
        mockMvc.perform(get(PRODUCTS)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(size)));
    }

    // ---------- Open endpoints (S01) ----------

    // SECURITY-T01 (S01)
    @Test
    void T01_anonymousList_returns200WithoutChallenge() throws Exception {
        mockMvc.perform(get(PRODUCTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    // SECURITY-T02 (S01, S09, AC-02)
    @Test
    void T02_anonymousCreateWithoutCsrf_returns201() throws Exception {
        mockMvc.perform(post(PRODUCTS).contentType(MediaType.APPLICATION_JSON).content(LAPTOP_STAND))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/products/4")));
    }

    // SECURITY-T03 (S01, FR-PROD-03)
    @Test
    void T03_userUpdate_returns200AndAdminSeesSameValues() throws Exception {
        String body = """
                {"name":"Wireless Mouse Pro","price":899.00,"quantity":20}""";
        mockMvc.perform(put(PRODUCTS + "/1").with(USER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Wireless Mouse Pro"));

        mockMvc.perform(get(PRODUCTS + "/1").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Wireless Mouse Pro"))
                .andExpect(content().string(containsString("\"price\":899.00")))
                .andExpect(jsonPath("$.quantity").value(20));
    }

    // SECURITY-T04 (S01)
    @Test
    void T04_anonymousUnknownPath_returns404NotUnauthorized() throws Exception {
        mockMvc.perform(get("/foo"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.instance").value("/foo"));
    }

    // ---------- Admin delete (S02) ----------

    // SECURITY-T05 (S02, S04, S06, AC-04)
    @Test
    void T05_deleteSequence_anon401_user403_admin204_then404() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/2")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(PRODUCTS + "/2").with(USER)).andExpect(status().isForbidden());
        mockMvc.perform(delete(PRODUCTS + "/2").with(ADMIN))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(get(PRODUCTS + "/2")).andExpect(status().isNotFound());
    }

    // SECURITY-T06 (S02, EC-17)
    @Test
    void T06_adminDeleteUnknownId_returns404() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/999").with(ADMIN))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Product with id 999 not found"));

        expectListSize(3);
    }

    // SECURITY-T07 (S02)
    @Test
    void T07_adminDeleteNonNumericId_returns400FromProductModule() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/abc").with(ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid value 'abc' for parameter 'id'"));
    }

    // ---------- Users and passwords (S03) ----------

    // SECURITY-T09 (S03, S05)
    @Test
    void T09_passwordIsCaseSensitive_returns401() throws Exception {
        mockMvc.perform(get(PRODUCTS).with(httpBasic("admin", "ADMIN123"))).andExpect(challenge401());
    }

    // ---------- 401 and 403 (S04, S05, S06) ----------

    // SECURITY-T10 (S04, EC-15)
    @Test
    void T10_anonymousDelete_returns401WithChallenge() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/1")).andExpect(challenge401());

        expectProductExists(1);
    }

    // SECURITY-T11 (S05, EC-19)
    @Test
    void T11_wrongPasswordOnOpenEndpoint_returns401() throws Exception {
        mockMvc.perform(get(PRODUCTS).with(WRONG_PW)).andExpect(challenge401());
    }

    // SECURITY-T12 (S05)
    @Test
    void T12_unknownUser_returns401() throws Exception {
        mockMvc.perform(get(PRODUCTS).with(httpBasic("nobody", "x"))).andExpect(challenge401());
    }

    // SECURITY-T13 (S05)
    @Test
    void T13_wrongPasswordOnCreate_returns401AndCreatesNothing() throws Exception {
        mockMvc.perform(post(PRODUCTS).with(httpBasic("user", "wrong"))
                        .contentType(MediaType.APPLICATION_JSON).content(LAPTOP_STAND))
                .andExpect(challenge401());

        expectListSize(3);
    }

    // SECURITY-T14 (S05)
    @Test
    void T14_wrongPasswordOnDelete_returns401AndKeepsProduct() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/1").with(WRONG_PW)).andExpect(challenge401());

        expectProductExists(1);
    }

    // SECURITY-T15 (S05)
    @Test
    void T15_undecodableBasicHeader_returns401() throws Exception {
        mockMvc.perform(get(PRODUCTS).header(HttpHeaders.AUTHORIZATION, "Basic !!!")).andExpect(challenge401());
    }

    // SECURITY-T16 (S06, EC-16)
    @Test
    void T16_userDelete_returns403WithoutChallenge() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/1").with(USER)).andExpect(forbidden403());

        expectProductExists(1);
    }

    // ---------- Error bodies (S07) ----------

    // SECURITY-T17a (S07, S04)
    @Test
    void T17a_anonymousDelete_has401ProblemBody() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Unauthorized","status":401,
                         "detail":"Authentication required","instance":"/api/products/1"}""",
                        JsonCompareMode.STRICT));
    }

    // SECURITY-T17b (S07, S05)
    @Test
    void T17b_wrongPassword_hasSame401ProblemBody() throws Exception {
        mockMvc.perform(get(PRODUCTS).with(WRONG_PW))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Unauthorized","status":401,
                         "detail":"Authentication required","instance":"/api/products"}""",
                        JsonCompareMode.STRICT));
    }

    // SECURITY-T18 (S07, S06)
    @Test
    void T18_userDelete_has403ProblemBody() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/1").with(USER))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Forbidden","status":403,
                         "detail":"Access Denied","instance":"/api/products/1"}""",
                        JsonCompareMode.STRICT));
    }

    // ---------- Authorization before lookup (S08) ----------

    // SECURITY-T19 (S08, EC-18)
    @Test
    void T19_userDeleteUnknownId_returns403Not404() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/999").with(USER)).andExpect(forbidden403());
    }

    // SECURITY-T20 (S08)
    @Test
    void T20_userDeleteNonNumericId_returns403Not400() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/abc").with(USER)).andExpect(forbidden403());
    }

    // SECURITY-T21 (S08, S04)
    @Test
    void T21_anonymousDeleteUnknownId_returns401Not404() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/999")).andExpect(challenge401());
    }

    // ---------- Stateless and CSRF (S09) ----------

    // SECURITY-T22 (S09)
    @Test
    void T22_authenticatedRequests_createNoSession() throws Exception {
        MvcResult deleted = mockMvc.perform(delete(PRODUCTS + "/3").with(ADMIN))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn();
        MvcResult listed = mockMvc.perform(get(PRODUCTS).with(USER))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn();

        assertThat(deleted.getRequest().getSession(false)).isNull();
        assertThat(listed.getRequest().getSession(false)).isNull();
    }

    // SECURITY-T23 (S09)
    @Test
    void T23_anonymousUpdateWithoutCsrf_returns200() throws Exception {
        mockMvc.perform(put(PRODUCTS + "/1").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Pen","price":10,"quantity":1}"""))
                .andExpect(status().isOk());
    }

    // ---------- Non-Basic schemes (S10) ----------

    // SECURITY-T24a (S10)
    @Test
    void T24a_bearerOnOpenEndpoint_isAnonymous200() throws Exception {
        mockMvc.perform(get(PRODUCTS).header(HttpHeaders.AUTHORIZATION, "Bearer abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    // SECURITY-T24b (S10)
    @Test
    void T24b_bearerOnDelete_isAnonymous401() throws Exception {
        mockMvc.perform(delete(PRODUCTS + "/1").header(HttpHeaders.AUTHORIZATION, "Bearer abc"))
                .andExpect(challenge401());

        expectProductExists(1);
    }
}
