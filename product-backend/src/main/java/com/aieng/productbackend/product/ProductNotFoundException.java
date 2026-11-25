package com.aieng.productbackend.product;

/** Thrown when a product id does not exist (PRODUCT-S14). */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long id) {
        super("Product with id " + id + " not found");
    }
}
