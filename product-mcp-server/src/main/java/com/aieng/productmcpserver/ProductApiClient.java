package com.aieng.productmcpserver;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.util.StringUtils.hasText;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Thin typed HTTP client over RestClient for the backend's Product API (MCP-SERVER-S03..S09, S11..S13). */
@Component
public class ProductApiClient {

    private static final String PRODUCTS = "/api/products";
    private static final String PRODUCT = "/api/products/{id}";

    private final String baseUrl;
    private final RestClient restClient;

    public ProductApiClient(RestClient.Builder builder, ProductApiProperties props) {
        this.baseUrl = props.baseUrl().replaceAll("/+$", "");                       // S03, MD-12
        RestClient.Builder b = builder.baseUrl(baseUrl)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {   // S11, S12
                    throw new ProductApiException(
                            ErrorText.format(response.getStatusCode(), response.getBody().readAllBytes()));
                });
        if (hasText(props.username()) && hasText(props.password())) {                // S04, MD-05
            b.defaultHeaders(h -> h.setBasicAuth(props.username(), props.password()));
        }
        this.restClient = b.build();
    }

    public List<Product> list() {
        return call(() -> restClient.get().uri(PRODUCTS).accept(APPLICATION_JSON)
                .retrieve().body(new ParameterizedTypeReference<List<Product>>() {
                }));
    }

    public Product get(Long id) {
        return call(() -> restClient.get().uri(PRODUCT, id).accept(APPLICATION_JSON)
                .retrieve().body(Product.class));
    }

    public Product create(ProductInput input) {
        return call(() -> restClient.post().uri(PRODUCTS).accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).body(input)
                .retrieve().body(Product.class));
    }

    public Product update(Long id, ProductInput input) {
        return call(() -> restClient.put().uri(PRODUCT, id).accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON).body(input)
                .retrieve().body(Product.class));
    }

    public void delete(Long id) {
        call(() -> restClient.delete().uri(PRODUCT, id).retrieve().toBodilessEntity());
    }

    /** Connection failures and timeouts become "unreachable"; the cause is not chained into the text (S13, S04). */
    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new ProductApiException("Product API unreachable at " + baseUrl);
        }
    }
}
