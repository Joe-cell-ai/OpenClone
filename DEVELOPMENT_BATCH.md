# OpenClone — Consolidated Development Batch

This is the single development batch for the remaining OpenClone roadmap. Keep all implementation, tests, documentation, licensing notices and build fixes on `dev/complete-extension-roadmap` and in PR #66. Do not split this work into separate feature PRs or merge partial features to `main`.

## Required implementation order

### Phase 1 — Shared VPN architecture (first)
- [ ] Establish one Android `VpnService` owner and a shared coordinator/state machine.
- [ ] Define explicit Ad Shield and Proxy modes, consent/permission handling, foreground lifecycle, stop/revoke/error recovery and mutual exclusion.
- [ ] Ensure switching modes tears down the previous tunnel cleanly; do not imply simultaneous operation unless a combined packet pipeline is implemented and tested.
- [ ] Add coordinator and lifecycle tests before wiring either feature into the shared service.

### Phase 2 — Actual Proxy routing
- [ ] Implement TUN-based authenticated HTTP CONNECT, SOCKS4 and SOCKS5 routing through the pinned tun2proxy JNI dependency.
- [ ] Verify the exact JNI entry points and native ABI/package mapping from the pinned artifact before integration.
- [ ] Protect upstream sockets from VPN loops; handle DNS explicitly; close descriptors and native workers reliably.
- [ ] Store proxy credentials securely, redact secrets and sensitive URLs from logs, and expose clear start/stop/error states.
- [ ] Implement per-app selection only where Android and the tunnel implementation genuinely support it; otherwise explain the limitation in the UI.
- [ ] Add routing/configuration/error-path tests.

### Phase 3 — Smart Remote v2
- [ ] Discover Android TV / Google TV devices with `_androidtvremote2._tcp` mDNS and support manual IP/port entry.
- [ ] Implement explicit pairing-code flow and certificate identity verification/pinning; never use trust-all TLS.
- [ ] Implement protobuf schemas/messages and length-delimited framing, pairing and control channels, remote commands, ping/pong, reconnect and lifecycle handling.
- [ ] Encrypt client pairing identities at rest and provide safe unpair/reset behavior.
- [ ] Add protocol/framing/pairing-state tests and document supported device/protocol constraints.

### Phase 4 — Ad Shield hardening and blocklists
- [ ] Harden DNS parsing and responses, UDP/TCP handling, IPv4/IPv6 packet paths and tunnel lifecycle as supported by the architecture.
- [ ] Add managed blocklist sources, safe downloads, update scheduling/manual refresh, source attribution and bounded storage.
- [ ] Support allow/deny rules with deterministic precedence and robust domain normalization.
- [ ] Add tests for malformed packets, truncation, IPv4/IPv6, TCP/UDP DNS, list parsing, updates and rule precedence.
- [ ] Clearly disclose DNS-only limits: embedded DoH and server-side ads cannot reliably be blocked.

### Phase 5 — Extension UI, security and compatibility
- [ ] Integrate complete, discoverable and usable Smart Remote, Proxy and Ad Shield screens; no placeholder-only entry points.
- [ ] Show VPN mode/ownership, permission state, connection status, errors and actionable recovery.
- [ ] Review encrypted credential/identity storage, TLS verification, log redaction, VPN loop prevention and lifecycle cleanup.
- [ ] Review Android 17 and GrapheneOS behavior, including target SDK restrictions and non-exported activity handling. Do not bypass Android security or signature checks.
- [ ] Keep the existing visual direction and avoid unrelated redesign; review APK size and avoid unnecessary dependencies.

### Phase 6 — Full quality gates and delivery
- [ ] Unit tests and instrumentation tests.
- [ ] Android lint and debug build; fix all regressions.
- [ ] Emulator compatibility tests for VPN start/stop, mode switching, network loss and service revocation.
- [ ] GPL-3.0-only licensing notices, third-party license attributions and implementation/user documentation.
- [ ] Produce and attach a verified installable debug APK artifact when CI can generate it.
- [ ] Final PR review only after all feasible gates pass. Record any tests that require physical hardware and do not claim Pixel 10a / GrapheneOS validation until actually performed on that device.

## Delivery rules
1. Work only in this development batch branch and PR #66 until the complete scope is implemented.
2. Preserve current `main` and reconcile newer commits before finalizing.
3. Keep PR #66 draft and unmerged until the consolidated batch is complete and ready for review.
4. Do not claim a build, test, APK or device validation unless its result/artifact is verified.

## Technical constraints
- Android permits only one active VPN per user/profile; Ad Shield and Proxy must share the VPN service/coordinator.
- DNS filtering cannot reliably block embedded DoH or server-side ads.
- Smart Remote must use explicit user-authorized pairing and verified certificate identity; never trust-all TLS.
- Do not bypass Android app/signature security. Compatibility behavior must remain opt-in and safe.
