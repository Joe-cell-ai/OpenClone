# OpenClone test build: cloning, TV remote and proxy routing

This branch is a focused, installable test batch for the three user-requested areas. It is not complete until implementation and automated build evidence are attached.

## 1. Clone process — highest priority
- Trace the complete installed-app and imported-APK flows from selection through source/split discovery, rebuild, signing, PackageInstaller session, confirmation and installed-clone refresh.
- Fix failures rather than masking them; preserve cancellation and report actionable stage-specific errors.
- Validate ZIP/APK integrity and APK signatures for every output split before offering installation.
- Test base-only APKs, split APK sets, unreadable/missing inputs, cancellation, low-memory preflight, signing mismatch/update rejection and installer failure callbacks.
- Verify install result handling and that the installed clone appears in the app's clone list and can be launched when Android permits it.
- Never bypass package signing, exported-component rules, app integrity checks or platform security restrictions.

## 2. TV remote control
- Distinguish Android TV / Google TV remote control from the existing OpenClone phone-to-phone companion remote.
- Implement mDNS discovery for _androidtvremote2._tcp, manual IP/port fallback, explicit pairing-code flow, verified TLS certificate identity, protobuf framing/messages, control commands and reconnect.
- Encrypt pairing identities at rest and provide unpair/reset.
- Include an in-app screen with device discovery, manual connect, pairing state and remote buttons.
- Test protocol framing and pairing state; document physical-TV validation still required.

## 3. Proxy routing
- Replace connectivity-test-only behavior with actual TUN traffic routing using the pinned tun2proxy Android library.
- Verify JNI class/method/ABI against the exact pinned artifact before wiring it.
- Use a single VPN owner/coordinator shared with Ad Shield; make modes mutually exclusive unless a combined pipeline is implemented.
- Support authenticated HTTP CONNECT and SOCKS5 routing, secure credential storage, upstream socket protection, DNS behavior, clear status/error handling and clean stop/revoke.
- Do not claim per-app routing unless the selected implementation demonstrably supports it.
- Test configuration, authentication failure, start/stop, network loss and VPN ownership transitions.

## Test build acceptance gates
- Unit tests, Android lint and assembleDebug pass.
- Instrumentation tests cover the clone install/result path and VPN lifecycle where the emulator supports it.
- CI uploads the exact debug APK and records its SHA-256.
- No claim of Pixel 10a / GrapheneOS validation until the user installs and tests on that physical device.
- Keep APK size under review and avoid unnecessary dependencies.
