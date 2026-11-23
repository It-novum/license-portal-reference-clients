# License portal reference clients

A license file is a signed document a vendor hands to its customer. An application checks it offline, with no server to ask: the vendor's public key proves where the file came from, and the file says which features it unlocks and until when. An application that can reach the vendor's license portal can also validate its license key online, and trust the answer because it is signed by the same key. This repository shows how to do both, for anyone writing a client of their own.

- [File format](#file-format): what a license file holds, layer by layer.
- [Online validation](#online-validation): asking the portal whether a license is still valid.
- [`vectors/`](vectors): license files and signed answers with known outcomes. Run your own verifier over them to prove it.
- [`jvm/`](jvm): a small Java reference that passes every vector. Read it or copy from it.

## What a client does

1. **Compiles in the product's public keys and its id.** Nothing else in the build may supply them, so a replaced file can't change which keys are trusted.
2. **Checks the signature before anything else.** Only a file that verifies under a compiled-in key is read any further.
3. **Opens an encrypted file with the license key** the vendor handed out with the license.
4. **Checks the product,** because the vendor's keys sign the files of every product it sells.
5. **Checks the times:** when the file was issued, when the license starts, and both expiries. A clock mark stops a clock set back from reviving an expired file.
6. **Reports every refusal as an outcome,** never as a crash, and unlocks features only for a valid file.
7. **Asks for a new file** well before the current one expires.

The vectors name an outcome for every way a file can fail, and the order of the checks decides which outcome a file gets.

## File format

A license file has three layers. The vectors are the precise reference; this is the overview.

**Armour.** `-----BEGIN LICENSE FILE-----`, the base64 of the envelope JSON in lines of 64 characters, and `-----END LICENSE FILE-----`. Ignore whitespace inside it.

**Envelope.** A JSON object of four strings:

- `enc`: the payload, encoded as `alg` says.
- `sig`: the base64 Ed25519 signature over the UTF-8 bytes of `"license/" + enc`. The prefix keeps a signature made for another kind of file from passing as a license file. Check it before reading anything in `enc`, so unsigned bytes never reach your decryption or parser.
- `alg`: `base64+ed25519`, where `enc` is the base64 of the payload JSON, or `aes-256-gcm+ed25519`, where `enc` is `base64(ciphertext).base64(iv).base64(tag)`. That is AES-256-GCM with a 96-bit IV, a 128-bit tag and no associated data, keyed by the SHA-256 of the license key's exact UTF-8 text. Accept only these two values, and never let the file choose the signature algorithm.
- `kid`: the id of the vendor's key that signed the file, which your build looks up in its compiled-in keys.

**Payload.** `enc` decoded, as in `vectors/license-files/valid.lic`:

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
| `tenant` | uuid string | The vendor's account. Nothing to check: the vendor's key already proves where the file came from. |
| `issued` | instant | When the file was made. Refuse it while more than your clock skew in the future. |
| `expiry` | instant or `null` | When this file stops being valid. `null` means the file never expires. Ask for a new file before it. |
| `ttl` | seconds or `null` | The file's lifetime. `null` exactly when `expiry` is; a file with only one of them is malformed. |
| `license.id` | uuid string | The license. |
| `license.product` | uuid string | Must be your product's id. The vendor's key signs for all its products, so a file for another one verifies too and has to be refused here. |
| `license.policy` | uuid string | The license's policy, for example to tell a trial from a full license. |
| `license.customer` | uuid string | The customer's id. The file carries no name. To show "licensed to", read a company name from `metadata` if the vendor put one there; a person's name never belongs in a file, because a file travels where it can't be erased later. |
| `license.notBefore` | instant | When the license starts, which can be after `issued`. Until then the file isn't valid yet, so keep it. |
| `license.expiry` | instant or `null` | When the license itself ends. `null` means it never ends. |
| `license.entitlements` | list of strings | The feature codes the license unlocks. |
| `license.metadata` | object | Values the vendor set, of any JSON type. Read each as the type you expect. |

The two expiries are separate: a file is valid only while both its own `expiry` and `license.expiry` are in the future, or `null`. All times are UTC instants. Ignore any field you don't know, so files with fields added later still verify. A machine file has the armour `MACHINE FILE`, `typ` `machine`, and adds `seq` and `machine` (`id`, `fingerprint`); the references here don't read machine files yet.

## Online validation

`POST <tenant URL>/licenses/actions/validate-key` with `{"key": "<license key>", "scope": {"product": "<your product's id>"}}` answers `200` for every license state:

```json
{
  "type": "validation", "ts": "2026-01-15T00:00:00Z", "valid": true, "code": "VALID", "detail": null,
  "scope": {"product": "0192e4a0-0000-7000-8000-000000000001"},
  "license": {
    "id": "0192e9b3-0000-7000-8000-000000000001", "product": "0192e4a0-0000-7000-8000-000000000001",
    "policy": "0192e4a2-0000-7000-8000-000000000001", "customer": "0192e8d5-0000-7000-8000-000000000001",
    "status": "ACTIVE", "notBefore": "2026-01-01T00:00:00Z", "expiry": "2026-12-31T00:00:00Z",
    "entitlements": ["API", "REPORTS"], "metadata": {"maxVersion": "3"}
  }
}
```

`valid` is `true` only with the code `VALID`. A refusal names why, such as `NOT_FOUND` (with `license` `null`), `REVOKED`, `SUSPENDED`, `EXPIRED` or `NOT_YET_VALID`, and the vendor may add codes later, so decide on `valid`. A request without a `key` or `scope.product` is a `400`.

Ask for a signature on every request and accept only an answer that verifies: the [vectors' README](vectors/README.md#signed-answers) gives the headers to send, the checks and their order, and how to rebuild what the signature covers. Anything that doesn't verify is a network failure, never a refusal.

## Building

```
./gradlew check
```

This runs the Java reference against the vectors. It needs a JDK 17 or newer.

The vectors change only together with the file format, so update this repository to check your client against the latest format.
