package com.itnovum.license.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the conformance vectors, as a client of your own should: each file at the manifest's time, with a fresh clock mark, compared with the manifest's outcome.
 */
class ConformanceTest {

    private static final Path VECTORS = Path.of("../conformance/vectors");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyVectorReachesItsOutcome() throws IOException {
        var manifest = read("manifest.json");
        var keys = ProductKeys.parse(Files.readString(VECTORS.resolve(manifest.get("keys").asString()), StandardCharsets.UTF_8));
        var now = Instant.parse(manifest.get("now").asString());
        var skew = Duration.ofSeconds(manifest.get("skewSeconds").asLong());

        for (var vector : manifest.get("cases")) {
            var name = vector.get("name").asString();
            var verifier = new LicenseFileVerifier(keys, manifest.get("licenseKey").asString(), new ClockMark.InMemory(), skew);

            var verification = verifier.verify(Files.readString(VECTORS.resolve(vector.get("file").asString()), StandardCharsets.UTF_8), now);

            assertThat(verification.outcome()).as(name).hasToString(vector.get("outcome").asString());
            if (verification instanceof Verification.Verified verified) {
                assertPayload(name, verified.license(), vector.get("payload"));
            }
        }
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
