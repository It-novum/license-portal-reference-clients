package com.itnovum.license.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Each check a signed response goes through, on responses signed here with the vectors' test key, whose seed is public. The first test pins the signature base to the text the server builds, so these
 * signatures are the server's.
 */
class ResponseVerifierTest {

    private static final UUID KID = UUID.fromString("0192e4a1-0000-7000-8000-000000000001");
    private static final UUID PRODUCT = UUID.fromString("0192e4a0-0000-7000-8000-000000000001");
    private static final UUID OTHER_PRODUCT = UUID.fromString("0192e4a0-0000-7000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-01-15T00:00:00Z");
    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final String NONCE = "q4M9tZ2xYv0Rw7Lk";
    private static final URI VALIDATE = URI.create("https://license.example.com/api/tenants/acme/licenses/actions/validate-key");
    private static final String REQUEST_DIGEST = "sha-256=:1Qd86NVl/r8UCssQ6nqoHwhVAnNwRe2Zb3v6vEe6io0=:";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final List<String> FIXED = List.of("\"@method\";req", "\"@authority\";req", "\"@path\";req", "\"@query\";req", "\"@status\"", "\"content-digest\"", "\"date\"");

    private final ClockMark mark = new ClockMark.InMemory();

    private static SentRequest sent(boolean bound) {
        return new SentRequest("POST", VALIDATE, "sig1", KID, NONCE, bound ? Optional.of(REQUEST_DIGEST) : Optional.empty());
    }

    private static String license(UUID product) {
        return """
                {"id":"0192e9b3-0000-7000-8000-000000000001","product":"%s","policy":"0192e4a2-0000-7000-8000-000000000001",\
                "customer":"0192e8d5-0000-7000-8000-000000000001","status":"ACTIVE","notBefore":"2026-01-01T00:00:00Z","expiry":"2026-12-31T00:00:00Z",\
                "entitlements":["API","REPORTS"],"metadata":{"maxVersion":"3"}}""".formatted(product);
    }

    private static String validation(boolean valid, String code, String license) {
        return "{\"type\":\"validation\",\"valid\":%s,\"code\":\"%s\",\"detail\":null,\"license\":%s,\"futureField\":1}".formatted(valid, code, license);
    }

    /**
     * What a signer signs. Everything defaults to what a correct server signs for {@code sent}.
     */
    private record Signing(SentRequest over, List<String> components, Instant date, byte[] seed, UUID keyid, String nonce) {

        static Signing of(SentRequest sent) {
            var components = new ArrayList<>(FIXED);
            sent.contentDigest().ifPresent(digest -> components.add(4, "\"content-digest\";req"));

            return new Signing(sent, components, NOW, ResponseVerifierTest.seed(0x01), KID, NONCE);
        }

        Signing over(SentRequest other) {
            return new Signing(other, components, date, seed, keyid, nonce);
        }

        Signing components(List<String> listed) {
            return new Signing(over, listed, date, seed, keyid, nonce);
        }

        Signing at(Instant at) {
            return new Signing(over, components, at, seed, keyid, nonce);
        }

        Signing by(int first, UUID kid) {
            return new Signing(over, components, date, ResponseVerifierTest.seed(first), kid, nonce);
        }

        Signing nonce(String other) {
            return new Signing(over, components, date, seed, keyid, other);
        }
    }

    private static String base(Signing signing, int status, String digest, String date) {
        var values = Map.of(
                "\"@method\";req", signing.over().method(),
                "\"@authority\";req", signing.over().authority(),
                "\"@path\";req", signing.over().uri().getRawPath(),
                "\"@query\";req", "?" + Optional.ofNullable(signing.over().uri().getRawQuery()).orElse(""),
                "\"content-digest\";req", signing.over().contentDigest().orElse(""),
                "\"@status\"", String.valueOf(status),
                "\"content-digest\"", digest,
                "\"date\"", date);
        var base = new StringBuilder();
        for (var component : signing.components()) {
            base.append(component).append(": ").append(values.get(component)).append('\n');
        }

        return base.append("\"@signature-params\": ").append(params(signing)).toString();
    }

    private static String params(Signing signing) {
        return "(" + String.join(" ", signing.components()) + ");created=" + signing.date().getEpochSecond() + ";keyid=\"" + signing.keyid() + "\";nonce=\"" + signing.nonce() + "\"";
    }

