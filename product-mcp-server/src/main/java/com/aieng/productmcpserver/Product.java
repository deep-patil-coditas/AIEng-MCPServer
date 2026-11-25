package com.aieng.productmcpserver;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A product as returned by the backend; component order is the JSON field order (MCP-SERVER-S16). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Product(Long id, String name, String description, BigDecimal price, Integer quantity) {
}
