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

Status values: **Foundation** = reusable groundwork exists, but the complete extension UI/workflow is not available; **Planned** = not implemented; **Research** = feasibility/security work required before implementation.

| Priority | Extension | First deliverable | Status |
|---|---|---|---|
| P0 | Icon & Name Studio | Unified per-clone icon/label editor, preview and format diagnostics | Partial existing clone-build controls; studio workflow planned |
| P0 | Clone Profiles | Save, list, apply and delete reusable non-secret clone configurations | Foundation: private profile persistence and validation added; UI/apply flow planned |
| P0 | Multi-Clone Manager | Group, search, sort and bulk-manage OpenClone-created instances | Partial: installed clone discovery, search by clone/original package, multi-select and sequential system-confirmed bulk uninstall UI; grouping/sorting remain planned |
| P0 | Theme Studio | More theme presets and user-selectable accent/surface options | Partial: System, Light, Dark and AMOLED themes already exist |
| P1 | Privacy Guard | Per-clone permission review, explain manifest changes and privacy checklist | Partial: clone manifest permission removal exists; dedicated guard UI and audit trail planned |
| P1 | Storage Manager | Per-clone APK/output size and app-data/cache visibility where Android permits | Partial: installed clone APK/split footprint is shown; other apps' private data/cache sizes are not exposed to ordinary apps |
| P1 | Backup & Restore | Export/import profiles and supported OpenClone metadata with integrity checks | Partial: signing identity PKCS#12 export/import and APK ZIP export exist; unified backup is planned |
| P1 | App Lock | Local OpenClone access gate using supported device authentication | Research: threat model and biometric/device-credential flow required |
| P1 | Proxy Manager | Named proxy profiles and connection test; only supported routing scope | Research: per-clone routing is not generally available to an ordinary app without VPN/OS support |
| P1 | Network Monitor | User-visible diagnostics for OpenClone's own traffic and supported VPN traffic | Research: cannot promise transparent per-app traffic capture |
| P1 | Developer Toolkit | Build/compatibility diagnostics, structured logs and redacted export | Partial: APK inspection and compatibility preflight exist |
| P2 | WebView Manager | Safe settings for OpenClone-owned WebViews and isolated data | Research: does not control arbitrary WebViews embedded in cloned apps |
| P2 | Notification Manager | Explain notification behavior and supported per-clone notification settings | Research: arbitrary notification rewriting requires privileged notification access and is not guaranteed |
| P2 | Automation Studio | Explicit, user-approved workflows using supported Android intents/actions | Planned: no hidden interaction, credential capture or security bypass |
| P2 | Remote Control | Authenticated, opt-in control of OpenClone actions over a secure local/remote channel | Research: threat model, pairing, encryption and revocation required |
| P3 | Extension Updater | Verify catalogue metadata, publisher signatures, version and compatibility before update | Planned: depends on signed extension package and IPC architecture |

## Milestones

### M0 — Foundation and truthful status (current)
- Publish this roadmap and align extension documentation with actual code.
- Add non-secret clone profile persistence with strict input validation.
- Add unit tests for profile validation.
- Keep profile UI/application flow explicitly marked as incomplete.

### M1 — Core clone experience
- Complete Clone Profiles UI: create, rename, duplicate, apply and delete.
- Apply profiles to the existing clone setup form without silently changing a previously selected app.
- Consolidate icon, label, package ID and badge settings into Icon & Name Studio.
- Expand icon replacement diagnostics for unsupported adaptive/vector/layer-list resources.
- Add clone grouping and sorting; filtering, multi-select and sequential system-confirmed bulk uninstall are implemented in the current delivery branch.
- Extend the existing theme selector without breaking current saved preferences.

### M2 — Privacy and data lifecycle
- Add a permission-impact review before building a clone.
- Extend Storage Manager with output APK sizes and app-owned cache visibility where Android exposes it; installed clone APK/split footprint is implemented.
- Define a versioned backup manifest; include profiles and supported app-owned artifacts, not arbitrary private app data.
- Add App Lock with explicit device-authentication fallback and no custom password storage.

### M3 — Network and diagnostics
- Implement named proxy profiles and test connectivity before enabling.
- Scope any VPN-based routing to an explicit, user-approved VPN session; disclose that Android VPN routing may affect the device or selected apps depending on platform support.
- Add structured, redacted diagnostics and export.
- Monitor only traffic OpenClone is authorized and technically able to observe.

### M4 — Platform integrations
- Add WebView settings only for WebViews owned by OpenClone.
- Implement notification controls only where Android exposes supported per-app settings or the user grants the required access.
- Provide compatibility labels for unsupported devices and app configurations.

### M5 — Automation and remote management
- Add a visual workflow editor for supported, consent-based actions.
- Require explicit confirmation for destructive actions and package installation.
- Add remote pairing, strong authentication, encrypted transport, revocation and audit logs before remote actions are exposed.

### M6 — Extension ecosystem
- Finalize a versioned descriptor and capability registry.
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

## Current implementation boundary

Existing OpenClone functionality includes app browsing, APK inspection and rebuild, custom label/package ID, limited custom PNG icon replacement, optional manifest permission removal, clone discovery/launch/uninstall, APK ZIP export, signing identity backup, build history and System/Light/Dark/AMOLED themes. These existing features are not evidence that the broader extensions listed above are complete.

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
| Screen-aware top app bar actions | Partial — Home shows Settings; Apps and Cloned apps expose a Search action that focuses the screen search field; Settings/back navigation polish remains |
| Premium gradient dashboard header | Planned — design direction approved; not yet confirmed in the app |
| Live installed-app and clone counts | Planned — must be data-backed, not hard-coded |
| Account & Tools placement in Settings | Existing direction — verify final navigation and remove duplicate entry points if any |

### Definition of done

- Every primary screen has the correct title and contextual action.
- Search opens and filters the relevant list; Settings and back navigation work from their expected screens.
- Dashboard counts reflect the current repository/app state and handle empty/loading states.
- Gradient styling remains legible in System, Light, Dark and AMOLED themes.
- UI tests or focused navigation tests cover the top-bar actions and dashboard count states.
- Do not mark the workstream complete based on a mockup or documentation alone.
