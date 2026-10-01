package com.github.lunar.io;

/**
 * Minimal JSON writer plus the top-level field extraction JSON-RPC needs. Deliberately not a
 * general-purpose JSON library: no DOM, no nested deserialization, no pretty printing.
 */
public final class Json {

    /**
     * A JSON-RPC id is echoed back verbatim, so it has to be exactly what JSON can carry: a
     * number, a quoted string, or null. Accepting any bare token here is what let {@code tru} and
     * {@code [1,2]} through and produced a response body that was not valid JSON.
     */
    private static final java.util.regex.Pattern JSON_NUMBER =
            java.util.regex.Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

    private Json() {
    }

    public static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /**
     * Encodes a value decoded by {@link #parse} back to JSON. This is the round-trip half of the
     * typed model, and it is what lets a tool return real objects instead of pre-quoted strings.
     */
    public static String write(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return quote(s);
        }
        if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            return value.toString();
        }
        if (value instanceof Double d) {
            // NaN and Infinity are not JSON, and a double that is integral must not become 1.0
            // when the schema said integer.
            if (d.isNaN() || d.isInfinite()) {
                return "null";
            }
            return d == Math.rint(d) && Math.abs(d) < 1e15
                    ? Long.toString(d.longValue()) : d.toString();
        }
        if (value instanceof Number n) {
            String encoded = n.toString();
            if (!JSON_NUMBER.matcher(encoded).matches()) {
                throw new IllegalArgumentException("non-finite JSON number");
            }
            return encoded;
        }
        if (value instanceof java.util.Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(quote(String.valueOf(e.getKey()))).append(':').append(write(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (value instanceof java.util.Collection<?> c) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Object o : c) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append(write(o));
            }
            return b.append(']').toString();
        }
        throw new IllegalArgumentException("cannot encode " + value.getClass().getName());
    }

    /** kv is alternating key, already-encoded value. */
    public static String obj(String... kv) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                b.append(',');
            }
            b.append(quote(kv[i])).append(':').append(kv[i + 1]);
        }
        return b.append('}').toString();
    }

    public static String arr(String... items) {
        return "[" + String.join(",", items) + "]";
    }

    /** Top-level fields of a JSON-RPC request. */
    public static final class Request {
        /** Decoded method name, or null when the member is absent. */
        public final String method;
        /** Raw id token (e.g. {@code 1}, {@code "abc"}, {@code null}) or null when absent. */
        public final String id;
        /**
         * Raw text of the {@code params} value, or null when absent. Raw rather than decoded
         * because a tools/call argument is only worth parsing once its schema is known, and
         * because re-parsing the whole body per member is what this class exists to avoid.
         */
        public final String params;
        /** Already parsed document, reused by the HTTP handler. */
        public final java.util.Map<?, ?> message;

        Request(String method, String id, String params, java.util.Map<?, ?> message) {
            this.method = method;
            this.id = id;
            this.params = params;
            this.message = message;
        }

        public boolean isNotification() {
            return id == null;
        }
    }

    /** Returns null when the body is not a well-formed JSON object. */
    public static Request request(String body) {
        if (body == null) {
            return null;
        }
        try {
            if (!(parse(body) instanceof java.util.Map<?, ?> message)) {
                return null;
            }
            int i = skipWs(body, 0);
            if (i >= body.length() || body.charAt(i) != '{') {
                return null;
            }
            i = skipWs(body, ++i);
            String method = null;
            String id = null;
            String params = null;
            if (i < body.length() && body.charAt(i) == '}') {
                i++;
            } else {
                while (true) {
                    if (i >= body.length() || body.charAt(i) != '"') {
                        return null;
                    }
                    StringBuilder key = new StringBuilder();
                    i = readString(body, i + 1, key);
                    i = skipWs(body, i);
                    if (i >= body.length() || body.charAt(i) != ':') {
                        return null;
                    }
                    int start = skipWs(body, i + 1);
                    i = readValue(body, start);
                    String raw = body.substring(start, i);
                    if ("method".contentEquals(key)) {
                        if (!raw.isEmpty() && raw.charAt(0) == '"') {
                            StringBuilder v = new StringBuilder();
                            readString(body, start + 1, v);
                            method = v.toString();
                        }
                    } else if ("id".contentEquals(key)) {
                        if (!isValidId(raw)) {
                            return null;
                        }
                        id = raw;
                    } else if ("params".contentEquals(key)) {
                        params = raw;
                    }
                    i = skipWs(body, i);
                    if (i >= body.length()) {
                        return null;
                    }
                    char c = body.charAt(i);
                    if (c == ',') {
                        i = skipWs(body, i + 1);
                        continue;
                    }
                    if (c == '}') {
                        i++;
                        break;
                    }
                    return null;
                }
            }
            return skipWs(body, i) == body.length() ? new Request(method, id, params, message) : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Decodes a JSON document into plain Java values: {@link java.util.LinkedHashMap},
     * {@link java.util.ArrayList}, {@link String}, {@link Long}, {@link Double}, {@link Boolean},
     * or null.
     *
     * <p>Numbers keep their declared type rather than being widened to double: that is the whole
     * point, because a schema that says {@code integer} must be able to reject {@code 1.5} and a
     * schema that says {@code number} must accept it. The predecessor handed every argument to a
     * tool as a String, so its schema was decoration.
     *
     * @throws IllegalArgumentException on anything malformed. The caller turns that into a
     *     schema-shaped error rather than a stack trace.
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no value");
        }
        Cursor c = new Cursor(text);
        c.ws();
        Object v = c.value(0);
        c.ws();
        if (c.i != c.s.length()) {
            throw new IllegalArgumentException("trailing content at offset " + c.i);
        }
        return v;
    }

    /** Mutable parse position. Local to parsing, so it does not need to be thread-safe. */
    private static final class Cursor {
        private final String s;
        private int i;

        Cursor(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t'
                    || s.charAt(i) == '\n' || s.charAt(i) == '\r')) {
                i++;
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return s.charAt(i);
        }

        Object value(int depth) {
            if (depth > 64) {
                throw new IllegalArgumentException("nesting deeper than 64 levels");
            }
            char c = peek();
            switch (c) {
                case '{' -> {
                    java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
                    i++;
                    ws();
                    if (peek() == '}') {
                        i++;
                        return m;
                    }
                    while (true) {
                        ws();
                        if (peek() != '"') {
                            throw new IllegalArgumentException("object key is not a string at " + i);
                        }
                        StringBuilder k = new StringBuilder();
                        // readString returns the index just past the closing quote. The decoded
                        // length differs from the raw length when escapes are present, so the
                        // return value is the only correct way to advance.
                        i = readString(s, i + 1, k);
                        ws();
                        if (peek() != ':') {
                            throw new IllegalArgumentException("expected : at offset " + i);
                        }
                        i++;
                        ws();
                        String key = k.toString();
                        if (m.containsKey(key)) {
                            throw new IllegalArgumentException("duplicate object key: " + key);
                        }
                        m.put(key, value(depth + 1));
                        ws();
                        char d = peek();
                        if (d == ',') {
                            i++;
                            continue;
                        }
                        if (d == '}') {
                            i++;
                            return m;
                        }
                        throw new IllegalArgumentException("expected , or } at offset " + i);
                    }
                }
                case '[' -> {
                    java.util.ArrayList<Object> a = new java.util.ArrayList<>();
                    i++;
                    ws();
                    if (peek() == ']') {
                        i++;
                        return a;
                    }
                    while (true) {
                        ws();
                        a.add(value(depth + 1));
                        ws();
                        char d = peek();
                        if (d == ',') {
                            i++;
                            continue;
                        }
                        if (d == ']') {
                            i++;
                            return a;
                        }
                        throw new IllegalArgumentException("expected , or ] at offset " + i);
                    }
                }
                case '"' -> {
                    StringBuilder out = new StringBuilder();
                    i = readString(s, i + 1, out);
                    return out.toString();
                }
                default -> {
                    return scalar();
                }
            }
        }

        private Object scalar() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            boolean real = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    real = true;
                    i++;
                } else {
                    break;
                }
            }
            String token = s.substring(start, i);
            if (!JSON_NUMBER.matcher(token).matches()) {
                throw new IllegalArgumentException("not a value at offset " + start);
            }
            try {
                if (!real) {
                    return Long.valueOf(token);
                }
                double number = Double.parseDouble(token);
                if (!Double.isFinite(number)) {
                    throw new IllegalArgumentException("number outside supported range");
                }
                return number;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a number: " + token);
            }
        }
    }

    private static boolean isValidId(String raw) {
        if (raw.equals("null") || (raw.length() > 1 && raw.charAt(0) == '"'
                && raw.charAt(raw.length() - 1) == '"')) {
            return true;
        }
        return JSON_NUMBER.matcher(raw).matches();
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            i++;
        }
        return i;
    }

    /** Index just past the value starting at {@code i}. */
    private static int readValue(String s, int i) {
        if (i >= s.length()) {
            throw new IllegalStateException("missing value");
        }
        char c = s.charAt(i);
        if (c == '"') {
            return readString(s, i + 1, new StringBuilder());
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == '"') {
                    i = readString(s, i + 1, new StringBuilder());
                    continue;
                }
                if (d == '{' || d == '[') {
                    depth++;
                } else if (d == '}' || d == ']') {
                    if (--depth == 0) {
                        return i + 1;
                    }
                }
                i++;
            }
            throw new IllegalStateException("unterminated composite");
        }
        int start = i;
        while (i < s.length()) {
            char d = s.charAt(i);
            if (d == ',' || d == '}' || d == ']' || d == ' ' || d == '\t' || d == '\n' || d == '\r') {
                break;
            }
            i++;
        }
        if (i == start) {
            throw new IllegalStateException("missing value");
        }
        return i;
    }

    /** {@code i} points just past the opening quote; returns the index just past the closing quote. */
    private static int readString(String s, int i, StringBuilder out) {
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return i;
            }
            if (c == '\\') {
                if (i >= s.length()) {
                    break;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new IllegalStateException("truncated \\u escape");
                        }
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new IllegalStateException("bad escape \\" + e);
                }
            } else if (c < 0x20) {
                throw new IllegalStateException("raw control character in string");
            } else {
                out.append(c);
            }
        }
        throw new IllegalStateException("unterminated string");
    }
}
