package com.github.lunar.tools;

import com.github.lunar.io.Json;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The small JSON Schema subset used by Lunar; unsupported constraints fail at registration. */
public final class SchemaValidator {
    private static final int MAX_DEPTH = 32;
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required",
            "additionalProperties", "description", "title", "items", "enum", "minimum",
            "maximum", "default");
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean",
            "object", "array", "null");

    private SchemaValidator() {
    }

    public static final class SchemaException extends IllegalArgumentException {
        public SchemaException(String message) { super(message); }
    }

    /** Definition checking does not try to supply a tool's required arguments. */
    public static void validateDefinition(Map<String, Object> schema) {
        definition(schema, "#", 0);
        if (!"object".equals(schema.get("type"))) {
            throw new SchemaException("#: tool inputSchema must declare type object");
        }
    }

    private static void definition(Map<String, Object> schema, String path, int depth) {
        depth(depth, path);
        for (String key : schema.keySet()) {
            if (!KEYWORDS.contains(key)) {
                throw new SchemaException(path + ": unsupported schema keyword '" + key + "'");
            }
        }
        Object type = schema.get("type");
        if (type != null && (!(type instanceof String) || !TYPES.contains(type))) {
            throw new SchemaException(path + ": unknown schema type '" + type + "'");
        }
        if (schema.containsKey("additionalProperties")
                && !(schema.get("additionalProperties") instanceof Boolean)) {
            throw new SchemaException(path + ": additionalProperties must be a boolean");
        }
        Map<String, Object> properties = properties(schema, path);
        for (var entry : properties.entrySet()) {
            definition(asSchema(entry.getValue(), child(path, entry.getKey())),
                    child(path, entry.getKey()), depth + 1);
        }
        if (schema.containsKey("required")) {
            if (!(schema.get("required") instanceof List<?> required)) {
                throw new SchemaException(path + ": required must be an array");
            }
            for (Object name : required) {
                if (!(name instanceof String) || !properties.containsKey(name)) {
                    throw new SchemaException(path + ": required property is not declared: " + name);
                }
            }
        }
        if ("array".equals(type) && !schema.containsKey("items")) {
            throw new SchemaException(path + ": array schema requires items");
        }
        if (schema.containsKey("items")) {
            if (!"array".equals(type)) {
                throw new SchemaException(path + ": items requires type array");
            }
            definition(asSchema(schema.get("items"), path + "[]"), path + "[]", depth + 1);
        }
        if ((schema.containsKey("properties") || schema.containsKey("required")
                || schema.containsKey("additionalProperties")) && !"object".equals(type)) {
            throw new SchemaException(path + ": object constraints require type object");
        }
        for (String bound : List.of("minimum", "maximum")) {
            if (schema.containsKey(bound)) {
                if (!"integer".equals(type) && !"number".equals(type)) {
                    throw new SchemaException(path + ": " + bound + " requires a numeric type");
                }
                decimal(schema.get(bound), path);
            }
        }
        if (schema.containsKey("minimum") && schema.containsKey("maximum")
                && decimal(schema.get("minimum"), path)
                        .compareTo(decimal(schema.get("maximum"), path)) > 0) {
            throw new SchemaException(path + ": minimum exceeds maximum");
        }
        if (schema.containsKey("enum") && (!(schema.get("enum") instanceof List<?> values)
                || values.isEmpty())) {
            throw new SchemaException(path + ": enum must be a nonempty array");
        }
        if (schema.containsKey("default")) {
            coerce(schema.get("default"), schema, path, depth + 1);
        }
    }

    public static Map<String, Object> validate(Map<String, Object> schema, Object args) {
        Object value = args == null ? Map.of() : args;
        if (!(value instanceof Map<?, ?> map)) {
            throw new SchemaException("arguments must be a JSON object");
        }
        return object(schema, map, "#", 0);
    }

    private static Map<String, Object> object(Map<String, Object> schema, Map<?, ?> source,
            String path, int depth) {
        depth(depth, path);
        Map<String, Object> declared = properties(schema, path);
        boolean allowExtra = Boolean.TRUE.equals(schema.get("additionalProperties"));
        for (Object key : source.keySet()) {
            if (!(key instanceof String) || (!declared.containsKey(key) && !allowExtra)) {
                throw new SchemaException(path + ": unknown property '" + key
                        + "'; accepted properties: " + declared.keySet());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<?> required = schema.get("required") instanceof List<?> list ? list : List.of();
        for (var entry : declared.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> sub = asSchema(entry.getValue(), child(path, name));
            if (source.containsKey(name) || sub.containsKey("default")) {
                out.put(name, coerce(source.containsKey(name) ? source.get(name)
                        : sub.get("default"), sub, child(path, name), depth + 1));
            } else if (required.contains(name)) {
                throw new SchemaException("missing required property '" + child(path, name) + "'");
            }
        }
        if (allowExtra) {
            for (var entry : source.entrySet()) {
                String key = (String) entry.getKey();
                if (!declared.containsKey(key)) {
                    out.put(key, jsonValue(entry.getValue(), child(path, key), depth + 1));
                }
            }
        }
        return out;
    }

    /** Generic containers still admit only bounded JSON values, never arbitrary Java objects. */
    private static Object jsonValue(Object value, String path, int depth) {
        depth(depth, path);
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number) { decimal(value, path); return value; }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                copy.add(jsonValue(list.get(i), path + "[" + i + "]", depth + 1));
            }
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new SchemaException(path + ": JSON object keys must be strings");
                }
                copy.put(key, jsonValue(entry.getValue(), child(path, key), depth + 1));
            }
            return copy;
        }
        throw new SchemaException(path + ": expected JSON value, got " + typeName(value));
    }

    private static Object coerce(Object value, Map<String, Object> schema, String path, int depth) {
        depth(depth, path);
        String type = schema.get("type") instanceof String string ? string : "";
        Object normalized = switch (type) {
            case "string" -> require(value, String.class, path, type);
            case "boolean" -> require(value, Boolean.class, path, type);
            case "integer" -> {
                try {
                    yield decimal(value, path).longValueExact();
                } catch (ArithmeticException fractionalOrOverflow) {
                    throw new SchemaException(path + ": expected integer within the 64-bit range");
                }
            }
            case "number" -> { decimal(value, path); yield value; }
            case "object" -> object(schema, (Map<?, ?>) require(value, Map.class, path, type),
                    path, depth + 1);
            case "array" -> {
                List<?> values = (List<?>) require(value, List.class, path, type);
                Map<String, Object> items = asSchema(schema.get("items"), path + "[]");
                List<Object> out = new ArrayList<>(values.size());
                for (int i = 0; i < values.size(); i++) {
                    out.add(coerce(values.get(i), items, path + "[" + i + "]", depth + 1));
                }
                yield out;
            }
            case "null" -> {
                if (value != null) throw new SchemaException(path + ": expected null");
                yield null;
            }
            case "" -> value;
            default -> throw new SchemaException(path + ": unknown schema type '" + type + "'");
        };
        if (schema.get("enum") instanceof List<?> allowed
                && allowed.stream().noneMatch(item -> equal(item, normalized))) {
            throw new SchemaException(path + ": value is not in enum " + Json.write(allowed));
        }
        if (normalized instanceof Number) {
            BigDecimal number = decimal(normalized, path);
            if (schema.containsKey("minimum")
                    && number.compareTo(decimal(schema.get("minimum"), path)) < 0) {
                throw new SchemaException(path + ": value is below minimum " + schema.get("minimum"));
            }
            if (schema.containsKey("maximum")
                    && number.compareTo(decimal(schema.get("maximum"), path)) > 0) {
                throw new SchemaException(path + ": value is above maximum " + schema.get("maximum"));
            }
        }
        return normalized;
    }

    private static boolean equal(Object a, Object b) {
        return a instanceof Number && b instanceof Number
                ? decimal(a, "enum").compareTo(decimal(b, "enum")) == 0 : Objects.equals(a, b);
    }

    private static BigDecimal decimal(Object value, String path) {
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException nonfinite) {
                // Includes NaN and infinity: JSON numbers must be finite.
            }
        }
        throw new SchemaException(path + ": expected finite number, got " + typeName(value));
    }

    private static Object require(Object value, Class<?> expected, String path, String type) {
        if (!expected.isInstance(value)) {
            throw new SchemaException(path + ": expected " + type + ", got " + typeName(value));
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asSchema(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new SchemaException(path + ": schema must be an object with string keys");
        }
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> properties(Map<String, Object> schema, String path) {
        return schema.containsKey("properties") ? asSchema(schema.get("properties"), path)
                : Map.of();
    }

    private static void depth(int depth, String path) {
        if (depth > MAX_DEPTH) throw new SchemaException(path + ": nesting exceeds " + MAX_DEPTH);
    }

    private static String child(String path, String name) {
        return "#".equals(path) ? name : path + "." + name;
    }

    static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
