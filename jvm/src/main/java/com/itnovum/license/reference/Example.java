package com.itnovum.license.reference;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Verifies one license file from the command line and prints what it unlocks, or validates a license key online:
 *
 * <pre>
 * Example &lt;keys.json&gt; &lt;product id&gt; &lt;file.lic&gt; [license key or -] [build version] [time]
 * Example validate &lt;keys.json&gt; &lt;product id&gt; &lt;key id&gt; &lt;tenant URL&gt; &lt;license key&gt;
 * </pre>
 *
 * The license key opens an encrypted file; {@code -} means none, for a plain one. An application does the same at start-up, with the product's keys and id compiled in, the license key the user entered,
 * {@code Instant.now()} as the time, and a clock mark that lasts between runs. Online, the key id is the compiled-in key to ask for, and the tenant URL the vendor's, such as
 * {@code https://license.example.com/api/tenants/acme}.
 */
public final class Example {

    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final String NO_KEY = "-";
    private static final String VALIDATE = "validate";

    private Example() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 6 && VALIDATE.equals(args[0])) {
            validate(args);
        }

        if (args.length < 3) {
            System.err.println("Usage: Example <keys.json> <product id> <file.lic> [license key or -] [build version] [time]");
            System.err.println("       Example validate <keys.json> <product id> <key id> <tenant URL> <license key>");
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

    private static void validate(String[] args) throws IOException {
        var keys = ProductKeys.parse(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
        var validator = new OnlineValidator(URI.create(args[4]), keys, UUID.fromString(args[3]), UUID.fromString(args[2]), new ClockMark.InMemory(), SKEW, HttpClient.newHttpClient());

        var validation = validator.validate(args[5]);
        System.out.println("Outcome: " + validation.outcome());
        validation.code().ifPresent(code -> System.out.println("Code: " + code));
        validation.license().ifPresent(license -> {
            System.out.println("License: " + license.license() + " (" + license.status() + ")");
            System.out.println("Entitlements: " + String.join(", ", license.entitlements()));
        });

        System.exit(validation.outcome() == AnswerOutcome.VALID ? 0 : 1);
    }
}
