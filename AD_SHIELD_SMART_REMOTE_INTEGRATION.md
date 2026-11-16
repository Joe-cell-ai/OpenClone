# Ad Shield and Smart Remote — integration assessment

This document records the source review for the `feature/ad-shield-smart-remote` delivery. It is an engineering decision record, not a claim that either feature is shipped.

## Ad Shield: selected upstream and integration boundary

### Candidate: Rethink DNS

The upstream project `celzero/rethink-app` is a complete Android application combining a VPN service, DNS-over-HTTPS / DNS-over-TLS / DNSCrypt client, firewall, connection monitor and blocklists. Its README describes the packet-processing layer (`firestack`) as a Go-based hard fork of Outline's `go-tun2socks`, rather than a small standalone Android DNS-blocking dependency.

**Decision:** use Rethink DNS as the architectural reference and evaluate its separately maintained `firestack` / resolver components and licenses before any code is vendored. Do not copy the whole app, reuse its branding, or assume the full app can be embedded as a drop-in library. Record exact upstream commit, license, notices, build reproducibility and Android API compatibility before importing any code.

### Why the existing tun2proxy library is not enough

OpenClone currently downloads a pinned `tun2proxy` native Android archive and declares a JNI bridge. It has proxy profile storage and clone assignment storage. It does not currently have a `VpnService` subclass, manifest registration, TUN setup, native engine invocation, DNS interception/filtering engine, blocklist updater, or leak/failure handling.

A functioning DNS ad blocker needs all of the following before the UI may show it as active:

1. A real Android `VpnService` lifecycle with explicit system consent and foreground notification.
2. TUN interface setup and packet forwarding for IPv4 and IPv6.
3. A DNS engine that parses and answers DNS safely, supports encrypted upstreams, and applies allow/deny rules.
4. Versioned, integrity-checked blocklist updates with visible source, timestamp and failure state.
5. Correct handling for Android Private DNS, user-selected DNS, other VPNs, VPN lockdown, network changes and service revocation.
6. Explicit per-app policy and accurate status; no false claim that traffic is protected when the VPN is stopped or revoked.
7. Tests for DNS parsing, allowlist precedence, blocklist matching, IPv6, encrypted upstream failure, VPN lifecycle, and traffic leaks.

DNS filtering cannot reliably remove ads served from the same hostnames as wanted content. Do not promise universal ad removal or bypass subscription/DRM controls.

## Smart Remote: Android TV Remote v2 research

### Candidate protocol reference

The public project `theharisshah/androidremote` documents a Kotlin Android TV Remote v2 implementation and reports testing against a Xiaomi Mi TV. Its README is useful protocol research: mDNS service discovery, manual IP fallback, pairing on TCP 6467, remote sessions on TCP 6466, protobuf varint framing, six-character on-screen code, and a persistent session ping/read loop.

The project README also explicitly describes not validating the TV certificate because the TV uses a self-signed certificate. **OpenClone will not copy that trust model.** Pairing must establish a cryptographically bound device identity, and subsequent connections must verify a stored TV certificate/public-key fingerprint. Any bootstrap exception must be tightly scoped to first pairing, confirmed by the user-visible code, and resistant to interception; if that cannot be implemented safely, pairing must fail rather than silently accept an arbitrary certificate.

The project credits protocol schemas from `tronikos/androidtvremote2` (Apache-2.0) and AOSP's Google TV pairing protocol. Before vendoring schema or code, verify the actual upstream license files and preserve notices. A README attribution is not a substitute for license review.

### Implementation requirements

- Discover only the advertised Android TV Remote v2 service; offer manual IP entry as a fallback.
- Show a clear device identity and require the user to enter the code displayed on the TV.
- Use TLS and verify the TV identity after pairing; persist keys/certificates in Android Keystore-backed storage.
- Implement protobuf varint framing with bounded message sizes, read timeouts, a dedicated I/O dispatcher and a session ping loop.
- Expose only supported key/media/text actions and capability-gate them from the TV's session configuration.
- Support disconnect, unpair/revoke, reconnect backoff and diagnostics without logging credentials.
- Handle GrapheneOS Network permission denial and explain that VPN lockdown can prevent mDNS discovery. Manual IP entry may still be blocked by the network/VPN policy; do not promise otherwise.
- Test on physical TVs, including Xiaomi and at least one other vendor/model. No brand-wide compatibility claim until model/firmware tests pass.

## Current delivery status

- [x] Correct the proxy-routing documentation so it does not claim a VPN exists.
- [x] Record the upstream architecture and integration risks.
- [ ] Implement and test the VPN service and DNS filtering engine.
- [ ] Implement and test Android TV Remote v2 discovery, secure pairing and controls.
- [ ] Add model/device test evidence and GrapheneOS validation.
- [ ] Open one reviewable PR only after substantive implementation and CI evidence exist.

No Ad Shield or Smart Remote control is considered shipped until its corresponding implementation and tests are complete.


## Implemented foundations (not yet connected to live services)

The branch now contains two isolated, JVM-testable primitives:

- `app/src/main/java/org/openclone/app/adshield/DomainBlocklist.kt`: parses plain-domain and hosts-file rules, normalizes IDNs, matches exact domains and subdomains, and gives explicit allow rules precedence. It does not intercept DNS or network traffic.
- `app/src/main/java/org/openclone/app/smartremote/AndroidTvRemoteFraming.kt`: reads/writes length-delimited protobuf frames with a 1 MiB limit and rejects truncated/invalid frames. It does not implement TLS, pairing, protobuf message schemas, discovery, or remote commands.
- Unit tests are present for both primitives under `app/src/test/java/org/openclone/app/`.

These tests have been added to the repository but have not yet been executed in CI. The delivery checklist above remains incomplete; no UI should advertise either service as active.
