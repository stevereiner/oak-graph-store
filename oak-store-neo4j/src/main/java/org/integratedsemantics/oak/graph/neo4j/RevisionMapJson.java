package org.integratedsemantics.oak.graph.neo4j;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.jackrabbit.oak.plugins.document.Revision;
import org.apache.jackrabbit.oak.plugins.document.StableRevisionComparator;

/**
 * JSON encoding of Oak's revision maps (revision to string, long, double, boolean or {@code null}), stored as one
 * string property in Neo4j, which has no map or null property values.
 * <p>
 * Control characters and all surrogates are written as {@code \}{@code uXXXX} escapes: lone surrogates, which UTF-8
 * (Bolt) can't carry, survive.
 */
final class RevisionMapJson {

    private RevisionMapJson() {
        // utility class
    }

    static String write(Map<?, ?> map) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var en : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, en.getKey().toString());
            sb.append(':');
            writeValue(sb, en.getValue());
        }
        return sb.append('}').toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            sb.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("Unsupported map value: " + value);
            }
            sb.append(Double.toString(d)); // always has a '.' or an 'E': read back as a double
        } else {
            throw new IllegalArgumentException("Unsupported map value: " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20 || Character.isSurrogate(c)) {
                sb.append("\\u%04x".formatted((int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Reads a revision map, sorted like in the other document stores (newest first). */
    static SortedMap<Revision, Object> read(String json) {
        var map = new TreeMap<Revision, Object>(StableRevisionComparator.REVERSE);
        var parser = new Parser(json);
        parser.expect('{');
        if (!parser.consume('}')) {
            do {
                var key = parser.readString();
                parser.expect(':');
                map.put(Revision.fromString(key), parser.readValue());
            } while (parser.consume(','));
            parser.expect('}');
        }
        return map;
    }

    private static final class Parser {

        private final String s;

        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void expect(char c) {
            if (!consume(c)) {
                throw new IllegalArgumentException("Expected '" + c + "' at " + pos + " in: " + s);
            }
        }

        boolean consume(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        String readString() {
            expect('"');
            var sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                } else if (c == '\\') {
                    char e = s.charAt(pos++);
                    if (e == 'u') {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    } else {
                        sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object readValue() {
            char c = s.charAt(pos);
            if (c == '"') {
                return readString();
            } else if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            } else if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            } else if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            var number = s.substring(start, pos);
            if (number.isEmpty()) {
                throw new IllegalArgumentException("Unexpected '" + c + "' at " + start + " in: " + s);
            }
            return number.contains(".") || number.contains("e") || number.contains("E") ? Double.valueOf(number)
                    : (Object) Long.valueOf(number);
        }
    }

}
