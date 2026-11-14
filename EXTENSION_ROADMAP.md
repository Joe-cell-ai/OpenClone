# OpenClone Extension Roadmap

This roadmap covers the 16 extensions approved for OpenClone. Status is deliberately conservative: a feature is **implemented** only when its user-facing flow and tests are present. A data model or design document alone is not a completed extension.

## Delivery principles

- Keep the APK cloning engine independent from optional extension modules.
- Ship one coherent, testable capability at a time; avoid a single large release that is difficult to validate.
- Keep profile/configuration data separate from clone APK bytes, signing identities, credentials and cloned-app private data.
- Use Android system consent for package installation, special access, VPN setup, notification access and other privileged operations.
- Never load arbitrary downloaded DEX/JAR code into the OpenClone process. Future third-party extensions must use signed packages and a narrow, versioned IPC contract.
- Do not bypass DRM, licensing, account security, anti-cheat, Play Integrity, app integrity or server-side authorization.
- Report platform and app compatibility limits honestly. A successful rebuild is not a guarantee that a cloned app will run.

## Priorities and status

Status values: **Implemented** = user flow, persistence and required tests are present; **Partial** = a useful bounded workflow is present but the full extension is not; **Foundation** = reusable groundwork only; **Planned** = not implemented; **Research** = feasibility/security work required before implementation.

| Priority | Extension | First deliverable | Status |
|---|---|---|---|
| P0 | Icon & Name Studio | Unified per-clone icon/label editor, preview and format diagnostics | Partial: clone name/package editor, custom PNG chooser/preview, original-icon reset, four badge positions and ZIP-based raster/XML resource diagnostics; compiled XML resource-table resolution remains |
| P0 | Clone Profiles | Save, list, apply and delete reusable non-secret clone configurations | Implemented: save/update, apply, rename, duplicate and delete; profile intentionally excludes APK bytes, signing keys, credentials and app data |
| P0 | Multi-Clone Manager | Group, search, sort and bulk-manage OpenClone-created instances | Partial: group by original app, search, name/update sorting, multi-select and sequential system-confirmed bulk uninstall; richer group actions remain |
| P0 | Theme Studio | More theme presets and user-selectable accent/surface options | Partial: System, Light, Dark and AMOLED modes, four persistent accent palettes and a persistent custom six-digit hex accent color; dedicated full-screen preview and custom surface palette remain |
| P1 | Privacy Guard | Per-clone permission review, explain manifest changes and privacy checklist | Partial: per-build persistent audit history records requested and removed manifest permissions; runtime grants are not revoked and deeper permission rationale remains |
| P1 | Storage Manager | Per-clone APK/output size and app-data/cache visibility where Android permits | Partial: installed clone APK/split footprint, OpenClone cache/files/build-output totals, safe OpenClone-cache cleanup and links to Android app storage details; other apps' private data/cache sizes remain OS-restricted |
| P1 | Backup & Restore | Export/import profiles and supported OpenClone metadata with integrity checks | Partial: bounded version-1 JSON backup includes clone profiles, appearance mode/accent, proxy profiles and automation cadence with schema validation; APK-set ZIP and signing identity backups remain separate; cryptographic authenticity remains |
| P1 | App Lock | Local OpenClone access gate using supported device authentication | Partial: AndroidX biometric prompt with device PIN/pattern/password fallback and lock-on-background; device-level lifecycle tests remain |
| P1 | Proxy Manager | Named proxy profiles and connection test; only supported routing scope | Partial: up to 10 validated unauthenticated HTTP CONNECT/SOCKS5 profiles with handshake tests; Android VpnService allowlists one selected clone and sends its IPv4/IPv6 traffic through the pinned tun2proxy v0.8.7 engine after OS consent, while other apps bypass. Android permits one active VPN per user/profile, so only one clone route is active at a time; device leak, revoke, proxy-failure and OEM compatibility tests remain |
| P1 | Network Monitor | User-visible diagnostics for OpenClone's own traffic and supported VPN traffic | Partial: live connectivity callbacks for transport, internet validation, metered and VPN state; no third-party traffic capture |
| P1 | Developer Toolkit | Build/compatibility diagnostics, structured logs and redacted export | Partial: APK inspection, compatibility preflight, build history, bounded redacted runtime event log and JSON share/export; deeper engine instrumentation remains |
| P2 | WebView Manager | Safe settings for OpenClone-owned WebViews and isolated data | Partial: isolated OpenClone-owned browser with exact HTTPS host allowlist, JavaScript/DOM/file access and mixed content disabled; cannot control cloned-app WebViews |
| P2 | Notification Manager | Explain notification behavior and supported per-clone notification settings | Partial: manages OpenClone's own update channel and deep-links to Android notification settings for each detected clone; cannot rewrite third-party notification payloads |
| P2 | Automation Studio | Explicit, user-approved workflows using supported Android intents/actions | Partial: opt-in WorkManager source-app update checks on a selectable 1-, 3- or 7-day cadence; reports only and never auto-installs |
| P2 | Universal Remote Centre | Multi-protocol appliance catalogue and safe device-specific command adapters | Partial: protocol-neutral appliance/transport models and bounded scenes; built-in Android IR transmission, tested Pronto Hex decoding and bounded raw Flipper .ir import; local DNS-SD discovery for Cast/AirPlay/Matter advertisements (Matter classified separately); permission-gated, bounded BLE advertisement discovery; and offline saved IR profiles with brand/model/category metadata, search, delete, validated command storage and per-button transmit for user-imported files. User-imported profiles are not manufacturer-verified and must be tested against the exact appliance. Still missing: curated signed/downloadable verified model code catalogue and automatic brand/model discovery, IR receiver learning and documented external USB-C IR bridge adapters, Cast/AirPlay/Matter control adapters and Matter commissioning, BLE GATT pairing/control and verified device profiles, external RF hubs and manufacturer cloud integrations. A Mi Remote-style TV/media and AC button layout is now present for user-imported profiles, with buttons enabled only when matching commands exist; saved favorites, room labels and user-defined multi-command scenes are persisted locally. These layouts do not supply missing IR codes and are not manufacturer-verified. Android ConsumerIrManager is transmit-only. Appliances without a remote interface require a supported smart integration or external bridge |
| P2 | Phone Remote Control | Authenticated, opt-in remote view and control of OpenClone actions over a secure local/remote channel | Partial: user-started TLS 1.2 foreground service with Android Keystore certificate pinning, AES-GCM encrypted pairing secret, HMAC request authentication, freshness/replay checks, explicit stop/revocation, a narrow status/launch-clone API and a certificate-pinned companion client; branch adds a single screen snapshot endpoint and viewer. screen-capture surface resizing was corrected in PR #45 by replacing the ImageReader surface and updating the VirtualDisplay; full CI/device confirmation remains separate. Continuous streaming, remote input, physical device/OEM lifecycle testing and QR pairing remain |
| P3 | Extension Updater | Verify catalogue metadata, publisher signatures, version and compatibility before update | Partial: publisher-key fingerprint confirmation/revocation, signed catalogue verification, HTTPS-only bounded download, package digest/signature verification, Android PackageInstaller staging and persisted user-visible install-result state are implemented. A signed hosted seed catalogue and prefilled publisher candidate are now included, but the catalogue is intentionally empty until real extension APKs and the IPC contract exist. Physical installer validation and rollback remain; Android RollbackManager is restricted to privileged MANAGE_ROLLBACKS callers |

