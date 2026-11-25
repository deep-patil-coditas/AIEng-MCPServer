package com.aieng.productmcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Stdio MCP server exposing the Product API as MCP tools (MCP-SERVER-S01). */
@SpringBootApplication
public class ProductMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductMcpServerApplication.class, args);
    }
}
