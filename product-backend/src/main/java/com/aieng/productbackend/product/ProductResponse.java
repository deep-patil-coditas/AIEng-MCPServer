package com.aieng.productbackend.product;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, String description, BigDecimal price, Integer quantity) {

    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getDescription(), p.getPrice(), p.getQuantity());
    }
}
