# App Cloner feature-parity audit

This document tracks the feature set described by the App Cloner reference project against the current OpenClone implementation. It is an implementation checklist, not a claim that every feature is already available.

## Current parity

| Reference capability | OpenClone status | Notes / remaining work |
|---|---|---|
| Clone an installed application | Supported | Uses the installed base APK and split APKs. |
| Clone a standalone APK | Supported | APK inspection and import are available. |
| Clone multi-APK / split packages | Supported | Complete base + split sets are imported and rebuilt together. |
| Import .apks, .xapk, .apkm and ZIP bundles | Supported | Requires a complete, consistent APK set. |
| Change app label | Supported | Applied to the base APK manifest. |
| Override version name and version code | Supported with limits | Optional manifest overrides are applied when the source manifest declares those attributes; version code override is limited to positive 32-bit values. |
| Change package ID | Supported with limitations | Binary manifest and resource table are rewritten; matching package-name strings in multidex DEX files are optionally rewritten experimentally, but class/type descriptors and dynamically assembled strings are not changed. |
| Add a coloured launcher badge | Partial | Four-corner badge support exists for supported icon resource formats. |
| Remove requested permissions from a clone | Supported | Clone setup lists declared permissions and can remove selected declarations from the rebuilt binary manifest before signing. Apps may stop working if a required permission is removed. |
| Replace launcher icon | Partial | Custom PNG replacement supports a limited set of direct PNG/adaptive-icon resource layouts. |
| Select and manage signing identities | Supported | Multiple identities are stored using Android Keystore protection. |
| Import/export signing identity backup | Supported | Password-protected PKCS#12 backup flow exists. |
| Re-sign rebuilt APKs | Supported | APKs are signed and verified before install is offered. |
| Install clone | Supported | Uses Android's PackageInstaller confirmation flow. |
| Export clone APK set | Supported | Exports the rebuilt set as ZIP. |
| Persistent build progress and cancellation | Supported | WorkManager jobs survive Activity recreation and can be recovered. |
| Persistent generated APK build history | Supported | The Cloned APKs tab stores recent APK/OBB output paths and build metadata in app-private preferences, supports install/export/remove, and flags missing output files. The history is capped at 30 entries; APK bytes remain in app-private storage. |
| Rebuild after original app update | Supported | Requires original app still installed and matching saved signing identity. |
| Deep package-string rewriting in DEX | Partial | A DEX pass rewrites matching package-name string constants and encoded string values in every classes*.dex file with token-boundary checks. It preserves class/type descriptors and cannot cover dynamically assembled or obfuscated identities. |
| DEX processing in an isolated worker with explicit heap budgeting | Partial | Build work is already backgrounded and has a heap preflight; DEX-specific memory isolation/budgeting is not implemented. |
| OBB / expansion-data handling | Partial | OBB files included in imported XAPK/ZIP bundles are preserved, renamed when package-bound, and included in exported ZIPs under Android/obb/<clone-package>. Installed-source OBB discovery and automatic placement are not implemented. |
| Broad icon-format replacement (vector, nested layers, adaptive backgrounds) | Partial | Unsupported source icon structures retain original artwork. |
| Detailed compatibility warnings for Google services, FCM, signature checks and server-bound identity | Partial | Current analyzer is heuristic and checks APK declarations/metadata for Firebase, Google services/sign-in, sharedUserId, and Play Integrity/licensing indicators; runtime behavior cannot be guaranteed. |

## Implementation sequence

1. **DEX package rewrite hardening** — expose an advanced opt-in setting, add cancellation checkpoints and per-DEX diagnostics, and expand fixtures for multidex, encoded values, and package-like unrelated strings.
2. **Icon pipeline** — expand custom replacement beyond direct PNG foregrounds to vector XML, nested layer lists, density variants and monochrome resources; add per-resource diagnostics so unsupported formats are reported instead of silently retaining the source artwork.
3. **OBB support** — investigate Android-version-appropriate access to installed-source expansion files, while keeping export/import behavior explicit and permission-safe.
4. **Compatibility analysis** — add fixture-backed checks for package-bound Google services, Firebase/FCM, signature dependencies, shared-user constraints and known runtime limitations.
5. **Build controls and resilience** — add per-stage diagnostics, cancellation checkpoints during DEX/OBB processing, bounded temporary-file cleanup and clearer memory estimates without weakening APK signature verification.
6. **Parity test matrix** — add fixtures for single APKs, split sets, vector/adaptive/layer-list icon resources, DEX multidex packages, OBB archive handling, signing verification, cancellation and rebuild/update flows.

## Safety and compatibility boundaries

OpenClone must not bypass DRM, licensing, account security, anti-cheat, app integrity checks or server-side authorization. DEX and manifest transformations are intended only to adapt package identity for apps the user is authorized to modify. A successful build or signature verification does not guarantee runtime compatibility.

## Source and licensing

This audit is based on the reference project's publicly described feature behavior and OpenClone's own source tree. It does not copy reference implementation source code. Keep OpenClone's existing GPL-3.0-only licensing and third-party notices accurate for the code actually included.
