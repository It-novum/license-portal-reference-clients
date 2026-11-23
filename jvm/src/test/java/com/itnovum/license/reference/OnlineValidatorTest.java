package com.itnovum.license.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a validation sends, what a verified answer does to the clock mark, and that a lost connection is an outcome, not an exception.
 */
class OnlineValidatorTest {

    private static final Path VECTORS = Path.of("../vectors");
    private static final UUID KID = UUID.fromString("0192e4a1-0000-7000-8000-000000000001");
    private static final UUID PRODUCT = UUID.fromString("0192e4a0-0000-7000-8000-000000000001");
    private static final Duration SKEW = Duration.ofMinutes(5);

    private static OnlineValidator validator(URI tenant) throws IOException {
        var keys = ProductKeys.parse(Files.readString(VECTORS.resolve("keys.json"), StandardCharsets.UTF_8));

        return new OnlineValidator(tenant, keys, KID, PRODUCT, new ClockMark.InMemory(), SKEW, HttpClient.newHttpClient());
    }

    @Test
    void aRequestAsksForASignatureWithAFreshNonceAndBindsItsBody() throws Exception {
        var validator = validator(URI.create("https://License.Example.com/api/tenants/acme/"));
        var body = validator.body("9F2C41A0-7B3D5E61-C08A2F94-17D3B6E5");
        var sent = validator.sent(body);

        var request = OnlineValidator.request(sent, body);

        assertThat(request.uri()).hasToString("https://License.Example.com/api/tenants/acme/licenses/actions/validate-key");
        assertThat(sent.authority()).isEqualTo("license.example.com");
        assertThat(request.headers().firstValue("Accept-Signature")).hasValue("sig1=();keyid=\"" + KID + "\";nonce=\"" + sent.nonce() + "\"");
        assertThat(request.headers().firstValue("Content-Digest")).hasValue("sha-256=:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body)) + ":");
        assertThat(JsonMapper.builder().build().readTree(body).at("/scope/product").asString()).isEqualTo(PRODUCT.toString());
        assertThat(validator.sent(body).nonce()).as("a new nonce for every request").isNotEqualTo(sent.nonce());
    }

    @Test
    void aPortOtherThanTheDefaultIsPartOfTheAuthority() {
        var sent = new SentRequest("POST", URI.create("http://localhost:8080/x"), "sig1", KID, "n", java.util.Optional.empty());

        assertThat(sent.authority()).isEqualTo("localhost:8080");
    }

    @Test
    void aVerifiedAnswerRaisesTheClockMarkToItsDate() throws IOException {
        var keys = ProductKeys.parse(Files.readString(VECTORS.resolve("keys.json"), StandardCharsets.UTF_8));
        var exchange = JsonMapper.builder().build().readTree(Files.readString(VECTORS.resolve("answers/valid.json"), StandardCharsets.UTF_8));
        var mark = new ClockMark.InMemory();
        var now = Instant.parse("2026-01-14T23:58:00Z");

        new AnswerVerifier(keys, PRODUCT, mark, SKEW).verify(ConformanceTest.sent(exchange.get("request")), ConformanceTest.received(exchange.get("answer")), now);

        assertThat(mark.mark()).isEqualTo(Instant.parse("2026-01-15T00:00:00Z"));
    }

    @Test
    void noConnectionIsANetworkFailure() throws IOException {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        var validation = validator(URI.create("http://127.0.0.1:" + port + "/api/tenants/acme")).validate("9F2C41A0-7B3D5E61-C08A2F94-17D3B6E5");

        assertThat(validation.outcome()).isEqualTo(AnswerOutcome.NETWORK_FAILURE);
    }
}
