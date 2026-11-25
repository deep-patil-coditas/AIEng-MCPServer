package com.aieng.productmcpserver;

import java.io.IOException;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** Builds the tool-error text for a backend error response (MCP-SERVER-S11, S12). */
final class ErrorText {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ErrorText() {
    }

    /** {@code <status> <reason>[: <detail>][ (<field>: <msg>; …)]}, with fields sorted by name. */
    static String format(HttpStatusCode status, byte[] body) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String head = known == null ? String.valueOf(status.value()) : status.value() + " " + known.getReasonPhrase();

        JsonNode root = parse(body);
        if (root == null || !root.path("detail").isTextual()) {
            return head;
        }
        StringBuilder text = new StringBuilder(head).append(": ").append(root.get("detail").asText());

        JsonNode errors = root.path("errors");
        if (errors.isObject() && !errors.isEmpty()) {
            Map<String, String> sorted = new TreeMap<>();
            errors.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue().asText()));
            StringJoiner fields = new StringJoiner("; ", " (", ")");
            sorted.forEach((field, message) -> fields.add(field + ": " + message));
            text.append(fields);
        }
        return text.toString();
    }

    private static JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }
}
