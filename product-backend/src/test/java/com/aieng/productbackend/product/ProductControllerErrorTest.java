package com.aieng.productbackend.product;

import com.aieng.productbackend.security.ProblemDetailAccessDeniedHandler;
import com.aieng.productbackend.security.ProblemDetailAuthenticationEntryPoint;
import com.aieng.productbackend.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Unexpected-error handling (PRODUCT-S19). */
@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class, ProblemDetailAccessDeniedHandler.class})
class ProductControllerErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    // PRODUCT-T43
    @Test
    void T43_unexpectedException_returns500WithoutLeakingMessage() throws Exception {
        when(productService.findAll()).thenThrow(new RuntimeException("db exploded"));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andExpect(jsonPath("$.instance").value("/api/products"))
                .andExpect(content().string(not(containsString("db exploded"))));
    }
}
