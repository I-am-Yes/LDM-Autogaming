package core.app.plugin.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes and decodes JSON values used by the MCP transport without dependencies.
 * Object members retain insertion order. Parsing rejects duplicate keys, trailing
 * data, malformed number or escape syntax, and nesting beyond 64 levels.
 * UTF-8 decoding and HTTP body limits are handled by {@link MCP}.
 */
final class Json {
    private Json() { }

    /**
     * Parses exactly one JSON value, allowing surrounding JSON whitespace.
     *
     * @param text non-null JSON source
     * @return a string-keyed map, list, string, boolean, number, or null; integers
     *         use {@link Long} when possible and other numbers use {@link BigDecimal}
     * @throws IllegalArgumentException if the source is invalid or nested too deeply
     * @throws NullPointerException if {@code text} is null
     */
    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value(0);
        parser.space();
        if (parser.index != text.length()) {
            throw parser.error("Unexpected trailing data");
        }
        return value;
    }

    /**
     * Writes a JSON value without optional formatting whitespace.
     *
     * @param value null, a string, boolean, number, string-keyed map, or iterable
     *             whose elements are themselves supported values
     * @return JSON text with escaped strings
     * @throws IllegalArgumentException for unsupported values, non-string map keys,
     *         non-finite floating-point numbers, or excessive nesting
     */
    static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output, 0);
        return output.toString();
    }

    private static void write(Object value, StringBuilder output, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("JSON nesting exceeds 64 levels");
        }
        if (value == null) {
            output.append("null");
        } else if (value instanceof String string) {
            quote(string, output);
        } else if (value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Number number) {
            if ((number instanceof Double && !Double.isFinite(number.doubleValue()))
                    || (number instanceof Float && !Float.isFinite(number.floatValue()))) {
                throw new IllegalArgumentException("JSON numbers must be finite");
            }
            output.append(number);
        } else if (value instanceof Map<?, ?> object) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                if (!first) output.append(',');
                first = false;
                quote(key, output);
                output.append(':');
                write(entry.getValue(), output, depth + 1);
            }
            output.append('}');
        } else if (value instanceof Iterable<?> items) {
            output.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) output.append(',');
                first = false;
                write(item, output, depth + 1);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
        }
    }

    private static void quote(String value, StringBuilder output) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private Object value(int depth) {
            if (depth > 64) throw error("JSON nesting exceeds 64 levels");
            space();
            if (index == text.length()) throw error("Expected a JSON value");
            return switch (text.charAt(index)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            index++;
            Map<String, Object> object = new LinkedHashMap<>();
            space();
            if (take('}')) return object;
            do {
                space();
                if (index == text.length() || text.charAt(index) != '"') {
                    throw error("Expected an object key");
                }
                String key = string();
                space();
                require(':');
                if (object.containsKey(key)) throw error("Duplicate object key");
                object.put(key, value(depth));
                space();
                if (take('}')) return object;
                require(',');
            } while (true);
        }

        private List<Object> array(int depth) {
            index++;
            List<Object> array = new ArrayList<>();
            space();
            if (take(']')) return array;
            do {
                array.add(value(depth));
                space();
                if (take(']')) return array;
                require(',');
            } while (true);
        }

        private String string() {
            require('"');
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char character = text.charAt(index++);
                if (character == '"') return value.toString();
                if (character < 0x20) throw error("Unescaped control character");
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (index == text.length()) throw error("Incomplete escape sequence");
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"': case '\\': case '/': value.append(escape); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u':
                        if (index + 4 > text.length()) throw error("Incomplete Unicode escape");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = hexDigit(text.charAt(index++));
                            if (digit < 0) throw error("Invalid Unicode escape");
                            code = code * 16 + digit;
                        }
                        value.append((char) code);
                        break;
                    default: throw error("Invalid escape sequence");
                }
            }
            throw error("Unterminated string");
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, index)) throw error("Invalid JSON value");
            index += expected.length();
            return value;
        }

        private Number number() {
            int start = index;
            take('-');
            if (take('0')) {
                if (index < text.length() && digit(text.charAt(index))) {
                    throw error("Leading zero in number");
                }
            } else {
                digits();
            }
            if (take('.')) digits();
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits();
            }
            String number = text.substring(start, index);
            try {
                return Long.valueOf(number);
            } catch (NumberFormatException ignored) {
                try {
                    return new BigDecimal(number);
                } catch (NumberFormatException exception) {
                    throw error("Invalid number");
                }
            }
        }

        private void digits() {
            int start = index;
            while (index < text.length() && digit(text.charAt(index))) index++;
            if (start == index) throw error("Expected a digit");
        }

        private boolean digit(char value) {
            return value >= '0' && value <= '9';
        }

        private int hexDigit(char value) {
            if (value >= '0' && value <= '9') return value - '0';
            if (value >= 'a' && value <= 'f') return value - 'a' + 10;
            if (value >= 'A' && value <= 'F') return value - 'A' + 10;
            return -1;
        }

        private void space() {
            while (index < text.length()) {
                char character = text.charAt(index);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') return;
                index++;
            }
        }

        private boolean take(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!take(expected)) throw error("Expected '" + expected + "'");
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + index);
        }
    }
}
