# OpenClone — Consolidated Development Batch

This is the single development batch for the remaining extension roadmap. Keep related implementation, tests, documentation, licensing notices and build fixes on this branch. Do not split the work into separate feature PRs or merge partial features to `main`.

## Scope

- [ ] **Smart Remote v2:** Android TV / Google TV mDNS discovery, manual-IP fallback, secure TLS pairing with certificate identity/pinning, pairing-code flow, protobuf messages, remote controls, reconnect/lifecycle handling and encrypted identity storage.
- [ ] **Proxy VPN:** real TUN-based authenticated HTTP CONNECT / SOCKS4 / SOCKS5 routing using the pinned tun2proxy dependency; protect upstream sockets against VPN loops; secure credential storage; per-app selection where supported; clear start/stop/error states.
- [ ] **Shared VPN coordination:** one Android `VpnService` owner and explicit modes so Ad Shield and Proxy cannot compete for Android's single active VPN slot.
- [ ] **Ad Shield hardening:** DNS UDP/TCP and IPv4/IPv6 handling as feasible, robust packet parsing/lifecycle, managed blocklist sources and updates, allow/deny rules, bounded storage, source/license notices, and honest DNS-only limitation messaging.
- [ ] **Extension UI integration:** make Smart Remote, Proxy and Ad Shield discoverable and usable in the installed app; avoid placeholder-only screens.
- [ ] **Compatibility and quality:** Android 17 / GrapheneOS compatibility review, unit/instrumentation tests, GPL-3.0-only notices and third-party license attributions, documentation and APK-size review.

## Delivery rules

1. Work only in this development batch branch until the complete scope is implemented.
2. Preserve current `main` and reconcile newer commits before finalizing.
3. Run unit tests, lint, debug APK build and Android emulator compatibility tests; fix failures before requesting final review.
4. Attach a verified installable debug APK artifact when CI can produce it.
5. Do not claim device validation until tested on the user's Pixel 10a / GrapheneOS device.
6. Keep the PR draft and unmerged until the consolidated batch is complete and ready for review.

## Technical constraints

- Android permits only one active VPN per user/profile; Ad Shield and Proxy must share the VPN service/coordinator.
- DNS filtering cannot reliably block embedded DoH or server-side ads.
- Smart Remote must not use trust-all TLS or bypass app/signature security. Use explicit user-authorized pairing and safe compatibility behavior only.
