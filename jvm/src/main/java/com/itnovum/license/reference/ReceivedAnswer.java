package com.itnovum.license.reference;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * An answer as it arrived.
 *
 * @param headers the answer's headers; a header sent more than once is joined with {@code ", "}
 */
public record ReceivedAnswer(int status, Map<String, String> headers, byte[] body) {

    public ReceivedAnswer {
        var lowercase = new TreeMap<String, String>();
        headers.forEach((name, value) -> lowercase.put(name.toLowerCase(Locale.ROOT), value));
        headers = Map.copyOf(lowercase);
        body = body.clone();
    }

    Optional<String> header(String name) {
        return Optional.ofNullable(headers.get(name)).map(String::strip);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
