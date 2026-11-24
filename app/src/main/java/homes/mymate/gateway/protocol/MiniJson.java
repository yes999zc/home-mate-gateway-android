package homes.mymate.gateway.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zero-dependency RFC 8259 JSON parser and serializer for Home Mate Northbound Protocol v2.
 * Preserves insertion order using LinkedHashMap and compiles cleanly on both standard JVM and Android DEX.
 */
public final class MiniJson {

    private MiniJson() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object parsed = parse(json);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("Expected JSON object but got: " + (parsed == null ? "null" : parsed.getClass().getSimpleName()));
        }
        return (Map<String, Object>) parsed;
    }

    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input cannot be null");
        }
        Parser p = new Parser(json);
        Object value = p.readValue();
        p.skipWhitespace();
        if (!p.isAtEnd()) {
            throw new IllegalArgumentException("Trailing characters at index " + p.pos);
        }
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            sb.append(((Number) value).longValue());
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("0.0");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15 && !(value instanceof Float || value instanceof Double)) {
                sb.append((long) d);
            } else {
                String formatted = Double.toString(d);
                if (formatted.endsWith(".0")) {
                    // Preserve float representation when intentional, or keep clean decimal
                    sb.append(formatted);
                } else {
                    sb.append(formatted);
                }
            }
        } else if (value instanceof Map) {
            sb.append('{');
            Map<Object, Object> map = (Map<Object, Object>) value;
            boolean first = true;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else if (value.getClass().isArray()) {
            sb.append('[');
            Object[] arr = (Object[]) value;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, arr[i]);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }

    public static String getString(Map<String, Object> map, String key, String defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Object v = map.get(key);
        return v != null ? String.valueOf(v) : defaultValue;
    }

    public static long getLong(Map<String, Object> map, String key, long defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static int getInt(Map<String, Object> map, String key, int defaultValue) {
        return (int) getLong(map, key, defaultValue);
    }

    public static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Object v = map.get(key);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getObject(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getArray(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v instanceof List) {
            return (List<Object>) v;
        }
        return null;
    }

    public static List<Object> getList(Map<String, Object> obj, String key) {
        return getArray(obj, key);
    }

    private static final class Parser {
        private final String src;
        private final int len;
        private int pos;

        Parser(String src) {
            this.src = src;
            this.len = src.length();
            this.pos = 0;
        }

        boolean isAtEnd() {
            return pos >= len;
        }

        void skipWhitespace() {
            while (pos < len) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWhitespace();
            if (pos >= len) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                case 'f':
                    return readBoolean();
                case 'n':
                    return readNull();
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw new IllegalArgumentException("Unexpected char '" + c + "' at index " + pos);
            }
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            pos++; // consume '{'
            skipWhitespace();
            if (pos < len && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (pos >= len || src.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected string key at index " + pos);
                }
                String key = readString();
                skipWhitespace();
                if (pos >= len || src.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':' after key at index " + pos);
                }
                pos++; // consume ':'
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                if (pos >= len) {
                    throw new IllegalArgumentException("Unterminated object");
                }
                char next = src.charAt(pos);
                if (next == '}') {
                    pos++;
                    return map;
                } else if (next == ',') {
                    pos++;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at index " + pos);
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<Object>();
            pos++; // consume '['
            skipWhitespace();
            if (pos < len && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = readValue();
                list.add(value);
                skipWhitespace();
                if (pos >= len) {
                    throw new IllegalArgumentException("Unterminated array");
                }
                char next = src.charAt(pos);
                if (next == ']') {
                    pos++;
                    return list;
                } else if (next == ',') {
                    pos++;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at index " + pos);
                }
            }
        }

        String readString() {
            pos++; // consume opening '"'
            int start = pos;
            while (pos < len) {
                char c = src.charAt(pos);
                if (c == '"') {
                    String fast = src.substring(start, pos);
                    pos++;
                    return fast;
                }
                if (c == '\\') {
                    break;
                }
                pos++;
            }
            StringBuilder sb = new StringBuilder(Math.max(32, (pos - start) + 32));
            if (pos > start) {
                sb.append(src, start, pos);
            }
            while (pos < len) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                } else if (c == '\\') {
                    if (pos >= len) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > len) {
                                throw new IllegalArgumentException("Invalid unicode escape at index " + pos);
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown escape '\\" + esc + "' at index " + pos);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        Boolean readBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            } else if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean at index " + pos);
        }

        Object readNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null at index " + pos);
        }

        Number readNumber() {
            int start = pos;
            boolean isFloat = false;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            while (pos < len) {
                char c = src.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloat = true;
                    pos++;
                } else {
                    break;
                }
            }
            String token = src.substring(start, pos);
            if (isFloat) {
                return Double.valueOf(Double.parseDouble(token));
            } else {
                try {
                    return Long.valueOf(Long.parseLong(token));
                } catch (NumberFormatException e) {
                    return Double.valueOf(Double.parseDouble(token));
                }
            }
        }
    }
}
