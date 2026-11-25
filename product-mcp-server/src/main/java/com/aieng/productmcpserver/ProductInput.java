package com.aieng.productmcpserver;

import java.math.BigDecimal;

/** Request body for POST and PUT; values are passed through unchanged (MCP-SERVER-S10). */
public record ProductInput(String name, String description, BigDecimal price, Integer quantity) {
}
