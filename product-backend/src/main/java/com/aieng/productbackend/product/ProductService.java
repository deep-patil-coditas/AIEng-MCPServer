package com.aieng.productbackend.product;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public ProductResponse create(ProductRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    public List<ProductResponse> findAll() {
        throw new UnsupportedOperationException("not implemented");
    }

    public ProductResponse findById(Long id) {
        throw new UnsupportedOperationException("not implemented");
    }

    public ProductResponse update(Long id, ProductRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    public void delete(Long id) {
        throw new UnsupportedOperationException("not implemented");
    }
}
