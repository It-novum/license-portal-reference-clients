package com.itnovum.license.reference;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A response as it arrived.
 *
 * @param headers the response's headers; a header sent more than once is joined with {@code ", "}
 */
public record ReceivedResponse(int status, Map<String, String> headers, byte[] body) {

    public ReceivedResponse {
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
