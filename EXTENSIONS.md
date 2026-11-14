# OpenClone Extensions Platform

## Current status

The Extensions screen now links to built-in OpenClone capabilities and includes working proxy-profile storage with TCP reachability checks, Android connectivity diagnostics, OpenClone notification-channel settings and an opt-in daily clone-source update check. These are built-in tools, not third-party extension packages. Third-party extension download, installation, execution, update and removal are **not implemented**.

The approved 16-extension delivery plan is tracked in [EXTENSION_ROADMAP.md](EXTENSION_ROADMAP.md). A status of Foundation or Partial does not mean the complete extension is available. In particular, saved proxy profiles do not route cloned-app traffic; the network monitor does not capture third-party traffic; notifications are limited to OpenClone's own channels; and automation only reports update availability without installing apps.

## Architecture and trust requirements

OpenClone should keep its APK cloning engine independent from optional extensions. Future extensions must use a narrow, versioned capability contract.

- Publish extensions as separately signed Android packages or another explicitly reviewed package format; do not load arbitrary downloaded DEX/JAR code into the OpenClone process.
- Verify package signatures and declared publisher identity before enabling an extension.
- Reject unsigned, tampered, downgraded or incompatible packages.
- Show publisher, version, source, requested capabilities and data handling before installation.
- Support revocation, disable, update and uninstall.
- Use explicit Android IPC with a versioned interface for approved capabilities.
- Verify calling package identity and signing certificate on every privileged request.
- Never expose arbitrary filesystem access, signing-key material, clone private data, package-install authority or unrestricted shell execution.
- Keep long-running work in bounded background tasks with cancellation and resource limits.

## Descriptor requirements

An extension descriptor should include:
- Stable extension ID and display name
- Semantic version and minimum/maximum OpenClone API version
- Minimum Android API level
- Publisher identity and signing-certificate digest
- Description, support URL and privacy disclosure
- Requested capability IDs with human-readable reasons
- Entry-point IPC component and supported actions

## Lifecycle

1. Discover a catalogue entry from a trusted catalogue source.
2. Verify catalogue metadata and publisher identity.
3. Display permissions and compatibility before user consent.
4. Download to app-private staging storage and verify signature/hash.
5. Install/enable through Android's package manager and explicit user consent where required.
6. Bind only to the declared IPC interface and re-check identity.
7. Update atomically after verification; retain rollback information.
8. Disable or uninstall and revoke access cleanly.

## Initial safe sample

The first third-party extension should be a read-only diagnostics sample. It may read explicitly exposed, non-sensitive build and compatibility metadata. It must not receive signing keys, credentials, APK contents, private clone data, unrestricted filesystem access or shell execution.

## Security boundaries

Extensions must not bypass DRM, app licensing, account security, anti-cheat, Play Integrity, server-side authorization or other app security controls. Extensions must not silently collect user data or alter cloned APKs outside explicitly approved OpenClone operations.
