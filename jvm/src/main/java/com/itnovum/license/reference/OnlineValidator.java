package com.itnovum.license.reference;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates a license key with the license portal and verifies the signed answer. Every request asks for a signature with a fresh nonce and binds its body with {@code Content-Digest}, and
 * {@link AnswerVerifier} decides what the answer means. A lost connection, a timeout or an answer that doesn't verify is {@link AnswerOutcome#NETWORK_FAILURE}, never an exception.
 */
public final class OnlineValidator {

    private static final String LABEL = "sig1";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final URI tenant;
    private final UUID keyid;
    private final UUID product;
    private final AnswerVerifier verifier;
    private final HttpClient http;

    /**
     * @param tenant the vendor's base URL, which includes its tenant, such as {@code https://license.example.com/api/tenants/acme}
     * @param keyid the compiled-in key to ask for, the vendor's tenant key
     * @param product your product's id, sent with every validation and checked in every answer
     */
    public OnlineValidator(URI tenant, ProductKeys keys, UUID keyid, UUID product, ClockMark clockMark, Duration skew, HttpClient http) {
        this.tenant = tenant;
        this.keyid = keyid;
        this.product = product;
        this.verifier = new AnswerVerifier(keys, product, clockMark, skew);
        this.http = http;
    }

    /**
     * Validates the key now.
     */
    public Validation validate(String licenseKey) {
        var body = body(licenseKey);
        var sent = sent(body);
        try {
            var response = http.send(request(sent, body), HttpResponse.BodyHandlers.ofByteArray());
            var headers = new HashMap<String, String>();
            response.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));

            return verifier.verify(sent, new ReceivedAnswer(response.statusCode(), headers, response.body()), Instant.now());
        } catch (IOException | RuntimeException unreachable) {
            return Validation.NETWORK_FAILURE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();

            return Validation.NETWORK_FAILURE;
        }
    }

    byte[] body(String licenseKey) {
        var body = JSON.createObjectNode().put("key", licenseKey);
        body.putObject("scope").put("product", product.toString());

        return JSON.writeValueAsBytes(body);
    }

    /**
     * What this request sends: a fresh nonce, so an answer recorded earlier can't be replayed, and the digest of its body, so an answer about another key doesn't verify.
     */
    SentRequest sent(byte[] body) {
        var nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        var uri = URI.create(tenant.toString().replaceAll("/+$", "") + "/licenses/actions/validate-key");

        return new SentRequest("POST", uri, LABEL, keyid, Base64.getUrlEncoder().withoutPadding().encodeToString(nonce), Optional.of(contentDigest(body)));
    }

    static HttpRequest request(SentRequest sent, byte[] body) {
        var request = HttpRequest.newBuilder(sent.uri())
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Accept-Signature", sent.acceptSignature())
                .method(sent.method(), HttpRequest.BodyPublishers.ofByteArray(body));
        sent.contentDigest().ifPresent(digest -> request.header("Content-Digest", digest));

        return request.build();
    }

    private static String contentDigest(byte[] body) {
        try {
            return "sha-256=:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body)) + ":";
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("Every JVM has SHA-256", impossible);
        }
    }
}
