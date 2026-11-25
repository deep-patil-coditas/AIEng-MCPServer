package com.aieng.productmcpserver;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Thin typed HTTP client over RestClient for the backend's Product API. */
@Component
public class ProductApiClient {

    public ProductApiClient(RestClient.Builder builder, ProductApiProperties props) {
    }

    public List<Product> list() {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public Product get(Long id) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public Product create(ProductInput input) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public Product update(Long id, ProductInput input) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public void delete(Long id) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
