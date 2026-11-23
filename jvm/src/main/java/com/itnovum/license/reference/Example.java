package com.itnovum.license.reference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Verifies one license file from the command line and prints what it unlocks:
 *
 * <pre>
 * Example &lt;keys.json&gt; &lt;product id&gt; &lt;file.lic&gt; [license key or -] [build version] [time]
 * </pre>
 *
 * The license key opens an encrypted file; {@code -} means none, for a plain one. An application does the same at start-up, with the product's keys and id compiled in, the license key the user entered,
 * {@code Instant.now()} as the time, and a clock mark that lasts between runs.
 */
public final class Example {

    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final String NO_KEY = "-";

    private Example() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: Example <keys.json> <product id> <file.lic> [license key or -] [build version] [time]");
            System.exit(2);
        }

        var keys = ProductKeys.parse(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        var product = UUID.fromString(args[1]);
        var clockMark = new ClockMark.InMemory();
        var verifier = args.length > 3 && !NO_KEY.equals(args[3]) ? new LicenseFileVerifier(keys, product, args[3], clockMark, SKEW) : new LicenseFileVerifier(keys, product, clockMark, SKEW);
        var now = args.length > 5 ? Instant.parse(args[5]) : Instant.now();

        var verification = verifier.verify(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8), now);
        System.out.println("Outcome: " + verification.outcome());

        if (verification instanceof Verification.Verified verified) {
            var license = verified.license();
            System.out.println("License: " + license.license());
            System.out.println("Entitlements: " + String.join(", ", license.entitlements()));
            System.out.println("File valid until: " + license.expiry().map(Instant::toString).orElse("it never expires"));
            if (args.length > 4) {
                System.out.println("Build " + args[4] + (VersionBinding.allows(license, args[4]) ? " is" : " isn't") + " covered");
            }
        }

        System.exit(verification.outcome() == Outcome.VALID ? 0 : 1);
    }
}
