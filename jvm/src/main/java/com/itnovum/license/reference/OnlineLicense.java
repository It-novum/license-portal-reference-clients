package com.itnovum.license.reference;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The license a verified answer carries. It holds no names: a license key alone reaches it.
 *
 * @param status the license's state as the portal names it, such as {@code ACTIVE} or {@code SUSPENDED}. Decide on the answer's outcome, never on this
 * @param expiry when the license ends, empty when it never does
 * @param entitlements the feature codes the license unlocks, its policy's and its own
 * @param metadata the values the vendor set, its policy's with the license's own laid over them
 */
public record OnlineLicense(
        UUID license,
        UUID product,
        UUID policy,
        UUID customer,
        String status,
        Instant notBefore,
        Optional<Instant> expiry,
        List<String> entitlements,
        JsonNode metadata
) {

    public OnlineLicense {
        metadata = metadata.deepCopy();
    }

    public boolean entitles(String code) {
        return entitlements.contains(code);
    }

    @Override
    public JsonNode metadata() {
        return metadata.deepCopy();
    }
}
