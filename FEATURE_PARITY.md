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
| Change package ID | Supported with limitations | Binary manifest and resource table are rewritten; arbitrary DEX strings are not rewritten. |
| Add a coloured launcher badge | Partial | Four-corner badge support exists for supported icon resource formats. |
| Replace launcher icon | Partial | Custom PNG replacement supports a limited set of direct PNG/adaptive-icon resource layouts. |
| Select and manage signing identities | Supported | Multiple identities are stored using Android Keystore protection. |
| Import/export signing identity backup | Supported | Password-protected PKCS#12 backup flow exists. |
| Re-sign rebuilt APKs | Supported | APKs are signed and verified before install is offered. |
| Install clone | Supported | Uses Android's PackageInstaller confirmation flow. |
| Export clone APK set | Supported | Exports the rebuilt set as ZIP. |
| Persistent build progress and cancellation | Supported | WorkManager jobs survive Activity recreation and can be recovered. |
| Rebuild after original app update | Supported | Requires original app still installed and matching saved signing identity. |
| Deep package-string rewriting in DEX | Partial | A DEX pass now rewrites package-name string references in every classes*.dex file with token-boundary checks. It preserves class/type descriptors and cannot cover dynamically assembled or obfuscated identities. |
| DEX processing in an isolated worker with explicit heap budgeting | Partial | Build work is already backgrounded and has a heap preflight; DEX-specific memory isolation/budgeting is not implemented. |
| OBB / expansion-data handling | Partial | OBB files included in imported XAPK/ZIP bundles are preserved, renamed when package-bound, and included in exported ZIPs under Android/obb/<clone-package>. Installed-source OBB discovery and automatic placement are not implemented. |
| Broad icon-format replacement (vector, nested layers, adaptive backgrounds) | Partial | Unsupported source icon structures retain original artwork. |
| Detailed compatibility warnings for Google services, FCM, signature checks and server-bound identity | Partial | Current analyzer is heuristic and based on APK declarations/metadata; runtime behavior cannot be guaranteed. |

## Implementation sequence

1. **DEX package rewrite hardening** — expose an advanced opt-in setting, add cancellation checkpoints and per-DEX diagnostics, and expand fixtures for multidex, encoded values, and package-like unrelated strings.
2. **Icon pipeline** — expand replacement and badge support for adaptive icons, vector XML, layer lists, density variants and monochrome resources, with previews and explicit unsupported-format feedback.
3. **OBB support** — discover and import expansion files where Android storage access permits, associate them with the clone package, and provide a transparent export/install workflow.
4. **Compatibility analysis** — improve static checks and present clear warnings for package-bound Google services, Firebase/FCM, signature dependencies, shared-user constraints and known runtime limitations.
5. **Build controls and resilience** — expose advanced options, cancellation checkpoints, memory estimates and detailed per-stage logs without weakening APK signature verification.
6. **Parity test matrix** — add fixtures for single APKs, split sets, icon resource variants, DEX multidex packages, signing verification, cancellation and rebuild/update flows.

## Safety and compatibility boundaries

OpenClone must not bypass DRM, licensing, account security, anti-cheat, app integrity checks or server-side authorization. DEX and manifest transformations are intended only to adapt package identity for apps the user is authorized to modify. A successful build or signature verification does not guarantee runtime compatibility.

## Source and licensing

This audit is based on the reference project's publicly described feature behavior and OpenClone's own source tree. It does not copy reference implementation source code. Keep OpenClone's existing GPL-3.0-only licensing and third-party notices accurate for the code actually included.
