package com.itnovum.license.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the conformance vectors, as a client of your own should: each file or answer at its manifest's time, with a fresh clock mark, compared with the manifest's outcome.
 */
class ConformanceTest {

    private static final Path VECTORS = Path.of("../vectors");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyVectorReachesItsOutcome() throws IOException {
        var manifest = read("manifest.json");
        var keys = ProductKeys.parse(Files.readString(VECTORS.resolve(manifest.get("keys").asString()), StandardCharsets.UTF_8));
        var now = Instant.parse(manifest.get("now").asString());
        var skew = Duration.ofSeconds(manifest.get("skewSeconds").asLong());
        var product = UUID.fromString(manifest.get("product").asString());

        for (var vector : manifest.get("cases")) {
            var name = vector.get("name").asString();
            var verifier = new LicenseFileVerifier(keys, product, manifest.get("licenseKey").asString(), new ClockMark.InMemory(), skew);

            var verification = verifier.verify(Files.readString(VECTORS.resolve(vector.get("file").asString()), StandardCharsets.UTF_8), now);

            assertThat(verification.outcome()).as(name).hasToString(vector.get("outcome").asString());
            if (verification instanceof Verification.Verified verified) {
                assertPayload(name, verified.license(), vector.get("payload"));
            }
        }
    }

    @Test
    void everyAnswerVectorReachesItsOutcome() throws IOException {
        var manifest = read("answer-manifest.json");
        var keys = ProductKeys.parse(Files.readString(VECTORS.resolve(manifest.get("keys").asString()), StandardCharsets.UTF_8));
        var now = Instant.parse(manifest.get("now").asString());
        var skew = Duration.ofSeconds(manifest.get("skewSeconds").asLong());
        var product = UUID.fromString(manifest.get("product").asString());

        for (var vector : manifest.get("cases")) {
            var name = vector.get("name").asString();
            var exchange = read(vector.get("file").asString());

            var validation = new AnswerVerifier(keys, product, new ClockMark.InMemory(), skew).verify(sent(exchange.get("request")), received(exchange.get("answer")), now);

            assertThat(validation.outcome()).as(name).hasToString(vector.get("outcome").asString());
            assertThat(validation.code()).as(name).isEqualTo(vector.has("code") ? Optional.of(vector.get("code").asString()) : Optional.empty());
            assertThat(validation.license().isPresent()).as(name).isEqualTo(vector.has("license"));
            validation.license().ifPresent(license -> assertLicense(name, license, vector.get("license")));
        }
    }

    /**
     * The request as the vector's client sent it. A client keeps these values when it sends the request; here they come from the request's own headers.
     */
    static SentRequest sent(JsonNode request) {
        var headers = request.get("headers");
        var asked = StructuredFields.dictionary(headers.get("accept-signature").asString()).entrySet().iterator().next();
        var query = request.get("query").isString() ? "?" + request.get("query").asString() : "";
        var digest = headers.has("content-digest") ? Optional.of(headers.get("content-digest").asString()) : Optional.<String>empty();

        return new SentRequest(request.get("method").asString(), URI.create("https://" + request.get("authority").asString() + request.get("path").asString() + query), asked.getKey(),
                UUID.fromString((String) asked.getValue().parameters().get("keyid")), (String) asked.getValue().parameters().get("nonce"), digest);
    }

    static ReceivedAnswer received(JsonNode answer) {
        var headers = new HashMap<String, String>();
        answer.get("headers").properties().forEach(header -> headers.put(header.getKey(), header.getValue().asString()));

        return new ReceivedAnswer(answer.get("status").asInt(), headers, answer.get("body").asString().getBytes(StandardCharsets.UTF_8));
    }

    private static void assertLicense(String name, OnlineLicense license, JsonNode expected) {
        assertThat(license.license()).as(name).hasToString(expected.get("id").asString());
        assertThat(license.product()).as(name).hasToString(expected.get("product").asString());
        assertThat(license.policy()).as(name).hasToString(expected.get("policy").asString());
        assertThat(license.customer()).as(name).hasToString(expected.get("customer").asString());
        assertThat(license.status()).as(name).isEqualTo(expected.get("status").asString());
        assertThat(license.notBefore()).as(name).isEqualTo(Instant.parse(expected.get("notBefore").asString()));
        assertThat(license.expiry()).as(name).isEqualTo(expected.get("expiry").isString() ? Optional.of(Instant.parse(expected.get("expiry").asString())) : Optional.empty());
        assertThat(license.entitlements()).as(name).containsExactlyElementsOf(expected.get("entitlements").valueStream().map(JsonNode::asString).toList());
        assertThat(license.metadata()).as(name).isEqualTo(expected.get("metadata"));
    }

    private static void assertPayload(String name, VerifiedLicense license, JsonNode payload) {
        var expected = payload.get("license");
        assertThat(license.license()).as(name).hasToString(expected.get("id").asString());
        assertThat(license.product()).as(name).hasToString(expected.get("product").asString());
        assertThat(license.policy()).as(name).hasToString(expected.get("policy").asString());
        assertThat(license.customer()).as(name).hasToString(expected.get("customer").asString());
        assertThat(license.licenseExpiry()).as(name).isEqualTo(expected.get("expiry").isString() ? Optional.of(Instant.parse(expected.get("expiry").asString())) : Optional.empty());
        assertThat(license.entitlements()).as(name).containsExactlyElementsOf(expected.get("entitlements").valueStream().map(JsonNode::asString).toList());
        assertThat(license.metadata()).as(name).isEqualTo(expected.get("metadata"));
        assertThat(license.text("maxVersion")).as(name).isPresent();
        assertThat(license.issued()).as(name).isEqualTo(Instant.parse(payload.get("issued").asString()));
        assertThat(license.expiry()).as(name).isEqualTo(payload.get("expiry").isString() ? Optional.of(Instant.parse(payload.get("expiry").asString())) : Optional.empty());
    }

    private static JsonNode read(String file) throws IOException {
        return JSON.readTree(Files.readString(VECTORS.resolve(file), StandardCharsets.UTF_8));
    }
}
