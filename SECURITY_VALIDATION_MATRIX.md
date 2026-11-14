# Remote Control and VPN security validation

## Automated coverage

The Android compatibility workflow runs the same instrumentation suite on API 26, 34 and 35. The companion integration test starts the real foreground Remote Control service on the emulator, connects over loopback TLS, checks the certificate fingerprint delivered in the pairing link, signs a fresh HMAC request and expects the authenticated status response. Pairing parser rejection cases are also covered.

The existing native VPN instrumentation test checks that the pinned tun2proxy library loads and exposes the expected JNI entry points on emulator ABIs. This is a packaging/JNI smoke test, not a traffic-leak certification.

## Physical device/OEM matrix — not yet verified

Do not mark the VPN or Remote Control security validation complete until this matrix is executed on physical devices:

| Device class | Required checks | Status |
|---|---|---|
| Google Pixel, current Android | VPN consent, selected-clone-only routing, IPv4/IPv6/DNS leak checks, Wi-Fi/mobile handoff, force-stop/revoke behavior, TLS pairing and stop/revoke | Pending physical-device run |
| Samsung One UI | Foreground-service lifetime, notification stop action, battery optimization, network handoff, VPN conflict and DNS leak checks | Pending OEM run |
| Xiaomi/HyperOS | Background process restrictions, foreground-service survival, VPN teardown, network handoff and leak checks | Pending OEM run |
| One additional OEM (Motorola/OnePlus/etc.) | Repeat consent, routing, process death and TLS pairing lifecycle checks | Pending OEM run |

For each VPN test, capture packet-level evidence that only the allowlisted clone enters the TUN route, no DNS or IPv6 traffic escapes the configured proxy path, and traffic behavior after proxy failure and Android VPN revocation matches the documented boundary. Android can revoke or kill a VPN service; the app cannot promise fail-closed behavior after OS-level teardown.

For Remote Control, verify that a wrong certificate fingerprint, stale timestamp, repeated nonce, revoked pairing secret, malformed HTTP request and non-clone package launch are all rejected. Confirm that stopping the service closes the listening socket and invalidates the pairing URI.

## Platform boundaries

- Android permits one active VPN service per Android user/profile. This implementation routes one selected clone at a time.
- A user-installed ordinary app cannot bypass Android's VPN consent, OEM background policies, or privileged package rollback restrictions.
- The companion client is local-network only. Carrier NAT, guest Wi-Fi and client isolation can prevent connectivity; do not expose the service port to the public internet.
- This document records a test plan and automated emulator coverage. It is not evidence that physical OEM testing has been performed.
