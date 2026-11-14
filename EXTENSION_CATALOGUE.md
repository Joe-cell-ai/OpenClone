# Hosted extension catalogue

The signed seed catalogue is hosted directly from this repository:

`https://raw.githubusercontent.com/Joe-cell-ai/OpenClone/main/extension-catalogue/catalogue.json`

The seed catalogue is intentionally empty because this repository does not yet contain distributable, independently packaged extension APKs. It is a real signed envelope and verifies only after the publisher key below has been explicitly trusted in OpenClone.

## Publisher trust

- Publisher: OpenClone Catalogue Publisher
- Publisher ID: `f33f2fb1fb231984a2234160`
- SHA-256 fingerprint (DER SubjectPublicKeyInfo): `f33f2fb1fb231984a22341605a89da5bf2a125fea9dc5ebe67b65e7465654ddf`
- Public key: [catalogue-publisher-public.pem](extension-catalogue/catalogue-publisher-public.pem)

Import the public key in Extensions → Extension Updater, inspect the displayed fingerprint, compare it with the fingerprint above through this repository's trusted source, and explicitly confirm it. Then use the hosted URL above. The app does not silently trust a key merely because it was downloaded beside a catalogue.

## Signing a catalogue update

Keep the matching private key offline or in a restricted secret store. Never commit it. The private key is not part of this repository.

1. Create a compact UTF-8 payload with `formatVersion: 1` and an `extensions` array.
2. For every package, sign the descriptor canonical bytes: six UTF-8 lines containing extension id, semantic version, publisher, minimum OpenClone API, maximum OpenClone API and lowercase APK SHA-256, with LF separators and no trailing LF. The resulting ECDSA/SHA-256 signature is the entry's `signature`.
3. Generate the entry's digest and descriptor signature with `bash tools/sign_extension_package_descriptor.sh <id> <version> <publisher> <min-api> <max-api> <apk> <https-download-url> private-key.pem`. Add its JSON output to the payload's `extensions` array.
4. Run `bash tools/sign_extension_catalogue.sh payload.json private-key.pem catalogue.json`.
5. Review the generated envelope, then commit the updated `extension-catalogue/catalogue.json` to main.

The script signs the exact payload bytes. Do not reformat or edit the payload after signing. The app verifies the catalogue envelope and each downloaded package descriptor independently.

## Current limitation

This provides a hosted, signed catalogue endpoint and reproducible signing process, not a populated store. The catalogue remains empty until a real extension packaging/IPC ABI and publisher-signed extension APKs exist. A normal app also cannot invoke Android's system rollback manager: Android protects those APIs with the privileged `MANAGE_ROLLBACKS` permission. OpenClone must not claim automatic APK rollback on ordinary consumer devices. See [Android RollbackManager API](https://developer.android.com/reference/android/content/rollback/RollbackManager).