## Milestones

### M0 — Foundation and truthful status (completed)
- Publish this roadmap and align extension documentation with actual code.
- Add non-secret clone profile persistence with strict input validation.
- Add unit tests for profile validation.
- Keep profile UI/application flow explicitly marked as incomplete.

### M1 — Core clone experience
- Complete Clone Profiles UI: create, rename, duplicate, apply and delete.
- Apply profiles to the existing clone setup form without silently changing a previously selected app.
- Consolidate icon, label, package ID and badge settings into Icon & Name Studio.
- Expand icon replacement diagnostics for unsupported adaptive/vector/layer-list resources.
- Add clone grouping and sorting; filtering, multi-select, sequential system-confirmed bulk uninstall and grouping by original app are implemented in main.
- Extend the existing theme selector without breaking current saved preferences; four persistent accent palettes are now available alongside the four theme modes.

### M2 — Privacy and data lifecycle
- Add a permission-impact review before building a clone; the setup lists requested permissions, offers per-permission removal selection, explains manifest-only effects and persists a bounded per-build audit history. Richer permission rationale remains.
- Extend Storage Manager with output APK sizes and app-owned cache visibility where Android exposes it; installed clone APK/split footprint, OpenClone cache/files totals, safe cache cleanup and Android app-details links are implemented.
- Add versioned JSON clone-profile backup export/restore with schema validation and bounded input; APK bytes, signing identities, credentials and clone-private app data remain excluded.
- Add App Lock with AndroidX biometric prompt, device-credential fallback and no custom password storage; device lifecycle verification remains.

### M3 — Network and diagnostics
- Implement named HTTP CONNECT/SOCKS5 proxy profiles and test an unauthenticated tunnel handshake; selected-clone routing is now implemented through Android VpnService and tun2proxy, with one active clone route per Android user/profile. Proxy authentication and device leak/OEM compatibility validation remain.
- Scope VPN-based routing to an explicit, user-approved VpnService session. A selected-clone allowlist, IPv4/IPv6 TUN routes and pinned tun2proxy packet engine are now implemented; only one active VPN/clone route per Android user/profile is supported, and device leak/OEM compatibility testing remains.
- Add structured, redacted diagnostics and export; bounded runtime event capture and JSON sharing are now available, while deeper engine instrumentation remains.
- Monitor only traffic OpenClone is authorized and technically able to observe.

### M4 — Platform integrations
- Add a separate OpenClone-owned managed WebView with exact HTTPS host allowlist, blocked non-allowlisted subresources and conservative WebView settings; cloned-app WebViews remain outside OpenClone's control.
- Implement notification controls only where Android exposes supported per-app settings or the user grants the required access; OpenClone's channel and Android settings deep-links for detected clones are implemented.
- Provide compatibility labels for unsupported devices and app configurations.

