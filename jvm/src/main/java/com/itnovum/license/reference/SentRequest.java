package com.itnovum.license.reference;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * What a request asked for and what it sent, kept so its answer is checked against the request itself and never against anything the answer says about it.
 *
 * @param uri the request's full URI, exactly as sent
 * @param label the label the request asked the signature under, such as {@code sig1}
 * @param keyid the compiled-in key the request named
 * @param nonce the fresh nonce the request sent
 * @param contentDigest the {@code Content-Digest} the request sent, if any
 */
public record SentRequest(String method, URI uri, String label, UUID keyid, String nonce, Optional<String> contentDigest) {

    /**
     * The value of {@code Accept-Signature}. The server fixes what it signs, so the request lists no components.
     */
    public String acceptSignature() {
        return label + "=();keyid=\"" + keyid + "\";nonce=\"" + nonce + "\"";
    }

    /**
     * The host the request went to, in lowercase, with its port only when it isn't the scheme's default.
     */
    String authority() {
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        var port = uri.getPort();
        var defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;

        return port < 0 || port == defaultPort ? host : host + ":" + port;
    }
}
