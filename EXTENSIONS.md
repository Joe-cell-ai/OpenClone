# OpenClone Extensions Platform

## Status

The Extensions Store screen currently presents a preview catalogue only. Extension download, installation, execution, updates and removal are **not implemented**. A catalogue entry must never be presented as installed or active.

## Proposed architecture

OpenClone should keep its APK cloning engine independent from optional extensions. Extensions are optional integrations with a narrow, versioned capability contract.

### Package and trust

- Publish extensions as separately signed Android packages or another explicitly reviewed package format; do not load arbitrary downloaded DEX/JAR code into the OpenClone process.
- Maintain a publisher trust policy and verify package signatures and declared identity before enabling an extension.
- Reject unsigned, tampered, downgraded or incompatible packages.
- Show publisher, version, source, requested capabilities and data handling before installation.
- Support revocation, disable, update and uninstall.

### Communication boundary

- Use explicit Android IPC with a versioned interface for approved extension capabilities.
- Verify calling package identity and signing certificate on every privileged request.
- Expose only narrow operations such as reading non-sensitive build metadata or requesting a user-approved export.
- Do not expose arbitrary filesystem access, signing-key material, clone private data, package installation authority, or unrestricted shell execution.
- Keep long-running work in bounded background tasks with cancellation and resource limits.

### Manifest proposal

An extension descriptor should include:
- Stable extension ID and display name
- Semantic version and minimum/maximum OpenClone API version
- Minimum Android API level
- Publisher identity and signing-certificate digest
- Description, support URL and privacy disclosure
- Requested capability IDs with human-readable reasons
- Entry-point IPC component and supported actions

### Lifecycle

1. Discover a catalogue entry from a trusted catalogue source.
2. Verify catalogue metadata and publisher identity.
3. Display permissions and compatibility before user consent.
4. Download to app-private staging storage and verify signature/hash.
5. Install/enable through Android's package manager and explicit user consent where required.
6. Bind only to the declared IPC interface and re-check identity.
7. Update atomically after verification; retain rollback information.
8. Disable or uninstall and revoke access cleanly.

## Initial extension candidates

| Extension | Intended scope | Initial status |
|---|---|---|
| Icon Studio | Prepare icon assets and adaptive-icon previews | Planned |
| Clone Profiles | Save and reuse non-secret clone configuration | Planned |
| Backup Tools | Export/import supported OpenClone settings | Planned |
| Network Profiles | Configure supported network settings without bypassing app security | Research |
| Developer Diagnostics | View build metadata and compatibility diagnostics | Planned |

## Security boundaries

Extensions must not bypass DRM, app licensing, account security, anti-cheat, Play Integrity, server-side authorization or other app security controls. Extensions must not silently collect user data or alter cloned APKs outside explicitly approved OpenClone operations.

## Delivery phases

1. Finalize capability IDs, IPC contract, descriptor schema and trust policy.
2. Implement signature verification, publisher management and capability consent UI.
3. Build a sample read-only diagnostics extension and automated security tests.
4. Add catalogue download, install, update, disable and uninstall flows.
5. Expand capabilities only after a threat-model review and compatibility tests.
