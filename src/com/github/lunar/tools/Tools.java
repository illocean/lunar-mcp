package com.github.lunar.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Schema constructors shared by concrete domain tools. */
public final class Tools {
    private Tools() { }
    public static Map<String, Object> object(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (required.length != 0) schema.put("required", List.of(required));
        return schema;
    }
    public static Map<String, Object> string() { return Map.of("type", "string"); }
    public static Map<String, Object> integer(long defaultValue, long min, long max) {
        return Map.of("type", "integer", "default", defaultValue, "minimum", min, "maximum", max);
    }
    public static Map<String, Object> bool(boolean defaultValue) {
        return Map.of("type", "boolean", "default", defaultValue);
    }
    public static Map<String, Object> enumString(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
