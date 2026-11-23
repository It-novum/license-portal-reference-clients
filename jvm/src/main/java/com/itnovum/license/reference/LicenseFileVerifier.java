package com.itnovum.license.reference;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies license files offline. The checks run in a fixed order and the first that fails decides the outcome, so a file has exactly one outcome, the one the conformance vectors give.
 *
 * <p>
 * Build one per product, with the product's keys and its id compiled into the application. Pass the current time to {@link #verify}, so tests can check a file at any time; the application
 * passes {@code Instant.now()}.
 *
 * <p>
 * It checks the file's product but not its tenant. The compiled-in keys belong to the vendor and sign the files of every product the vendor sells, so a file that verifies may carry another
 * product's license. A signature that verifies does prove the vendor, so the tenant needs no check.
 *
 * <p>
 * The vendor's policy decides whether a license's files are encrypted, so read both kinds. An encrypted file opens only with the license key, which the user enters or the application stores
 * beside the file; without it the file gives nothing.
 */
public final class LicenseFileVerifier {

    /**
     * The one signature algorithm this build accepts. Never take the algorithm from the file, or an attacker picks a weak one.
     */
    private static final String ALGORITHM = "ed25519";
    private static final String PLAIN = "base64";
    private static final String ENCRYPTED = "aes-256-gcm";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TYPE = "license";
    /**
     * What the signature covers ahead of {@code enc}, so that a signature made for another kind of file can't pass as a license file.
     */
    private static final String DOMAIN = "license/";
    private static final int HIGHEST_VERSION = 1;
    private static final Pattern ARMOUR = Pattern.compile("\\A-----BEGIN LICENSE FILE-----\\s+(.*?)\\s*-----END LICENSE FILE-----\\z", Pattern.DOTALL);
    // Numbers keep their digits, so a metadata value of 3.10 reads as 3.10.
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final ProductKeys keys;
    private final UUID product;
    private final Optional<String> licenseKey;
    private final ClockMark clockMark;
    private final Duration skew;

    /**
     * For an application that reads only plain files: an encrypted one is {@code DECRYPTION_FAILED}.
     *
     * @param product your product's id, the one its licenses name, which the vendor gives you with the keys
     * @param skew how far the machine's clock may be off: a file issued up to this long after {@code now} still counts, and {@code now} may lag the clock mark by this much. Five minutes suits most
     *     machines
     */
    public LicenseFileVerifier(ProductKeys keys, UUID product, ClockMark clockMark, Duration skew) {
        this(keys, product, Optional.empty(), clockMark, skew);
    }

    /**
     * @param licenseKey the license key exactly as the vendor issued it, which opens the license's encrypted files. Don't trim or change its case: the file key is the SHA-256 of its exact text
     */
    public LicenseFileVerifier(ProductKeys keys, UUID product, String licenseKey, ClockMark clockMark, Duration skew) {
        this(keys, product, Optional.of(licenseKey), clockMark, skew);
    }

    private LicenseFileVerifier(ProductKeys keys, UUID product, Optional<String> licenseKey, ClockMark clockMark, Duration skew) {
        this.keys = keys;
        this.product = product;
        this.licenseKey = licenseKey;
        this.clockMark = clockMark;
        this.skew = skew;
    }

    /**
     * Checks the file at {@code now}. A refusal comes back as an outcome and never as an exception, so a bad file can't crash the application.
     */
    public Verification verify(String file, Instant now) {
        try {
            var license = check(file, now);
            clockMark.raise(license.issued());

            return new Verification.Verified(license);
        } catch (Refusal refusal) {
            return new Verification.Refused(refusal.outcome);
        } finally {
            // Every time seen raises the mark, whatever the outcome: otherwise a file found expired could be revived by setting the clock back. A file's issued time counts only once it verified.
            clockMark.raise(now);
        }
    }

