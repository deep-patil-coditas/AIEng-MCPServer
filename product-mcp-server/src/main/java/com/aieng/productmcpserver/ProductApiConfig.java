package com.aieng.productmcpserver;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Properties binding and HTTP timeouts: connect 5 s, read 10 s (MCP-SERVER-S13). */
@Configuration
@EnableConfigurationProperties(ProductApiProperties.class)
public class ProductApiConfig {

    static final ClientHttpRequestFactorySettings HTTP_SETTINGS = ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofSeconds(5))
            .withReadTimeout(Duration.ofSeconds(10));

    @Bean
    ClientHttpRequestFactorySettings clientHttpRequestFactorySettings() {
        return HTTP_SETTINGS;
    }
}
