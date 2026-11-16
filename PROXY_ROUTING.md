# Per-clone proxy routing — implementation status

> **Not currently implemented.** This document records the intended design, not a shipped capability. The current source tree does not contain a `VpnService` subclass, a VPN service manifest declaration, a call to `VpnService.prepare()`, or a call to the native `Tun2proxy.run()` / `Tun2proxy.stop()` methods. The existing proxy profile and clone-assignment UI must not be treated as evidence that traffic is being routed.

## Existing pieces

- Proxy profile validation and storage for unauthenticated HTTP CONNECT and SOCKS5 endpoints.
- Clone-to-profile assignment storage.
- A pinned, SHA-256-verified tun2proxy v0.8.7 Android native library archive and a JNI declaration.

These pieces do not create a TUN interface, start a foreground VPN service, route packets, provide DNS filtering, or guarantee traffic blocking.

## Required implementation before this feature can be enabled

1. Add a dedicated Android `VpnService` subclass and declare it with `android.permission.BIND_VPN_SERVICE` and the `android.net.VpnService` intent action.
2. Obtain explicit Android VPN consent through `VpnService.prepare()`; never start or replace another VPN silently.
3. Establish and configure the TUN interface, define IPv4/IPv6 routes and DNS behavior, and start the native packet engine with validated arguments.
4. Handle foreground-service requirements, notification stop action, network changes, engine failure, service revocation, process death, and explicit user stop.
5. Verify that the selected clone is the only routed application and that excluded applications continue to use the normal network.
6. Add instrumented tests for VPN consent, route configuration, engine startup/failure, stop/revoke behavior, and supported Android versions. Test GrapheneOS separately, including Network permission denial and VPN lockdown.
7. Do not describe the route as fail-closed after Android revokes or kills the VPN service. Android owns that lifecycle boundary.

## Android platform limits

Android allows one active VPN service per Android user/profile. This feature must detect an existing VPN and explain the conflict rather than silently replacing it. VPN configuration is per Android user/profile.

## Intended supported scope (not yet available)

- One selected OpenClone-created clone per active tunnel.
- HTTP CONNECT and SOCKS5 upstreams.
- IPv4 and IPv6, with explicitly tested DNS handling.
- Android system consent and a persistent foreground notification.

Not supported by the current code: authenticated proxy credentials, multiple simultaneous clone-to-different-proxy tunnels, routing across Android work profiles/users, or compatibility with a cloned app that requires its own VPN.

## Ad Shield is a separate capability

A proxy tunnel is not an ad blocker. DNS-based ad/tracker filtering requires a real DNS filtering engine, maintained blocklists, explicit DNS upstream and Private DNS behavior, per-app policy, and leak/failure handling. Do not expose an Ad Shield enabled state until those components are implemented and tested. DNS filtering cannot reliably remove ads served from the same domains as requested content.

## Privacy and safety requirements

- Do not inspect or log application payloads.
- Explain that an ordinary HTTP proxy connection is not encrypted unless the upstream transport itself is protected.
- Never override Android Private DNS or another VPN without clear user action.
- Never claim protection after VPN revocation, process death, or an unsupported network transition.
