package com.aieng.productmcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/** Layer 2: application context (MCP-SERVER-T35, T36). */
@SpringBootTest(properties = "spring.ai.mcp.server.enabled=false")
class ProductMcpContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private ProductApiProperties properties;

    @Test
    void T35_nonWebContext_withServerNameAndVersion() {
        assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
        assertThat(environment.getProperty("spring.ai.mcp.server.name")).isEqualTo("product-mcp-server");
        assertThat(environment.getProperty("spring.ai.mcp.server.version")).isEqualTo("0.0.1");
    }

    @Test
    void T36_defaultConfiguration() {
        boolean anyEnvSet = System.getenv().keySet().stream().anyMatch(k -> k.startsWith("PRODUCT_API_"));
        assumeTrue(!anyEnvSet, "PRODUCT_API_* env var set in the build environment (TD-09)");

        assertThat(properties.baseUrl()).isEqualTo("http://localhost:8080");
        assertThat(properties.username()).isNullOrEmpty();
        assertThat(properties.password()).isNullOrEmpty();
    }
}
