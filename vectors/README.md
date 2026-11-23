# License-file conformance vectors

License files with known answers, for checking a client that verifies license files. Run your verifier over every case and compare its result with the case's `outcome`.

## Files

- `keys.json`: the public keys a build of the test product compiles in, in the shape of `GET /api/tenants/{tenant}/products/{id}/keys`. Here that is the test tenant's key. Each has a `kid`, an `alg`, a `state`, a `publicKey`, the base64 of an X.509 SubjectPublicKeyInfo, and a `product`, which a client ignores.
- `license-files/<case>.lic`: one armoured license file per case.
- `manifest.json`: what a client expects (`algorithm`, the signature part of `alg`, `type`, and `product`, the id of the product the client belongs to), the test license's key (`licenseKey`), the time to check at (`now`), the allowed clock skew (`skewSeconds`), and one entry per case with its `file` and `outcome`. A `VALID` case also lists the `payload` a client reads from it.

These keys sign nothing but these vectors, and no real build compiles them in.

Check every file at the manifest's `now`, never at the wall clock, so a verifier needs its time passed in. Every time is a UTC instant, so the outcomes don't depend on the date or the time zone a client runs in.

## Order of the checks

A file has exactly one outcome, because the checks run in this order and the first that fails decides:

| Step | Check | Outcome when it fails |
|---|---|---|
| 1 | The text is a `LICENSE FILE` armour around a base64 JSON envelope with `enc`, `sig`, `alg` and `kid` | `MALFORMED` |
| 2 | `alg` is `<encoding>+<signature>`: the signature part is the expected algorithm, and the encoding `base64` or `aes-256-gcm` | `UNEXPECTED_ALGORITHM` |
| 3 | The keys your build compiles in hold `kid` | `KEY_UNKNOWN` |
| 4 | `sig` verifies over the UTF-8 bytes of `"license/" + enc`, before `enc` is decoded or decrypted | `BAD_SIGNATURE` |
| 5 | An `aes-256-gcm` file decrypts with the license key, as below | `DECRYPTION_FAILED` |
| 6 | The payload's `v` is one the client understands, here 1 | `UNSUPPORTED_VERSION` |
| 7 | The payload parses, its `typ` is the expected type, its `kid` equals the envelope's, its license has a `notBefore`, and it has both an `expiry` and a `ttl` or neither | `MALFORMED` |
| 8 | `license.product` is your product's id | `WRONG_PRODUCT` |
| 9 | `issued ≤ now + skewSeconds` | `NOT_YET_VALID` |
| 10 | `license.notBefore ≤ now + skewSeconds` | `NOT_YET_VALID` |
| 11 | `now < expiry`, when the file has an `expiry` | `EXPIRED` |
| 12 | `now < license.expiry`, when the license has one | `EXPIRED` |

A file that passes every step is `VALID`. A field the client doesn't know is ignored.

- **Check the product, not the tenant.** The keys your build compiles in belong to the vendor's tenant and sign the files of every product the vendor sells, so a file that verifies may carry a license for another of them. Step 8 refuses it, as the `wrong-product` case shows. A signature that verifies already proves the tenant, so a client doesn't check `tenant`.
- **Encrypted files.** The vendor's policy decides whether a license's files are encrypted, so read both encodings. An `aes-256-gcm` file's `enc` is `base64(ciphertext).base64(iv).base64(tag)`: AES-256-GCM with a 96-bit IV and a 128-bit tag, no associated data, under the SHA-256 of the license key's UTF-8 text exactly as issued. Decrypt only after the signature verifies. An `enc` that isn't three base64 parts, or whose IV or tag has the wrong length, is `MALFORMED`. The `encrypted` case opens with the manifest's `licenseKey`, and `wrong-license-key` doesn't.
- **A license can start after its file was issued.** Before `license.notBefore`, step 10 answers `NOT_YET_VALID`, as the `not-started` case shows. Keep such a file and check it again later: it becomes valid when the license starts. Allow the skew here as for `issued`, because a license often starts the moment its file is issued.
- **A file without an `expiry` doesn't expire.** Step 11 doesn't apply to it, as the `no-expiry` case shows. Step 12 still does, unless the license has no `expiry` either: the `perpetual` case, issued in 2020, is still `VALID`.
- **Step 12 matters because a file can't outlive the license it carries.** A file is a proof of the license, so once the license has ended the file proves nothing.