### M5 — Automation and remote management
- Add a visual workflow editor for supported, consent-based actions.
- Require explicit confirmation for destructive actions and package installation.
- Add remote pairing, strong authentication, encrypted transport, revocation and audit logs before remote actions are exposed. The TLS 1.2 pinned-certificate service, encrypted pairing secret, authenticated status/clone-launch endpoints, stop/revocation flow and certificate-pinned companion client are implemented; QR pairing and physical device/OEM lifecycle verification remain.

### M6 — Extension ecosystem
- Add a versioned signed descriptor and package-digest verifier with API compatibility checks. The signed catalogue envelope and publisher key are now hosted in the repository; the app still requires explicit fingerprint confirmation. The seed catalogue remains empty until distributable signed extension APKs exist.
- Verify publisher identity, package signature, version and compatibility.
- Use explicit Android IPC; verify caller identity on every privileged request.
- Require per-capability consent and provide disable, revoke, update and uninstall.
- Start with a read-only diagnostics sample extension; expand capabilities only after security review.

## Definition of done

An extension is complete only when it has:
1. A visible user flow and clear unavailable/unsupported states.
2. Persistence and lifecycle behavior appropriate to the feature.
3. Input validation and error handling.
4. Unit tests plus Android compatibility tests where platform APIs are involved.
5. Privacy/security review for sensitive capabilities.
6. Documentation updated from Planned/Research/Partial to Implemented only after verification.

## Security validation status\n\nThe companion client and authenticated service exchange are covered by an Android instrumentation test. The updater now persists and displays Android PackageInstaller result callbacks, and the repository hosts a signed empty catalogue seed with a publisher-key candidate and signing helper. The seed is not a populated store. CI now targets API 26, 34 and 35. See [SECURITY_VALIDATION_MATRIX.md](SECURITY_VALIDATION_MATRIX.md) for the required physical Pixel, Samsung, Xiaomi and additional-OEM checks. Physical device/OEM security testing is not complete and must not be inferred from emulator success.\n\n## Current implementation boundary

Existing OpenClone functionality includes app browsing, APK inspection and rebuild, custom label/package ID, limited custom PNG icon replacement, optional manifest permission removal, clone discovery/launch/uninstall, APK ZIP export, signing identity backup, build history, System/Light/Dark/AMOLED themes and four saved accent palettes. Versioned clone-profile backup with appearance and proxy settings, bounded privacy audits and redacted runtime/build diagnostics are also available; this does not back up clone-private data or signing identities. The current extension tools also provide HTTP CONNECT/SOCKS5 handshake tests, live Android connectivity diagnostics, OpenClone cache cleanup, per-clone notification settings, selectable update-check cadence, custom theme colors, settings-aware profile backups, validated signature/authentication primitives, a trusted-publisher catalogue verification foundation and an opt-in TLS remote-control session with a narrow clone-launch API. Per-clone proxy routing now uses Android VpnService with an explicit selected-clone allowlist and a pinned tun2proxy packet engine; only one clone can be routed at a time because Android permits one active VPN per user/profile. Third-party traffic/notification access remains unavailable. These existing features are not evidence that the broader extensions listed above are complete.

## Core UI workstream — Top Bar & Dashboard

This is a core application experience, not a seventeenth extension. It is tracked alongside the extension roadmap so the shared navigation and dashboard are delivered consistently.

### Approved design direction

- Visual direction: **Concept C — Premium Futuristic UI**.
- Use a rounded blue-to-purple-to-cyan gradient dashboard header.
- Show live counts for installed apps and created clones; counts must come from actual app data and update when that data changes.
- Keep the current screen title clearly visible on the left of the top app bar.
- Home: show a clearly visible Settings gear/action on the right.
- List screens: show Search on the right and make it open the relevant list search.
- Settings: show a back arrow and the Settings title.
- Keep Settings easy to discover; do not reduce it to a tiny or hard-to-find icon.
- Keep Account & Tools inside Settings, consistent with the existing navigation decision.
- Preserve the colourful taskbar icon language used by the Cloned Apps destination, with consistent icon sizing and readable labels.

### Delivery sequence and status

| Item | Status |
|---|---|
| Existing destination/taskbar navigation | Partial — current navigation exists; visual consistency and final polish remain |
| Screen-aware top app bar actions | Partial — Home shows Settings; Apps and Cloned apps expose Search; system and visible in-app Back navigation are merged; contextual settings/back polish remains |
| Premium gradient dashboard header | Implemented — rounded blue/purple/teal gradient header is present |
| Live installed-app and clone counts | Implemented — header reads installed-app and discovered-clone counts from current app state |
| Account & Tools placement in Settings | Existing direction — verify final navigation and remove duplicate entry points if any |

### Definition of done

- Every primary screen has the correct title and contextual action.
- Search opens and filters the relevant list; Settings and back navigation work from their expected screens.
- Dashboard counts reflect the current repository/app state and handle empty/loading states.
- Gradient styling remains legible in System, Light, Dark and AMOLED themes.
- UI tests or focused navigation tests cover the top-bar actions and dashboard count states.
- Do not mark the workstream complete based on a mockup or documentation alone.