    private static ReceivedResponse signed(Signing signing, int status, String body) throws GeneralSecurityException {
        var digest = digest(body);
        var date = HTTP_DATE.format(signing.date());
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, signing.seed())));
        signer.update(base(signing, status, digest, date).getBytes(StandardCharsets.UTF_8));

        return new ReceivedResponse(status, Map.of(
                "Content-Type", "application/json",
                "Date", date,
                "Content-Digest", digest,
                "Signature-Input", "sig1=" + params(signing),
                "Signature", "sig1=:" + Base64.getEncoder().encodeToString(signer.sign()) + ":"), body.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String body) throws GeneralSecurityException {
        return "sha-256=:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))) + ":";
    }

    private static byte[] seed(int first) {
        var seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (first + i);
        }

        return seed;
    }

    private Validation verify(SentRequest sent, ReceivedResponse response) throws Exception {
        var keys = ProductKeys.parse(Files.readString(Path.of("../vectors/keys.json"), StandardCharsets.UTF_8));

        return new ResponseVerifier(keys, PRODUCT, mark, SKEW).verify(sent, response, NOW);
    }

    @Test
    void theBaseIsTheTextTheServerSigns() throws GeneralSecurityException {
        var signing = Signing.of(sent(true));

        assertThat(base(signing, 200, "sha-256=:abc=:", "Thu, 15 Jan 2026 00:00:00 GMT")).isEqualTo("""
                "@method";req: POST
                "@authority";req: license.example.com
                "@path";req: /api/tenants/acme/licenses/actions/validate-key
                "@query";req: ?
                "content-digest";req: sha-256=:1Qd86NVl/r8UCssQ6nqoHwhVAnNwRe2Zb3v6vEe6io0=:
                "@status": 200
                "content-digest": sha-256=:abc=:
                "date": Thu, 15 Jan 2026 00:00:00 GMT
                "@signature-params": ("@method";req "@authority";req "@path";req "@query";req "content-digest";req "@status" "content-digest" "date");created=1768435200;\
                keyid="0192e4a1-0000-7000-8000-000000000001";nonce="q4M9tZ2xYv0Rw7Lk\"""");
    }

    @Test
    void aVerifiedValidResponseGivesTheLicenseAndRaisesTheClockMark() throws Exception {
        for (var bound : List.of(false, true)) {
            var validation = verify(sent(bound), signed(Signing.of(sent(bound)), 200, validation(true, "VALID", license(PRODUCT))));

            assertThat(validation.outcome()).as("bound %s", bound).isEqualTo(ResponseOutcome.VALID);
            assertThat(validation.code()).hasValue("VALID");
            assertThat(validation.license()).hasValueSatisfying(held -> {
                assertThat(held.entitles("REPORTS")).isTrue();
                assertThat(held.expiry()).hasValue(Instant.parse("2026-12-31T00:00:00Z"));
            });
        }

        assertThat(mark.mark()).isEqualTo(NOW);
    }

    @Test
    void aSignedRefusalIsTrustedWhateverItsCode() throws Exception {
        var notFound = verify(sent(false), signed(Signing.of(sent(false)), 200, validation(false, "NOT_FOUND", "null")));
        var unknown = verify(sent(false), signed(Signing.of(sent(false)), 200, validation(false, "FUTURE_CODE", license(PRODUCT))));

        assertThat(notFound.outcome()).isEqualTo(ResponseOutcome.REFUSED);
        assertThat(notFound.code()).hasValue("NOT_FOUND");
        assertThat(notFound.license()).isEmpty();
        assertThat(unknown.outcome()).isEqualTo(ResponseOutcome.REFUSED);
        assertThat(unknown.code()).hasValue("FUTURE_CODE");
    }

    @Test
    void anotherProductsLicenseIsWrongProduct() throws Exception {
        var validation = verify(sent(false), signed(Signing.of(sent(false)), 200, validation(true, "VALID", license(OTHER_PRODUCT))));

        assertThat(validation.outcome()).isEqualTo(ResponseOutcome.WRONG_PRODUCT);
        assertThat(validation.license()).isEmpty();
    }

    @Test
    void aSigned4xxIsAnErrorButA429OrA5xxIsANetworkFailure() throws Exception {
        var problem = "{\"status\":400,\"code\":\"invalid_request\"}";

        assertThat(verify(sent(false), signed(Signing.of(sent(false)), 400, problem))).isEqualTo(new Validation(ResponseOutcome.ERROR, Optional.of("invalid_request"), Optional.empty()));
        assertThat(verify(sent(false), signed(Signing.of(sent(false)), 429, problem)).outcome()).isEqualTo(ResponseOutcome.NETWORK_FAILURE);
        assertThat(verify(sent(false), signed(Signing.of(sent(false)), 503, problem)).outcome()).isEqualTo(ResponseOutcome.NETWORK_FAILURE);
    }

    @Test
    void anUnsignedResponseIsANetworkFailure() throws Exception {
        var body = validation(true, "VALID", license(PRODUCT));

        assertThat(verify(sent(false), new ReceivedResponse(200, Map.of("Content-Type", "application/json"), body.getBytes(StandardCharsets.UTF_8))).outcome())
                .isEqualTo(ResponseOutcome.NETWORK_FAILURE);
    }

    @Test
    void aResponseForAnotherRequestIsANetworkFailure() throws Exception {
        var body = validation(true, "VALID", license(PRODUCT));
        var sent = sent(true);
        var otherPath = new SentRequest("POST", URI.create("https://license.example.com/api/tenants/acme/licenses/actions/check-out"), "sig1", KID, NONCE, sent.contentDigest());
        var otherHost = new SentRequest("POST", URI.create("https://staging.example.com" + VALIDATE.getRawPath()), "sig1", KID, NONCE, sent.contentDigest());
        var otherBody = new SentRequest("POST", VALIDATE, "sig1", KID, NONCE, Optional.of(digest("{\"key\":\"another\"}")));
        var notCovered = Signing.of(sent).components(FIXED);

        for (var signing : List.of(Signing.of(sent).over(otherPath), Signing.of(sent).over(otherHost), Signing.of(sent).over(otherBody), notCovered)) {
            assertThat(verify(sent, signed(signing, 200, body)).outcome()).as(signing.toString()).isEqualTo(ResponseOutcome.NETWORK_FAILURE);
        }
    }

    @Test
    void anotherKeyNonceOrComponentListIsANetworkFailure() throws Exception {
        var body = validation(true, "VALID", license(PRODUCT));
        var sent = sent(false);
        var withoutDate = FIXED.subList(0, FIXED.size() - 1);

        for (var signing : List.of(Signing.of(sent).by(0x41, UUID.randomUUID()), Signing.of(sent).nonce("not-mine"), Signing.of(sent).components(withoutDate))) {
            assertThat(verify(sent, signed(signing, 200, body)).outcome()).as(signing.toString()).isEqualTo(ResponseOutcome.NETWORK_FAILURE);
        }
    }

    @Test
    void aChangedBodyOrSignatureIsANetworkFailure() throws Exception {
        var sent = sent(false);
        var response = signed(Signing.of(sent), 200, validation(true, "VALID", license(PRODUCT)));
        var changedBody = new ReceivedResponse(200, response.headers(), validation(true, "VALID", license(PRODUCT)).replace("REPORTS", "EVERYTHING").getBytes(StandardCharsets.UTF_8));
        var headers = new HashMap<>(response.headers());
        var signature = Base64.getDecoder().decode(headers.get("signature").substring(6, headers.get("signature").length() - 1));
        signature[0] ^= 1;
        headers.put("signature", "sig1=:" + Base64.getEncoder().encodeToString(signature) + ":");

        assertThat(verify(sent, changedBody).outcome()).isEqualTo(ResponseOutcome.NETWORK_FAILURE);
        assertThat(verify(sent, new ReceivedResponse(200, headers, response.body())).outcome()).isEqualTo(ResponseOutcome.NETWORK_FAILURE);
    }

    @Test
    void anOldResponseIsANetworkFailure() throws Exception {
        var sent = sent(false);

        assertThat(verify(sent, signed(Signing.of(sent).at(NOW.minus(Duration.ofMinutes(6))), 200, validation(true, "VALID", license(PRODUCT)))).outcome())
                .isEqualTo(ResponseOutcome.NETWORK_FAILURE);
        assertThat(mark.mark()).isEqualTo(Instant.EPOCH);
    }
}
