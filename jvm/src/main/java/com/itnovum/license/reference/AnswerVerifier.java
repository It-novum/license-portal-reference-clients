package com.itnovum.license.reference;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks an answer from the license portal against the request that asked for it. The checks run in a fixed order and the first that fails decides, so an answer has exactly one outcome, the one the
 * conformance vectors give. It needs no network, so it can be tested with the vectors alone; {@link OnlineValidator} sends the request and hands both here.
 *
 * <p>
 * Everything about the request comes from {@link SentRequest}, what this client sent. The answer could have been made for another request, so nothing it says about the request is believed: a
 * signature over another method, host, path, query or body simply doesn't verify.
 */
public final class AnswerVerifier {

    private static final String ED25519 = "Ed25519";
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final ProductKeys keys;
    private final UUID product;
    private final ClockMark clockMark;
    private final Duration skew;

    /**
     * @param product your product's id, the one its licenses name
     * @param skew how far the answer's {@code Date} may be from {@code now}. Five minutes suits most machines
     */
    public AnswerVerifier(ProductKeys keys, UUID product, ClockMark clockMark, Duration skew) {
        this.keys = keys;
        this.product = product;
        this.clockMark = clockMark;
        this.skew = skew;
    }

    /**
     * Checks the answer at {@code now}. Anything that doesn't verify comes back as {@link AnswerOutcome#NETWORK_FAILURE}, never as an exception.
     */
    public Validation verify(SentRequest sent, ReceivedAnswer answer, Instant now) {
        try {
            return check(sent, answer, now);
        } catch (Unverified | RuntimeException unverified) {
            return Validation.NETWORK_FAILURE;
        }
    }

    private Validation check(SentRequest sent, ReceivedAnswer answer, Instant now) throws Unverified {
        // A 429 or a 5xx says nothing about the license, signed or not.
        var status = answer.status();
        if (status == 429 || status >= 500) {
            throw new Unverified();
        }

        // A signature under the label this client asked for. Without one, the answer may come from anyone.
        var input = StructuredFields.dictionary(answer.header("signature-input").orElseThrow(Unverified::new)).get(sent.label());
        var signature = StructuredFields.dictionary(answer.header("signature").orElseThrow(Unverified::new)).get(sent.label());
        if (input == null || signature == null || !(input.value() instanceof List<?> components) || !(signature.value() instanceof byte[] signed)) {
            throw new Unverified();
        }

        // Exactly the components the server fixes, so nothing this client relies on is left out.
        var expected = new ArrayList<>(List.of("\"@method\";req", "\"@authority\";req", "\"@path\";req", "\"@query\";req"));
        sent.contentDigest().ifPresent(digest -> expected.add("\"content-digest\";req"));
        expected.addAll(List.of("\"@status\"", "\"content-digest\"", "\"date\""));
        if (!expected.equals(rendered(components))) {
            throw new Unverified();
        }

        // The key and the nonce this client sent, so an answer to another request, or replayed from an earlier one, fails here.
        var parameters = input.parameters();
        if (!(parameters.get("created") instanceof Long) || !sent.keyid().toString().equals(parameters.get("keyid")) || !sent.nonce().equals(parameters.get("nonce"))) {
            throw new Unverified();
        }

        // The body the answer's Content-Digest names, which the signature covers in its turn.
        var body = answer.body();
        var digest = answer.header("content-digest").orElseThrow(Unverified::new);
        var sha256 = StructuredFields.dictionary(digest).get("sha-256");
        if (sha256 == null || !(sha256.value() instanceof byte[] claimed) || !MessageDigest.isEqual(claimed, sha256(body))) {
            throw new Unverified();
        }

        // The signature, over the base rebuilt from what this client sent and the answer's own status, digest and date.
        var date = answer.header("date").orElseThrow(Unverified::new);
        var values = new ArrayList<>(List.of(sent.method(), sent.authority(), sent.uri().getRawPath(), "?" + Optional.ofNullable(sent.uri().getRawQuery()).orElse("")));
        sent.contentDigest().ifPresent(values::add);
        values.addAll(List.of(String.valueOf(status), digest, date));
        var base = new StringBuilder();
        for (int i = 0; i < expected.size(); i++) {
            base.append(expected.get(i)).append(": ").append(values.get(i)).append('\n');
        }

        base.append("\"@signature-params\": ").append(input.text());
        verifySignature(sent.keyid(), base.toString(), signed);

        // A Date far from now means the answer is old, or this clock is off; either way it proves nothing about the license now. A verified Date is a time this application has seen.
        var dated = instant(date);
        if (Duration.between(dated, now).abs().compareTo(skew) > 0) {
            throw new Unverified();
        }

        clockMark.raise(dated);

        // A verified refusal of the request itself, which a retry won't change.
        var json = object(body);
        if (status >= 400 && status < 500) {
            return new Validation(AnswerOutcome.ERROR, json.map(node -> node.path("code")).filter(JsonNode::isString).map(JsonNode::asString), Optional.empty());
        }

        if (status != 200 || json.isEmpty() || !json.get().path("valid").isBoolean() || !json.get().path("code").isString()) {
            throw new Unverified();
        }

        var valid = json.get().path("valid").asBoolean();
        var code = Optional.of(json.get().path("code").asString());
        var license = license(json.get().path("license"));
        if (valid && license.isEmpty()) {
            throw new Unverified();
        }

        // The product, because the vendor's keys sign answers about every product it sells.
        if (license.filter(held -> !product.equals(held.product())).isPresent()) {
            return new Validation(AnswerOutcome.WRONG_PRODUCT, code, Optional.empty());
        }

        // Decide on valid, never on the code, so a code added later is still a plain refusal.
        return new Validation(valid ? AnswerOutcome.VALID : AnswerOutcome.REFUSED, code, license);
    }

