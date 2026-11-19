# Java reference client

A small, readable example of verifying license files offline, for anyone writing a client of their own. It makes every check a license file needs, in the order that gives each file one outcome, and passes the conformance vectors in [`vectors`](../vectors). The [file format](../README.md#file-format) is described in the repository's README. Copy what you need: it targets Java 17 and uses only the JDK and Jackson.

## What your build compiles in

- **The product's keys.** Take them once from the license portal (`GET /api/tenants/{tenant}/products/{id}/keys`) and compile the list into the build. Never read it from a file beside the application or fetch it at start-up, because either can be replaced. Nothing refreshes the keys: after a key rotation, a new build carries the new key.
- **The expected signature algorithm**, `ed25519`, which the verifier fixes. A file's `alg` is `base64+ed25519` or `aes-256-gcm+ed25519`, because the vendor's policy decides whether its files are encrypted, and the verifier reads both.

The verifier checks neither the file's tenant nor its product. Only your product's keys are compiled in, and they sign for nothing else, so a file that verifies is for your product. A file for any other product is `KEY_UNKNOWN`.

## Verifying a file

```java
var keys = ProductKeys.parse(PRODUCT_KEYS);
var verifier = new LicenseFileVerifier(keys, licenseKey, new ClockMark.InFile(markFile), Duration.ofMinutes(5));

var verification = verifier.verify(licenseFileText, Instant.now());
if (verification instanceof Verification.Verified verified) {
    var license = verified.license();
    boolean reports = license.entitles("REPORTS");
    boolean covered = VersionBinding.allows(license, "3.9.1");
} else {
    // Run without what the license would unlock, and log verification.outcome().
}
```

- **The license key opens encrypted files.** The vendor hands it out with the license. Store it as the user entered it and pass it unchanged, because the file key is the SHA-256 of its exact text. Without it, or with a key the vendor has since regenerated, an encrypted file is `DECRYPTION_FAILED`. An application that only ever gets plain files can use the constructor without a key.
- **Every refusal is an outcome, never an exception.** Act on `VALID` only. `Outcome` says what each refusal means.
- **The time is an argument.** Pass `Instant.now()` in the application, and a fixed time in tests.
- **The clock mark** remembers the latest time seen, so setting the clock back doesn't revive an expired file. Every call to `verify` raises it, whatever the outcome; a long-running application can also call `raise(Instant.now())` now and then. `ClockMark.InFile` keeps it in a file; where the platform has a keystore, keep it there instead.
- **A file expires before its license can.** `VerifiedLicense.expiry()` is when the file stops working, so ask for a new file before then. It is empty for a file that never expires. `licenseExpiry()` is when the license itself ends, which ends every file too.
- **A file can arrive before its license starts.** Until `notBefore` the outcome is `NOT_YET_VALID`. Keep the file and verify it again later: it becomes `VALID` once the license starts, and the clock mark doesn't stand in the way, because it only follows the clock forward.
- **Metadata is typed.** Its values can be text, numbers, `true` or `false`, `null`, lists or objects. Read a value as the type you expect with `text`, `number` or `flag`, which treat a value of any other type as absent, and read nested values from `metadata()`.
- **Version binding.** If the vendor set `maxVersion` on the license, `VersionBinding.allows` tells whether this release is covered. `maxVersion` is read as text, and any other type allows nothing.

## Running the example

```
./gradlew :jvm:compileJava
java -cp "jvm/build/classes/java/main:<jackson jars>" com.itnovum.license.reference.Example \
    vectors/keys.json vectors/license-files/encrypted.lic 9F2C41A0-7B3D5E61-C08A2F94-17D3B6E5 3.1 2026-01-15T00:00:00Z
```

The third argument is the license key, or `-` for none. The last is the time to check at; the vectors' files are checked at the time their manifest gives.
