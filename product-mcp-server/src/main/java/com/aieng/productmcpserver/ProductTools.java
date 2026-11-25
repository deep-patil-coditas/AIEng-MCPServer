package com.aieng.productmcpserver;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** The five MCP tools (MCP-SERVER-S02, S05..S10). */
@Component
public class ProductTools {

    private static final String ID = "Product id (positive integer)";
    private static final String NAME = "Product name, 1-100 characters";
    private static final String DESCRIPTION = "Optional description, up to 500 characters";
    private static final String PRICE = "Price greater than 0 with at most 2 decimal places, e.g. 799.00";
    private static final String QUANTITY = "Stock quantity, an integer of 0 or more";

    private final ProductApiClient client;
    private final ObjectMapper objectMapper;

    public ProductTools(ProductApiClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "list_products",
            description = "List all products in the catalogue, ordered by id. Returns a JSON array of products, "
                    + "each with id, name, description, price and quantity.",
            resultConverter = RawTextResultConverter.class)
    public String listProducts() {
        throw new UnsupportedOperationException();
    }

    @Tool(name = "get_product",
            description = "Get one product by its id. Returns the product as JSON, or an error if no product has that id.",
            resultConverter = RawTextResultConverter.class)
    public String getProduct(@ToolParam(description = ID) Long id) {
        throw new UnsupportedOperationException();
    }

    @Tool(name = "create_product",
            description = "Create a new product. The server assigns the id. Product names do not need to be unique. "
                    + "Returns the created product as JSON.",
            resultConverter = RawTextResultConverter.class)
    public String createProduct(@ToolParam(description = NAME) String name,
                                @ToolParam(required = false, description = DESCRIPTION) String description,
                                @ToolParam(description = PRICE) BigDecimal price,
                                @ToolParam(description = QUANTITY) Integer quantity) {
        throw new UnsupportedOperationException();
    }

    @Tool(name = "update_product",
            description = "Replace all fields of an existing product. This is a full replace, not a partial update: "
                    + "omitting description clears it. Fails if no product has that id; it never creates a product. "
                    + "Returns the updated product as JSON.",
            resultConverter = RawTextResultConverter.class)
    public String updateProduct(@ToolParam(description = ID) Long id,
                                @ToolParam(description = NAME) String name,
                                @ToolParam(required = false, description = DESCRIPTION) String description,
                                @ToolParam(description = PRICE) BigDecimal price,
                                @ToolParam(description = QUANTITY) Integer quantity) {
        throw new UnsupportedOperationException();
    }

    @Tool(name = "delete_product",
            description = "Delete a product by its id. This cannot be undone. Requires the MCP server to be configured "
                    + "with ADMIN credentials; otherwise the backend refuses with 401 or 403.",
            resultConverter = RawTextResultConverter.class)
    public String deleteProduct(@ToolParam(description = ID) Long id) {
        throw new UnsupportedOperationException();
    }
}