    private static List<String> rendered(List<?> components) {
        var rendered = new ArrayList<String>();
        for (var component : components) {
            var item = (StructuredFields.Item) component;
            var text = new StringBuilder("\"").append(item.value()).append('"');
            for (Map.Entry<String, Object> parameter : item.parameters().entrySet()) {
                // Only ;req is expected, so any other parameter makes the list differ.
                text.append(';').append(parameter.getKey()).append(Boolean.TRUE.equals(parameter.getValue()) ? "" : "=?");
            }

            rendered.add(item.value() instanceof String ? text.toString() : "?");
        }

        return rendered;
    }

    private void verifySignature(UUID kid, String base, byte[] signed) throws Unverified {
        var key = keys.find(kid).orElseThrow(Unverified::new);
        try {
            var verifier = Signature.getInstance(ED25519);
            verifier.initVerify(key);
            verifier.update(base.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signed)) {
                throw new Unverified();
            }
        } catch (GeneralSecurityException failure) {
            throw new Unverified();
        }
    }

    private static Optional<OnlineLicense> license(JsonNode license) throws Unverified {
        if (license.isMissingNode() || license.isNull()) {
            return Optional.empty();
        }

        var entitlements = new ArrayList<String>();
        for (var entitlement : license.path("entitlements")) {
            if (!entitlement.isString()) {
                throw new Unverified();
            }

            entitlements.add(entitlement.asString());
        }

        var expiry = license.path("expiry");
        if (!license.isObject() || !license.path("entitlements").isArray() || !license.path("metadata").isObject() || !license.path("status").isString()
                || !(expiry.isNull() || expiry.isString())) {
            throw new Unverified();
        }

        return Optional.of(new OnlineLicense(
                UUID.fromString(license.path("id").asString()),
                UUID.fromString(license.path("product").asString()),
                UUID.fromString(license.path("policy").asString()),
                UUID.fromString(license.path("customer").asString()),
                license.path("status").asString(),
                Instant.parse(license.path("notBefore").asString()),
                expiry.isString() ? Optional.of(Instant.parse(expiry.asString())) : Optional.empty(),
                List.copyOf(entitlements),
                license.path("metadata")));
    }

    private static Optional<JsonNode> object(byte[] body) {
        try {
            var node = JSON.readTree(body);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (JacksonException notJson) {
            return Optional.empty();
        }
    }

    private static Instant instant(String date) throws Unverified {
        try {
            return ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException failure) {
            throw new Unverified();
        }
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("Every JVM has SHA-256", impossible);
        }
    }

    /**
     * An answer that doesn't verify. It never leaves this class: {@link #verify} turns it into a network failure.
     */
    private static final class Unverified extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        Unverified() {
            super(null, null, false, false);
        }
    }
}
