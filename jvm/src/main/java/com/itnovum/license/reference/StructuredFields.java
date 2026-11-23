package com.itnovum.license.reference;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an RFC 8941 dictionary, the form of {@code Signature-Input}, {@code Signature} and {@code Content-Digest}. A value is a {@link String}, a {@code byte[]}, a {@link Long} or
 * {@link BigDecimal}, a {@link Token} or a {@link Boolean}, or an inner list of {@link Item}s. A field that doesn't parse throws {@link IllegalArgumentException}, and the caller treats the answer
 * as a network failure: never guess at a header the signature depends on.
 */
final class StructuredFields {

    /**
     * A dictionary member, with its value's text exactly as the field held it, which a signature base repeats.
     */
    record Member(Object value, Map<String, Object> parameters, String text) {
    }

    record Item(Object value, Map<String, Object> parameters) {
    }

    record Token(String value) {
    }

    private final String input;
    private int at;

    private StructuredFields(String input) {
        this.input = input;
    }

    static Map<String, Member> dictionary(String field) {
        var parser = new StructuredFields(field);
        var members = new LinkedHashMap<String, Member>();
        parser.spaces();
        while (!parser.done()) {
            var key = parser.key();
            var start = parser.at;
            Object value = Boolean.TRUE;
            if (parser.peek() == '=') {
                parser.at++;
                start = parser.at;
                value = parser.peek() == '(' ? parser.innerList() : parser.bareItem();
            }

            var parameters = parser.parameters();
            members.put(key, new Member(value, parameters, field.substring(start, parser.at)));
            parser.whitespace();
            if (parser.done()) {
                break;
            }

            parser.expect(',');
            parser.whitespace();
            if (parser.done()) {
                throw new IllegalArgumentException("A dictionary ends with a comma");
            }
        }

        return members;
    }

    private List<Item> innerList() {
        expect('(');
        var items = new ArrayList<Item>();
        while (true) {
            spaces();
            if (peek() == ')') {
                at++;
                return items;
            }

            items.add(new Item(bareItem(), parameters()));
            if (peek() != ' ' && peek() != ')') {
                throw new IllegalArgumentException("Inner list items are separated by spaces");
            }
        }
    }

    private Map<String, Object> parameters() {
        var parameters = new LinkedHashMap<String, Object>();
        while (peek() == ';') {
            at++;
            spaces();
            var key = key();
            Object value = Boolean.TRUE;
            if (peek() == '=') {
                at++;
                value = bareItem();
            }

            parameters.put(key, value);
        }

        return parameters;
    }

    private Object bareItem() {
        var c = peek();
        if (c == '-' || Character.isDigit(c)) {
            return number();
        }

        if (c == '"') {
            return string();
        }

        if (c == ':') {
            return bytes();
        }

        if (c == '?') {
            at++;
            var flag = next();
            if (flag != '0' && flag != '1') {
                throw new IllegalArgumentException("A boolean is ?0 or ?1");
            }

            return flag == '1';
        }

        if (c == '*' || isAlpha(c)) {
            var start = at++;
            while (!done() && (isTokenChar(peek()) || peek() == ':' || peek() == '/')) {
                at++;
            }

            return new Token(input.substring(start, at));
        }

        throw new IllegalArgumentException("Not an item");
    }

    private Object number() {
        var start = at;
        if (peek() == '-') {
            at++;
        }

        while (!done() && (Character.isDigit(peek()) || peek() == '.')) {
            at++;
        }

        var text = input.substring(start, at);
        if (!text.matches("-?\\d{1,15}|-?\\d{1,12}\\.\\d{1,3}")) {
            throw new IllegalArgumentException("Not a number");
        }

        return text.contains(".") ? new BigDecimal(text) : Long.valueOf(text);
    }

    private String string() {
        expect('"');
        var text = new StringBuilder();
        while (true) {
            var c = next();
            if (c == '"') {
                return text.toString();
            }

            if (c == '\\') {
                c = next();
                if (c != '"' && c != '\\') {
                    throw new IllegalArgumentException("A string escapes only \" and \\");
                }
            } else if (c < 0x20 || c > 0x7e) {
                throw new IllegalArgumentException("A string holds printable ASCII only");
            }

            text.append(c);
        }
    }

    private byte[] bytes() {
        expect(':');
        var end = input.indexOf(':', at);
        if (end < 0) {
            throw new IllegalArgumentException("A byte sequence ends with a colon");
        }

        var encoded = input.substring(at, end);
        at = end + 1;

        return Base64.getDecoder().decode(encoded);
    }

    private String key() {
        var start = at;
        var c = peek();
        if (c != '*' && (c < 'a' || c > 'z')) {
            throw new IllegalArgumentException("Not a key");
        }

        at++;
        while (!done() && (isLowerAlphaOrDigit(peek()) || "_-.*".indexOf(peek()) >= 0)) {
            at++;
        }

        return input.substring(start, at);
    }

    private void expect(char c) {
        if (next() != c) {
            throw new IllegalArgumentException("Expected " + c);
        }
    }

    private char next() {
        if (done()) {
            throw new IllegalArgumentException("The field ends too early");
        }

        return input.charAt(at++);
    }

    private char peek() {
        return done() ? '\0' : input.charAt(at);
    }

    private boolean done() {
        return at >= input.length();
    }

    private void spaces() {
        while (peek() == ' ') {
            at++;
        }
    }

    private void whitespace() {
        while (peek() == ' ' || peek() == '\t') {
            at++;
        }
    }

    private static boolean isAlpha(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean isLowerAlphaOrDigit(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }

    private static boolean isTokenChar(char c) {
        return c > 0x20 && c < 0x7f && "\"(),;<=>?@[\\]{}".indexOf(c) < 0;
    }
}
