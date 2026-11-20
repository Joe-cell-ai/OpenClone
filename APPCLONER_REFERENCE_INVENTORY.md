# AppCloner reference inventory for OpenClone

This inventory was extracted from the user-provided JADX archive `Documents.zip` (AppCloner APK export). It records observable integration surfaces, not proprietary implementation details. It is an engineering input for OpenClone and does not imply feature parity.

## Archive characteristics

- JADX export with 15,898 entries across `sources/` and `resources/`.
- Readable Java under `sources/com/applisto/appcloner/` is limited and heavily obfuscated.
- The archive also contains protected DEX payloads, native libraries, and encrypted configuration.
- Native payloads include `libappcloner.so`, `libappclonervpn.so`, and `libdexprotector.so` for arm64-v8a and x86_64, plus hook-related archives under `resources/assets/`.
- These protected binaries, vendor SDKs, and signing materials are not copied into OpenClone.

## Observable feature/integration surfaces

### App and package workflows
- Main app, app selection, APK file, settings, device profiles, app list and clone management activities.
- Receivers named `NewIdentityReceiver`, `LaunchCloneReceiver`, and `ExportImportAppDataReceiver`.
- APK and app providers, including file-sharing and VPN-status providers.
- Tasker action/condition integration is present in the manifest.

### Background and network services
- AppCloner service, VPN service, logcat dump service, HTTP server service, and audio recording service.
- Boot/update-related receivers and wireless debugging pairing receiver.
- Accessibility-bound persistence and install services are declared. OpenClone should not adopt accessibility as a general-purpose interception mechanism; it can interfere with other apps and requires sensitive user privileges.
- The reference manifest requests network, install/delete package, notification, biometric, NFC, camera, location, overlay, foreground-service, audio, phone-state, and storage permissions. OpenClone must request only permissions needed for a user-enabled feature.

### Runtime and compatibility implications
- The archive includes `BytesReplacer.java`, a native-backed streaming byte-replacement helper, plus a UI string warning that some clone variants may take substantially longer. These observations do not define a safe, complete DEX rewrite contract. OpenClone does not copy the helper or protected native payloads; it uses its own parsed-DEX, token-boundary-limited, opt-in pass and emits per-DEX stage diagnostics.
- The presence of hook-related native libraries and protected DEX indicates that the reference app contains a runtime/hooking layer beyond ordinary APK repackaging.
- Manifest declarations alone do not reveal the complete runtime implementation or prove that a feature is active.
- OpenClone's current virtual-space storage and component resolver are not a guest Android runtime. A true in-process guest runtime requires lifecycle, class loading, resource, package-manager, provider, service, broadcast, permission, process and native-library compatibility layers. This is a separate major subsystem, not a safe one-line launch fix.

## OpenClone implementation mapping

| Reference surface | OpenClone direction |
|---|---|
| APK import, clone identity, rebuild, signing, install/export | Keep using the existing clone pipeline; harden correctness and diagnostics |
| Clone launch and guest app execution | Build a separately tested runtime architecture; do not advertise stored APKs as runnable |
| App data export/import | Implement an explicit, user-authorized backup format; do not imply access to another app's private sandbox |
| VPN/network filtering | Use the existing opt-in local VPN feature; keep it independent of guest execution |
| Log capture and diagnostics | Prefer user-consented bug reports and app-owned logs; do not depend on Accessibility or privileged DUMP access |
| Tasker/automation integration | Add only if a safe, documented public intent contract is implemented |
| Device/profile customization | Keep to documented, app-owned clone build settings; do not spoof protected device identity or bypass integrity checks |
| Hook/native runtime features | Reimplement only where lawful, maintainable, and independently testable; never transplant protected vendor binaries |

## Acceptance rule

A capability is complete only when its user flow, implementation, automated tests, and build checks are present. A manifest declaration, settings toggle, stored APK, or progress animation alone is not evidence that a feature works.

## Source boundary

This document describes observable names and structures from the user-supplied archive. It does not reproduce AppCloner source code, protected DEX, native libraries, proprietary configuration, or signing material. OpenClone's existing licensing and third-party notices remain authoritative for its own implementation.
