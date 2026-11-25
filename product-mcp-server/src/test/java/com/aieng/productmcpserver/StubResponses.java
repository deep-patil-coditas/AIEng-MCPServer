package com.aieng.productmcpserver;

/** Stub backend bodies from test.md "Stub backend responses", shared by all layers. */
final class StubResponses {

    private StubResponses() {
    }

    static final String SEED_LIST = "[{\"id\":1,\"name\":\"Wireless Mouse\",\"description\":\"2.4 GHz wireless mouse, black\",\"price\":799.00,\"quantity\":25},"
            + "{\"id\":2,\"name\":\"Mechanical Keyboard\",\"description\":\"Blue switches, full size\",\"price\":2499.00,\"quantity\":10},"
            + "{\"id\":3,\"name\":\"USB-C Hub\",\"description\":\"6-in-1 hub with HDMI\",\"price\":1299.50,\"quantity\":0}]";

    static final String MOUSE = "{\"id\":1,\"name\":\"Wireless Mouse\",\"description\":\"2.4 GHz wireless mouse, black\",\"price\":799.00,\"quantity\":25}";

    static final String STAND_CREATED = "{\"id\":4,\"name\":\"Laptop Stand\",\"description\":null,\"price\":1599.00,\"quantity\":7}";

    static final String MOUSE_PRO = "{\"id\":1,\"name\":\"Wireless Mouse Pro\",\"description\":null,\"price\":899.00,\"quantity\":20}";

    static final String INVALID_3 = "{\"type\":\"about:blank\",\"title\":\"Validation failed\",\"status\":400,"
            + "\"detail\":\"Request has invalid fields\",\"instance\":\"/api/products\","
            + "\"errors\":{\"quantity\":\"must be greater than or equal to 0\",\"name\":\"must not be blank\",\"price\":\"must be greater than 0\"}}";

    static final String INVALID_3_TEXT = "400 Bad Request: Request has invalid fields (name: must not be blank; "
            + "price: must be greater than 0; quantity: must be greater than or equal to 0)";

    static final String UNAUTHORIZED = "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
            + "\"detail\":\"Authentication required\",\"instance\":\"/api/products/1\"}";

    static final String FORBIDDEN = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
            + "\"detail\":\"Access Denied\",\"instance\":\"/api/products/3\"}";

    static final String ADMIN_BASIC = "Basic YWRtaW46YWRtaW4xMjM=";
    static final String USER_BASIC = "Basic dXNlcjp1c2VyMTIz";

    static String notFound(long id) {
        return "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"detail\":\"Product with id " + id
                + " not found\",\"instance\":\"/api/products/" + id + "\"}";
    }

    static String notFoundText(long id) {
        return "404 Not Found: Product with id " + id + " not found";
    }
}
