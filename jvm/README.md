# Java reference client

A small, readable example of verifying license files offline, for anyone writing a client of their own. It makes every check a license file needs, in the order that gives each file one outcome, and passes the conformance vectors in [`conformance/vectors`](../conformance/vectors). Copy what you need: it targets Java 17 and uses only the JDK and Jackson.

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

## File format

A license file has three layers. The conformance vectors are the precise reference; this is the overview.

**Armour.** `-----BEGIN LICENSE FILE-----`, the base64 of the envelope JSON in lines of 64 characters, and `-----END LICENSE FILE-----`. Ignore whitespace inside it.

**Envelope.** A JSON object of four strings:

- `enc`: the payload, encoded as `alg` says.
- `sig`: the base64 Ed25519 signature over the UTF-8 bytes of `"license/" + enc`. The prefix keeps a signature made for another kind of file from passing as a license file. Check it before reading anything in `enc`, so unsigned bytes never reach your decryption or parser.
- `alg`: `base64+ed25519`, where `enc` is the base64 of the payload JSON, or `aes-256-gcm+ed25519`, where `enc` is `base64(ciphertext).base64(iv).base64(tag)`. That is AES-256-GCM with a 96-bit IV, a 128-bit tag and no associated data, keyed by the SHA-256 of the license key's exact UTF-8 text. Accept only these two values, and never let the file choose the signature algorithm.
- `kid`: the id of the product key that signed the file, which your build looks up in its compiled-in keys.

**Payload.** `enc` decoded, as in `valid.lic`:

```json
{
  "v": 1, "typ": "license",
  "kid": "0192e4a1-0000-7000-8000-000000000001", "tenant": "7c1e0000-0000-4000-8000-000000000001",
  "issued": "2026-01-01T00:00:00Z", "expiry": "2026-01-31T00:00:00Z", "ttl": 2592000,
  "license": {
    "id": "0192e9b3-0000-7000-8000-000000000001",
    "product": "0192e4a0-0000-7000-8000-000000000001",
    "policy": "0192e4a2-0000-7000-8000-000000000001",
    "customer": "0192e8d5-0000-7000-8000-000000000001",
    "notBefore": "2026-01-01T00:00:00Z", "expiry": "2026-12-31T00:00:00Z",
    "entitlements": ["API", "REPORTS"],
    "metadata": {"beta": true, "maxVersion": "3", "note": null, "regions": ["EU", "US"], "seats": 25, "support": {"tier": "gold"}}
  }
}
```

| Field | Type | What a client does with it |
|---|---|---|
| `v` | integer | The format version. Refuse a higher one than you know, because its fields may mean something else. |
| `typ` | string | Must be `license`. |
| `kid` | uuid string | Must equal the envelope's `kid`, so a file can't be re-labelled with another key. |
| `tenant` | uuid string | The vendor's account. Nothing to check: your product's key already proves where the file came from. |
| `issued` | instant | When the file was made. Refuse it while more than your clock skew in the future. |
| `expiry` | instant or `null` | When this file stops being valid. `null` means the file never expires. Ask for a new file before it. |
| `ttl` | seconds or `null` | The file's lifetime. `null` exactly when `expiry` is; a file with only one of them is malformed. |
| `license.id` | uuid string | The license. |
| `license.product` | uuid string | Your product. Nothing to check, for the same reason as `tenant`. |
| `license.policy` | uuid string | The license's policy, for example to tell a trial from a full license. |
| `license.customer` | uuid string | The customer's id. The file carries no name. To show "licensed to", read a company name from `metadata` if the vendor put one there; a person's name never belongs in a file, because a file travels where it can't be erased later. |
| `license.notBefore` | instant | When the license starts, which can be after `issued`. Until then the file isn't valid yet, so keep it. |
| `license.expiry` | instant or `null` | When the license itself ends. `null` means it never ends. |
| `license.entitlements` | list of strings | The feature codes the license unlocks. |
| `license.metadata` | object | Values the vendor set, of any JSON type. Read each as the type you expect. |

The two expiries are separate: a file is valid only while both its own `expiry` and `license.expiry` are in the future, or `null`. All times are UTC instants. Ignore any field you don't know, so files with fields added later still verify. A machine file has the armour `MACHINE FILE`, `typ` `machine`, and adds `seq` and `machine` (`id`, `fingerprint`); this reference doesn't read machine files.

## Running the example

```
./gradlew :jvm:compileJava
java -cp "jvm/build/classes/java/main:<jackson jars>" com.itnovum.license.reference.Example \
    conformance/vectors/keys.json conformance/vectors/license-files/encrypted.lic 9F2C41A0-7B3D5E61-C08A2F94-17D3B6E5 3.1 2026-01-15T00:00:00Z
```

The third argument is the license key, or `-` for none. The last is the time to check at; the vectors' files are checked at the time their manifest gives.