    private VerifiedLicense check(String file, Instant now) throws Refusal {
        // The armour and the envelope.
        var armoured = ARMOUR.matcher(file.strip());
        if (!armoured.matches()) {
            throw new Refusal(Outcome.MALFORMED);
        }

        var envelope = object(base64(armoured.group(1).replaceAll("\\s", "")));
        var enc = text(envelope, "enc");
        var signature = base64(text(envelope, "sig"));

        // alg is <encoding>+<signature>. The signature part must be the one this build expects; the encoding is either of the two.
        var alg = text(envelope, "alg").split("\\+", -1);
        if (alg.length != 2 || !ALGORITHM.equals(alg[1]) || !(PLAIN.equals(alg[0]) || ENCRYPTED.equals(alg[0]))) {
            throw new Refusal(Outcome.UNEXPECTED_ALGORITHM);
        }

        // The key, then the signature, before anything in enc is read, so bytes nobody signed never reach the decryption or the parser.
        var kid = uuid(text(envelope, "kid"));
        var key = keys.find(kid).orElseThrow(() -> new Refusal(Outcome.KEY_UNKNOWN));
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update((DOMAIN + enc).getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signature)) {
                throw new Refusal(Outcome.BAD_SIGNATURE);
            }
        } catch (GeneralSecurityException failure) {
            throw new Refusal(Outcome.BAD_SIGNATURE);
        }

        // Only now is the payload decoded, or decrypted. Fields this build doesn't know are ignored, so a file with fields added later still verifies; a higher version
        // is refused, because its fields may mean something else.
        var payload = object(PLAIN.equals(alg[0]) ? base64(enc) : decrypt(enc));
        var version = payload.path("v");
        if (!version.isInt() || version.asInt() < 1) {
            throw new Refusal(Outcome.MALFORMED);
        }

        if (version.asInt() > HIGHEST_VERSION) {
            throw new Refusal(Outcome.UNSUPPORTED_VERSION);
        }

        // The type, and the payload repeats the envelope's kid, so a file can't be re-labelled with another key.
        if (!TYPE.equals(text(payload, "typ")) || !kid.equals(uuid(text(payload, "kid")))) {
            throw new Refusal(Outcome.MALFORMED);
        }

        // A file has both an expiry and a ttl, or neither.
        if (payload.path("expiry").isString() != payload.path("ttl").isNumber()) {
            throw new Refusal(Outcome.MALFORMED);
        }

        var license = license(payload);

        // The product, because the vendor's keys sign for its other products too. Checked before the times, so a file for another product says so whatever its times.
        if (!product.equals(license.product())) {
            throw new Refusal(Outcome.WRONG_PRODUCT);
        }

        // A clock set back behind a time already seen, so an expired file can't be revived. Checked before the times below, which such a clock can't decide.
        if (now.isBefore(clockMark.mark().minus(skew))) {
            throw new Refusal(Outcome.CLOCK_BEHIND);
        }

        // Issued no later than now plus the skew: a file from the future means this clock is behind, so its times can't be judged yet.
        if (license.issued().isAfter(now.plus(skew))) {
            throw new Refusal(Outcome.NOT_YET_VALID);
        }

        // A license can start after its file was issued. Keep such a file, which becomes valid once the license starts. The skew applies as to issued, because a license often starts the
        // moment its file is issued.
        if (license.notBefore().isAfter(now.plus(skew))) {
            throw new Refusal(Outcome.NOT_YET_VALID);
        }

        // A file without an expiry doesn't expire.
        if (license.expiry().filter(ends -> !now.isBefore(ends)).isPresent()) {
            throw new Refusal(Outcome.EXPIRED);
        }

        // The license inside the file has its own expiry, and a file proves nothing once the license has ended, whether or not the file expires. An application that should keep
        // working after its license ends, at reduced function for example, removes this check and reads licenseExpiry() itself.
        if (license.licenseExpiry().filter(ends -> !now.isBefore(ends)).isPresent()) {
            throw new Refusal(Outcome.EXPIRED);
        }

        return license;
    }

    private static VerifiedLicense license(JsonNode payload) throws Refusal {
        var license = payload.path("license");
        var licenseExpiry = license.path("expiry");
        var expiry = payload.path("expiry");

        var entitlements = new ArrayList<String>();
        for (var entitlement : list(license, "entitlements")) {
            if (!entitlement.isString()) {
                throw new Refusal(Outcome.MALFORMED);
            }

            entitlements.add(entitlement.asString());
        }

        // Metadata values can be of any JSON type, and VerifiedLicense reads each as the type its caller expects.
        var metadata = license.path("metadata");
        if (!metadata.isObject()) {
            throw new Refusal(Outcome.MALFORMED);
        }

        return new VerifiedLicense(
                uuid(text(license, "id")),
                uuid(text(license, "product")),
                uuid(text(license, "policy")),
                uuid(text(license, "customer")),
                instant(text(license, "notBefore")),
                licenseExpiry.isString() ? Optional.of(instant(licenseExpiry.asString())) : Optional.empty(),
                List.copyOf(entitlements),
                metadata.deepCopy(),
                instant(text(payload, "issued")),
                expiry.isString() ? Optional.of(instant(expiry.asString())) : Optional.empty());
    }

    private static JsonNode list(JsonNode node, String field) throws Refusal {
        var value = node.path(field);
        if (!value.isArray()) {
            throw new Refusal(Outcome.MALFORMED);
        }

        return value;
    }

    private static String text(JsonNode node, String field) throws Refusal {
        var value = node.path(field);
        if (!value.isString()) {
            throw new Refusal(Outcome.MALFORMED);
        }

        return value.asString();
    }

    private static UUID uuid(String value) throws Refusal {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw new Refusal(Outcome.MALFORMED);
        }
    }

    private static Instant instant(String value) throws Refusal {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw new Refusal(Outcome.MALFORMED);
        }
    }

    /**
     * An encrypted enc is base64(ciphertext).base64(iv).base64(tag): AES-256-GCM, keyed by the SHA-256 of the license key's UTF-8 text, with no associated data. A file that won't open under this
     * build's license key is refused, never thrown.
     */
    private byte[] decrypt(String enc) throws Refusal {
        var parts = enc.split("\\.", -1);
        if (parts.length != 3) {
            throw new Refusal(Outcome.MALFORMED);
        }

        var ciphertext = base64(parts[0]);
        var iv = base64(parts[1]);
        var tag = base64(parts[2]);
        if (iv.length != IV_BYTES || tag.length * Byte.SIZE != TAG_BITS) {
            throw new Refusal(Outcome.MALFORMED);
        }

        if (licenseKey.isEmpty()) {
            throw new Refusal(Outcome.DECRYPTION_FAILED);
        }

        try {
            var fileKey = MessageDigest.getInstance("SHA-256").digest(licenseKey.get().getBytes(StandardCharsets.UTF_8));
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(fileKey, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            // Java's AES-GCM takes the tag at the end of the ciphertext.
            var sealed = Arrays.copyOf(ciphertext, ciphertext.length + tag.length);
            System.arraycopy(tag, 0, sealed, ciphertext.length, tag.length);

            return cipher.doFinal(sealed);
        } catch (GeneralSecurityException failure) {
            throw new Refusal(Outcome.DECRYPTION_FAILED);
        }
    }

    private static byte[] base64(String value) throws Refusal {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new Refusal(Outcome.MALFORMED);
        }
    }

    private static JsonNode object(byte[] bytes) throws Refusal {
        try {
            var node = JSON.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw new Refusal(Outcome.MALFORMED);
            }

            return node;
        } catch (JacksonException failure) {
            throw new Refusal(Outcome.MALFORMED);
        }
    }

    /**
     * Carries a refusal out of the checks to {@link #verify}, which turns it into an outcome. It never leaves this class.
     */
    private static final class Refusal extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Outcome outcome;

        Refusal(Outcome outcome) {
            super(outcome.name(), null, false, false);
            this.outcome = outcome;
        }
    }
}
