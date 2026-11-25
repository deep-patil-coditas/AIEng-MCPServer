package com.aieng.productmcpserver;

import java.lang.reflect.Type;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

/** Returns the tool method's String unchanged instead of JSON-encoding it (plan R1). */
class RawTextResultConverter implements ToolCallResultConverter {

    @Override
    public String convert(Object result, Type returnType) {
        return result == null ? "" : result.toString();
    }
}
