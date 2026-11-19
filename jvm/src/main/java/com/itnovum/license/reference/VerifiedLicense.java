package com.itnovum.license.reference;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * What a verified license file says. Trust it only as it came out of {@link LicenseFileVerifier}: entitlements and metadata from anywhere else are not signed.
 *
 * @param license the license's id
 * @param product the product the license is for
 * @param policy the license's policy, for example to tell a trial from a full license
 * @param customer the customer's id. The file carries no name, so show "licensed to" from metadata if the vendor put a company name there
 * @param notBefore when the license starts. A file can be issued before then, and is {@link Outcome#NOT_YET_VALID} until it does
 * @param licenseExpiry when the license itself ends, which can be later than the file's {@code expiry}; empty when it never ends
 * @param entitlements the feature codes the license unlocks
 * @param metadata the values the vendor set on the license, a JSON object of any types. Read a value with {@link #text}, {@link #number} or {@link #flag}, which treat a value of
 *     another type as absent; read nested lists and objects here
 * @param issued when the file was issued
 * @param expiry when the file stops being valid, so ask for a new file before then; empty for a file that never expires
 */
public record VerifiedLicense(
        UUID license,
        UUID product,
        UUID policy,
        UUID customer,
        Instant notBefore,
        Optional<Instant> licenseExpiry,
        List<String> entitlements,
        JsonNode metadata,
        Instant issued,
        Optional<Instant> expiry
) {

    public VerifiedLicense {
        metadata = metadata.deepCopy();
    }

    public boolean entitles(String code) {
        return entitlements.contains(code);
    }

    /**
     * A text value, or empty when the key is missing or holds another type, such as {@code maxVersion}.
     */
    public Optional<String> text(String key) {
        var value = metadata.get(key);
        return value != null && value.isString() ? Optional.of(value.stringValue()) : Optional.empty();
    }

    /**
     * A number, or empty when the key is missing or holds another type. Its digits are kept, so {@code 3.10} stays {@code 3.10}.
     */
    public Optional<BigDecimal> number(String key) {
        var value = metadata.get(key);
        return value != null && value.isNumber() ? Optional.of(value.decimalValue()) : Optional.empty();
    }

    /**
     * A {@code true} or {@code false}, or empty when the key is missing or holds another type.
     */
    public Optional<Boolean> flag(String key) {
        var value = metadata.get(key);
        return value != null && value.isBoolean() ? Optional.of(value.booleanValue()) : Optional.empty();
    }

    /**
     * A copy, so the license read once stays as it was.
     */
    @Override
    public JsonNode metadata() {
        return metadata.deepCopy();
    }
}
