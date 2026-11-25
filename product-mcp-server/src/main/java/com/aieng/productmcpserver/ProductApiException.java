package com.aieng.productmcpserver;

/** Its message is the tool-error text the AI client sees (MCP-SERVER-S11..S14). */
public class ProductApiException extends RuntimeException {

    public ProductApiException(String message) {
        super(message);
    }
}
