package com.aieng.productbackend.product;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    /** PRODUCT-S01, S06, S07, S13: any id in the request is ignored; the database generates it. */
    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(request.name(), request.description(), scaled(request.price()), request.quantity());
        return ProductResponse.from(repository.save(product));
    }

    /** PRODUCT-S02: every product, ordered by id ascending. */
    public List<ProductResponse> findAll() {
        return repository.findAll(Sort.by("id")).stream().map(ProductResponse::from).toList();
    }

    /** PRODUCT-S03, S14. */
    public ProductResponse findById(Long id) {
        return ProductResponse.from(load(id));
    }

    /** PRODUCT-S04, S14: full replace; the path id wins. */
    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = load(id);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(scaled(request.price()));
        product.setQuantity(request.quantity());
        return ProductResponse.from(repository.saveAndFlush(product));
    }

    /** PRODUCT-S05, S14. */
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ProductNotFoundException(id);
        }
        repository.deleteById(id);
    }

    private Product load(Long id) {
        return repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    /** PRODUCT-S13: always 2 decimals. @Digits(fraction = 2) guarantees no rounding is needed. */
    private static BigDecimal scaled(BigDecimal price) {
        return price.setScale(2, RoundingMode.UNNECESSARY);
    }
}
