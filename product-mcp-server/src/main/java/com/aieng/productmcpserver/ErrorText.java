package com.aieng.productmcpserver;

import org.springframework.http.HttpStatusCode;

/** Builds the tool-error text for a backend error response (MCP-SERVER-S11, S12). */
final class ErrorText {

    private ErrorText() {
    }

    static String format(HttpStatusCode status, byte[] body) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
