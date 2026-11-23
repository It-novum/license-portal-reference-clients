# Conformance vectors

License files and signed server answers with known outcomes, for checking a client of your own. Run your verifier over every case and compare its result with the case's `outcome`.

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

## Signed answers

`answers/<case>.json` holds one validation as it went over the wire: the `request` your client sent, with its `method`, `authority`, `path`, `query` (without its `?`, `null` when it had none), `headers` and `body`, and the `answer` it got, with its `status`, `headers` and `body`. Header names are in lowercase, and bodies are text. `answer-manifest.json` names the key list, the expected `algorithm` and `product`, the time to check at (`now`) and the allowed difference of the answer's `Date` from it (`skewSeconds`), and one entry per case with its `file` and `outcome`. A verified answer also lists its `code`, and a `VALID` or `REFUSED` one the `license` a client reads from it, when it carries one.

A client asks for a signature on every request: `Accept-Signature: sig1=();keyid="<a compiled-in key's kid>";nonce="<fresh random text>"`. It sends `Content-Digest: sha-256=:<base64 of the SHA-256 of the body>:` too, so the answer is bound to the body it sent. The server always signs the same components, whatever the request lists, and returns the nonce among the signature's parameters.

An answer has one of these outcomes:

| Outcome | Meaning | What the client does |
|---|---|---|
| `VALID` | A verified answer with `valid` `true` for your product | Unlocks what the license holds |
| `REFUSED` | A verified answer with `valid` `false`, whatever its `code` | Treats the license as refused. Act on a `code` you know; one you don't know is still a refusal |
| `WRONG_PRODUCT` | A verified answer whose `license.product` isn't yours | Treats it as a refusal |
| `ERROR` | A verified `4xx` other than `429` | Fixes the request; doesn't retry it, and changes no license state |
| `NETWORK_FAILURE` | Anything else | Changes no license state, keeps any file it holds, and retries later |

Anything that doesn't verify is a network failure, never a refusal: an attacker who can forge one could otherwise lock a customer out. The checks run in this order, and the first that fails decides:

| Step | Check | Outcome when it fails |
|---|---|---|
| 1 | The status isn't `429` or `5xx`, which are never answers about the license | `NETWORK_FAILURE` |
| 2 | `Signature-Input` and `Signature` both hold a member under the label you asked for: an inner list of components with parameters, and a byte sequence | `NETWORK_FAILURE` |
| 3 | The components are exactly `"@method";req "@authority";req "@path";req "@query";req`, then `"content-digest";req` if and only if you sent `Content-Digest`, then `"@status" "content-digest" "date"` | `NETWORK_FAILURE` |
| 4 | The parameters hold `created` as an integer, `keyid` as the `kid` you asked for, and `nonce` as the nonce you sent | `NETWORK_FAILURE` |
| 5 | The answer's `Content-Digest` holds a `sha-256` member equal to the SHA-256 of the body | `NETWORK_FAILURE` |
| 6 | The signature verifies under the compiled-in key `keyid` names, over the signature base built from what you sent and the answer's status, `Content-Digest` and `Date`, as below | `NETWORK_FAILURE` |
| 7 | The answer's `Date` is within `skewSeconds` of `now` | `NETWORK_FAILURE` |
| 8 | The status is `200`: a `4xx` is `ERROR`, any other status `NETWORK_FAILURE` | `ERROR` or `NETWORK_FAILURE` |
| 9 | The body is a JSON object with a boolean `valid`, a string `code`, and a `license` that is an object or `null`, and `valid` `true` comes with a license | `NETWORK_FAILURE` |
| 10 | `license.product` is your product's id, when the answer carries a license | `WRONG_PRODUCT` |
| 11 | `valid` is `true` | `REFUSED` |

An answer that passes every step is `VALID`. Ignore any field you don't know, in the body and among the signature's parameters.

The signature base is RFC 9421's: one line per component, `"<name>"`, `;req` for the request's parts, `: ` and the value, joined by a line feed, and last the line `"@signature-params": ` followed by the member's value exactly as `Signature-Input` holds it after `sig1=`. The values come from what you sent, never from the answer:

- `"@method";req`: the method, `POST`.
- `"@authority";req`: the host you sent the request to, in lowercase, with its port only when it isn't the scheme's default.
- `"@path";req` and `"@query";req`: the path and the query as you sent them, without decoding; the query with its leading `?`, and `?` alone when there was none.
- `"content-digest";req`: the `Content-Digest` you sent.
- `"@status"`, `"content-digest"` and `"date"`: the answer's status code and its two headers.

The signature is the bytes between the colons of the `Signature` member, base64-decoded, and it is Ed25519 over the UTF-8 bytes of the base.

- **A refusal you asked to have signed can be trusted,** so `not-found` and `expired` are `REFUSED`, and `invalid-request` is `ERROR`. Unsigned ones, such as `key-unusable` and `html-page`, are network failures.
- **The request's parts come from what you sent.** An answer signed for another method, host, path or query doesn't verify, as the `other-*` cases show, so an answer to another call can't be handed to your client.
- **Bind the body.** With `Content-Digest` sent, an answer has to cover `"content-digest";req`: `request-digest-not-covered` doesn't, and `request-body-changed` covers the digest of a body changed on the way. Without it, the server's answer says nothing about the key you sent.
- **A verified answer's `Date` raises your clock mark,** as a verified file's `issued` does.
