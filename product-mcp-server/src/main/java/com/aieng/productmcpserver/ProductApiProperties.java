package com.aieng.productmcpserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Backend location and Basic credentials (MCP-SERVER-S03, S04). */
@ConfigurationProperties(prefix = "product-api")
public record ProductApiProperties(String baseUrl, String username, String password) {
}
