# Per-clone proxy routing

OpenClone uses Android's `VpnService` allowlist and the pinned MIT-licensed tun2proxy v0.8.7 Android engine.

## How it works

1. Save an unauthenticated HTTP CONNECT or SOCKS5 proxy profile in Extensions.
2. Select one installed OpenClone-created clone and one saved profile.
3. OpenClone saves the clone-to-profile assignment and asks Android for VPN consent if it has not already been granted.
4. Android's VPN allowlist routes only that selected clone through the TUN interface. OpenClone and other apps are excluded from the allowlist and use the normal network.
5. The tunnel sends IPv4/IPv6 traffic through the chosen proxy and uses tun2proxy's virtual DNS handling.

The foreground notification stays visible while the route is active. Stop the route from Extensions or the notification.

## Android platform limit

Android permits one active VPN service per Android user/profile. Consequently OpenClone can route one selected clone at a time in this implementation. Selecting a different clone/profile requires stopping the current route first. OpenClone checks for an existing VPN and does not silently replace it.

## Current supported scope

- HTTP CONNECT and SOCKS5 endpoints without proxy authentication.
- One selected clone per active tunnel.
- IPv4 and IPv6 routes with virtual DNS.
- Android system consent and foreground notification.

Unsupported: multiple simultaneous clone-to-different-proxy tunnels, authenticated proxy credentials, routing across Android work profiles/users, and cloned apps that require their own VPN service while OpenClone's VPN is active.

The native engine archive is pinned to tun2proxy v0.8.7 and SHA-256 verified during the Gradle build. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Privacy and safety

OpenClone does not capture or inspect the selected clone's payloads. The TUN engine forwards packets to the configured proxy. Use a proxy endpoint you trust; an HTTP proxy connection to the proxy server itself is not encrypted unless the proxy provider separately offers a protected transport. Stop the tunnel before switching networks or using another VPN.
