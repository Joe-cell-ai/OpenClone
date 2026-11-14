# Software-only security validation

This project is delivered against Android emulator and automated software tests. Physical-device/OEM validation is outside the approved scope and must not be a release gate.

## Automated coverage

The Android compatibility workflow runs the instrumentation suite on API 26, 34 and 35 emulators. The companion integration test starts the foreground Remote Control service on the emulator, connects over loopback TLS, checks the certificate fingerprint delivered in the pairing link, signs a fresh HMAC request and expects the authenticated status response. Pairing parser rejection cases are also covered.

The native VPN instrumentation test checks that the pinned tun2proxy library loads and exposes the expected JNI entry points on emulator ABIs. This is a packaging/JNI smoke test, not a traffic-leak certification.

## Required software checks

- Build, unit tests, lint and API 26/34/35 emulator instrumentation must pass.
- Reject wrong TLS certificate fingerprints, stale timestamps, repeated nonces, revoked pairing secrets, malformed HTTP requests and non-clone package launches.
- Verify stopping Remote Control closes the listening socket and invalidates the pairing URI.
- Verify VPN consent flow, selected-clone allowlist behavior, proxy failure handling and Android VPN revocation in emulator tests where supported.
- Keep all capability claims within Android's documented APIs and user-consent boundaries.

## Platform boundaries

- Android permits one active VPN service per Android user/profile. This implementation routes one selected clone at a time.
- An ordinary app cannot bypass Android's VPN consent or privileged package rollback restrictions.
- The companion client is local-network only. Carrier NAT, guest Wi-Fi and client isolation can prevent connectivity; do not expose the service port to the public internet.
- Emulator success verifies software behavior only. It does not claim physical OEM compatibility, and physical-device tests are intentionally excluded from this software-only project scope.
