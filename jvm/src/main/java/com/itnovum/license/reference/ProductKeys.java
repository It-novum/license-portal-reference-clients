package com.itnovum.license.reference;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The product's public keys, which the build compiles in: the list the license portal gives for the product ({@code GET /api/tenants/{tenant}/products/{id}/keys}), each with its {@code kid},
 * {@code alg} and {@code publicKey}. Compile the list into the build and never read it from a file or fetch it at start-up, because a key the application loads from outside its build can be
 * replaced. Nothing refreshes these keys: a new key reaches clients only in a new build.
 *
 * <p>
 * Every Ed25519 key in the list is trusted, whatever its {@code state}. During a key rotation the list holds the old key and the next one, and a build has to verify files signed by either. Fields
 * this build doesn't know are ignored, so a key list with fields added later still parses.
 */
public final class ProductKeys {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ED25519 = "ed25519";

    private final Map<UUID, PublicKey> keys;

    private ProductKeys(Map<UUID, PublicKey> keys) {
        this.keys = Map.copyOf(keys);
    }

    /**
     * Reads the key list.
     *
     * @throws IllegalArgumentException when the list holds no Ed25519 key, or a key that can't be read. It ships with the build, so this is a packaging mistake, not something a customer can cause
     */
    public static ProductKeys parse(String list) {
        var keys = new HashMap<UUID, PublicKey>();
        for (JsonNode key : JSON.readTree(list)) {
            if (ED25519.equals(key.path("alg").asString())) {
                keys.put(UUID.fromString(key.path("kid").asString()), publicKey(key.path("publicKey").asString()));
            }
        }

        if (keys.isEmpty()) {
            throw new IllegalArgumentException("The key list holds no Ed25519 key");
        }

        return new ProductKeys(keys);
    }

    Optional<PublicKey> find(UUID kid) {
        return Optional.ofNullable(keys.get(kid));
    }

    private static PublicKey publicKey(String encoded) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("A key in the list isn't an Ed25519 public key", failure);
        }
    }
}
