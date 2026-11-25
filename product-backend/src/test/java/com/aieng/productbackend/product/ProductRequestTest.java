package com.aieng.productbackend.product;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for ProductRequest normalisation (PRODUCT-S09). */
class ProductRequestTest {

    // PRODUCT-T39
    @Test
    void T39_trimsNameAndDescription() {
        ProductRequest request = new ProductRequest("  Pen  ", "  Blue ink  ", BigDecimal.TEN, 1);

        assertThat(request.name()).isEqualTo("Pen");
        assertThat(request.description()).isEqualTo("Blue ink");
    }

    // PRODUCT-T40
    @Test
    void T40_blankDescriptionBecomesNull() {
        ProductRequest request = new ProductRequest("Pen", "   ", BigDecimal.TEN, 1);

        assertThat(request.description()).isNull();
    }

    // PRODUCT-T41
    @Test
    void T41_nullDescriptionStaysNull() {
        ProductRequest request = new ProductRequest("Pen", null, BigDecimal.TEN, 1);

        assertThat(request.description()).isNull();
    }

    // PRODUCT-T42
    @Test
    void T42_allNullFieldsDoNotThrow() {
        ProductRequest request = new ProductRequest(null, null, null, null);

        assertThat(request.name()).isNull();
        assertThat(request.description()).isNull();
        assertThat(request.price()).isNull();
        assertThat(request.quantity()).isNull();
    }
}
