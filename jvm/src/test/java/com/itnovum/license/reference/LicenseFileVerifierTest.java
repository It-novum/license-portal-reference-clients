package com.itnovum.license.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the conformance vectors can't show at their one time: a file without an expiry long after it was issued, another product's file that has also expired, and a file whose expiry and ttl
 * disagree. The second is signed here with the
 * vectors' test key, whose seed is public, so the file reaches the check it is about.
 */
class LicenseFileVerifierTest {

    private static final Path VECTORS = Path.of("../vectors");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final UUID PRODUCT = UUID.fromString("0192e4a0-0000-7000-8000-000000000001");

    private static String vector(String name) throws IOException {
        return Files.readString(VECTORS.resolve(name), StandardCharsets.UTF_8);
    }

    private static LicenseFileVerifier verifier() throws IOException {
        return new LicenseFileVerifier(ProductKeys.parse(vector("keys.json")), PRODUCT, new ClockMark.InMemory(), SKEW);
    }

    /**
     * The valid vector's payload with one change, signed again as a license file under the vectors' key.
     */
    private static String resigned(Consumer<ObjectNode> change) throws IOException, GeneralSecurityException {
        var file = vector("license-files/valid.lic");
        var envelope = (ObjectNode) JSON.readTree(Base64.getDecoder().decode(file.lines().filter(line -> !line.startsWith("-----")).reduce("", String::concat)));
        var payload = (ObjectNode) JSON.readTree(Base64.getDecoder().decode(envelope.get("enc").asString()));
        change.accept(payload);

        var seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (0x01 + i);
        }

        var enc = Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(payload));
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed)));
        signature.update(("license/" + enc).getBytes(StandardCharsets.UTF_8));
        envelope.put("enc", enc).put("sig", Base64.getEncoder().encodeToString(signature.sign()));

        return "-----BEGIN LICENSE FILE-----\n" + Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(envelope)) + "\n-----END LICENSE FILE-----\n";
    }

    @Test
    void aFileWithoutAnExpiryStaysValidUntilItsLicenseEnds() throws IOException {
        var file = vector("license-files/no-expiry.lic");

        var verification = verifier().verify(file, Instant.parse("2026-12-30T00:00:00Z"));

        assertThat(verification.outcome()).isEqualTo(Outcome.VALID);
        assertThat(((Verification.Verified) verification).license().expiry()).isEmpty();
        assertThat(verifier().verify(file, Instant.parse("2026-12-31T00:00:00Z")).outcome()).isEqualTo(Outcome.EXPIRED);
    }

    @Test
    void aFileIssuedBeforeItsLicenseStartsIsNotYetValidUntilThenWithinTheSkew() throws IOException {
        var file = vector("license-files/not-started.lic");
        var starts = Instant.parse("2026-01-25T00:00:00Z");

        assertThat(verifier().verify(file, starts.minus(SKEW).minusSeconds(1)).outcome()).isEqualTo(Outcome.NOT_YET_VALID);
        var verification = verifier().verify(file, starts.minus(SKEW));
        assertThat(verification.outcome()).isEqualTo(Outcome.VALID);
        assertThat(((Verification.Verified) verification).license().notBefore()).isEqualTo(starts);
    }

    @Test
    void anEncryptedFileNeedsTheRightLicenseKey() throws IOException {
        var file = vector("license-files/encrypted.lic");
        var now = Instant.parse("2026-01-15T00:00:00Z");
        var keys = ProductKeys.parse(vector("keys.json"));

        assertThat(new LicenseFileVerifier(keys, PRODUCT, "9F2C41A0-7B3D5E61-C08A2F94-17D3B6E5", new ClockMark.InMemory(), SKEW).verify(file, now).outcome()).isEqualTo(Outcome.VALID);
        assertThat(verifier().verify(file, now).outcome()).isEqualTo(Outcome.DECRYPTION_FAILED);
        assertThat(new LicenseFileVerifier(keys, PRODUCT, "9f2c41a0-7b3d5e61-c08a2f94-17d3b6e5", new ClockMark.InMemory(), SKEW).verify(file, now).outcome()).isEqualTo(Outcome.DECRYPTION_FAILED);
    }

    @Test
    void anotherProductsFileIsRefusedBeforeItsTimesAreChecked() throws IOException {
        var file = vector("license-files/wrong-product.lic");

        assertThat(verifier().verify(file, Instant.parse("2026-01-15T00:00:00Z")).outcome()).isEqualTo(Outcome.WRONG_PRODUCT);
        assertThat(verifier().verify(file, Instant.parse("2027-06-01T00:00:00Z")).outcome()).as("expired too").isEqualTo(Outcome.WRONG_PRODUCT);
    }

    @Test
    void aFileWithOnlyOneOfExpiryAndTtlIsMalformed() throws Exception {
        var now = Instant.parse("2026-01-15T00:00:00Z");

        assertThat(verifier().verify(resigned(payload -> payload.putNull("ttl")), now).outcome()).isEqualTo(Outcome.MALFORMED);
        assertThat(verifier().verify(resigned(payload -> payload.putNull("expiry")), now).outcome()).isEqualTo(Outcome.MALFORMED);
        assertThat(verifier().verify(resigned(payload -> payload.put("tenant", payload.get("tenant").asString())), now).outcome()).isEqualTo(Outcome.VALID);
    }
}
