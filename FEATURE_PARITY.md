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
| Replace launcher icon | Partial | Custom replacement supports direct PNG resources, direct PNG adaptive-icon foregrounds, and vector XML roots/foregrounds. Vector input is regenerated from the selected raster image with a 48x48 viewport and a maximum 32-colour palette, so this conversion is bounded but lossy. Per-resource diagnostics identify applied, unsupported, or failed icon paths and density variants; nested layer-list traversal, other non-PNG formats, icon packs, and generated/themed icon workflows remain pending or unsupported. |
| Select and manage signing identities | Supported | Multiple identities are stored using Android Keystore protection. |
| Import/export signing identity backup | Supported | Password-protected PKCS#12 backup flow exists. |
| Re-sign rebuilt APKs | Supported | APKs are signed and verified before install is offered. |
| Install clone | Supported | Uses Android's PackageInstaller confirmation flow. |
| Export clone APK set | Supported | Exports the rebuilt set as ZIP. |
| Persistent build progress and cancellation | Supported | WorkManager jobs survive Activity recreation and can be recovered. |
| Persistent generated APK build history | Supported | The Cloned APKs tab stores recent APK/OBB output paths and build metadata in app-private preferences, supports install/export/remove, and flags missing output files. The history is capped at 30 entries; APK bytes remain in app-private storage. |
| Rebuild after original app update | Supported | Requires original app still installed and matching saved signing identity. |
| Deep package-string rewriting in DEX | Partial — experimental opt-in | Disabled by default. The pass rewrites matching package-identity string constants and encoded string values in every classes*.dex file with token-boundary checks. Per-DEX start/completion diagnostics and cancellation checkpoints are present; APK-level regression fixtures cover multidex entries, encoded static values, unrelated package-like tokens and unchanged class descriptors. It cannot cover dynamically assembled or obfuscated identities. |
| DEX processing in an isolated worker with explicit heap budgeting | Partial | Build work is already backgrounded and has a heap preflight; DEX-specific memory isolation/budgeting is not implemented. |
| OBB / expansion-data handling | Partial | OBB files included in imported XAPK/ZIP bundles are preserved, renamed when package-bound, and included in exported ZIPs under Android/obb/<clone-package>. Installed-source OBB discovery and automatic placement are not implemented. |
| Broad icon-format replacement (vector, nested layers, adaptive backgrounds) | Partial | Unsupported source icon structures retain original artwork. |
| Detailed compatibility warnings for Google services, FCM, signature checks and server-bound identity | Partial | Current analyzer is heuristic and checks APK declarations/metadata for Firebase, Google services/sign-in, sharedUserId, and Play Integrity/licensing indicators; runtime behavior cannot be guaranteed. |

## Implementation sequence

1. [x] **DEX package rewrite hardening** — the advanced opt-in is off by default; cancellation checkpoints and per-DEX start/completion diagnostics are present. APK-level fixtures cover multidex rewriting, encoded static values, package-like unrelated strings, unchanged class descriptors, and cancellation propagation. Verified on 2026-11-20 by full CI run #480 for commit `e7b012d` (`testDebugUnitTest`, `lintDebug`, and `assembleDebug` all passed).
2. **Icon pipeline** — expand custom replacement beyond direct PNG foregrounds to vector XML, nested layer lists, density variants and monochrome resources.
   - [x] **Per-resource diagnostics** — report resource path, ID, density, operation and outcome for each attempted icon replacement, with bounded logs. Verified 2026-11-21 by full Android CI run [#488](https://github.com/Joe-cell-ai/OpenClone/actions/runs/44886406841) for commit `9a199db` (`testDebugUnitTest`, `lintDebug`, and `assembleDebug` all passed).
   - [ ] **Format expansion** — safely support vector XML, nested layer lists, density variants and monochrome resources where the resource table can be updated without changing unrelated app resources.
     - [ ] **Vector XML replacement** — implementation added for direct vector resources and adaptive-icon vector foregrounds using a bounded 48x48 raster-to-vector conversion; focused tests and full CI verification are pending.
3. **OBB support** — investigate Android-version-appropriate access to installed-source expansion files, while keeping export/import behavior explicit and permission-safe.
4. **Compatibility analysis** — add fixture-backed checks for package-bound Google services, Firebase/FCM, signature dependencies, shared-user constraints and known runtime limitations.
5. **Build controls and resilience** — add per-stage diagnostics, cancellation checkpoints during DEX/OBB processing, bounded temporary-file cleanup and clearer memory estimates without weakening APK signature verification.
6. **Parity test matrix** — add fixtures for single APKs, split sets, vector/adaptive/layer-list icon resources, DEX multidex packages, OBB archive handling, signing verification, cancellation and rebuild/update flows.

## Safety and compatibility boundaries

OpenClone must not bypass DRM, licensing, account security, anti-cheat, app integrity checks or server-side authorization. DEX and manifest transformations are intended only to adapt package identity for apps the user is authorized to modify. A successful build or signature verification does not guarantee runtime compatibility.

## Source and licensing

This audit is based on the reference project's publicly described feature behavior and OpenClone's own source tree. It does not copy reference implementation source code. Keep OpenClone's existing GPL-3.0-only licensing and third-party notices accurate for the code actually included.


## Completed task log

- [x] 2026-11-20 — DEX rewrite hardening test coverage and safer opt-in wording. Commit: `e7b012d198f29d0d2670fa1bc8b17abc192a5647`. Full Android CI run [#480](https://github.com/Joe-cell-ai/OpenClone/actions/runs/44710402654) passed unit tests, lint and debug APK assembly. This verifies build-time behavior only; it is not a device/runtime compatibility claim.

- [x] 2026-11-21 — Icon replacement per-resource diagnostics. Commit: `9a199db5d7636d023209beaa674128a55c40d281`. Full Android CI run [#488](https://github.com/Joe-cell-ai/OpenClone/actions/runs/44886406841) passed `testDebugUnitTest`, `lintDebug`, and `assembleDebug`; the debug APK artifact was uploaded. Vector XML, nested layer-list traversal, non-PNG source resources and monochrome custom-icon replacement remain pending.
