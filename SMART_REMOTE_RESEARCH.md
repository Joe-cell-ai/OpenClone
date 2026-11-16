# India Smart Remote — compatibility research

## Scope and conclusion

OpenClone can host a unified remote extension, but there is no universal TV/AC/fan control protocol. A brand name alone is not enough to establish compatibility: exact model, firmware, radio hardware, account/app provisioning and local-network topology determine whether a device can be controlled.

The extension must be driver-based. Each device card must show transport and exact compatibility evidence. Never show a generic Bluetooth/Wi-Fi device as controllable merely because Android can discover it.

This document is research and an implementation decision record. It does not claim that device discovery, pairing, control panels, extension APK installation or home-screen shortcuts have shipped.

## Confirmed first-wave targets

| Device family / India-market brand | Transport and protocol | Public evidence | OpenClone decision |
|---|---|---|---|
| Android TV / Google TV televisions and set-top boxes sold by Xiaomi/Redmi, OnePlus, Realme, Vu, Kodak, Motorola, Sony, TCL and other India-market brands | Android TV Remote v2 over mutually authenticated TLS + protobuf; mDNS discovery; on-screen pairing code | Google describes Android TV phone remote operation on the same network or via Bluetooth discovery. Independent Kotlin implementations document Remote v2 on TCP 6466 (control) and 6467 (pairing), including certificate-based pairing. | First software driver. Discover _androidtvremote2._tcp, pair only after the user enters the TV's displayed code, pin the TV certificate, then expose navigation, media, volume, power and text actions supported by the TV. Brand compatibility must be model/firmware verified. Bluetooth discovery is not a generic Bluetooth remote-control channel. |
| Samsung Tizen TVs | SmartThings account/cloud integration or Samsung local remote WebSocket protocol (model/firmware dependent) | Samsung India documents adding a TV in SmartThings, same-Wi-Fi registration and an on-TV PIN/approval flow; remote features vary by model/app. | Separate Samsung driver. Prefer documented/authorized SmartThings integration where available. Local WebSocket support needs model/firmware validation, explicit on-TV approval and certificate/token handling. Do not promise all Samsung TVs. |
| LG webOS TVs | SSAP WebSocket; SSDP discovery; on-TV pairing; newer models may require secure WebSocket | LG UDAP/SSAP documentation describes discovery, pairing, commands, events and queries. Community Kotlin implementations show practical control APIs but are not a substitute for LG model/firmware certification. | Separate LG driver after Android TV. Store pairing key securely; use only public user-facing remote commands. |
| Blue Star Smart AC (Wi-Fi/Bluetooth-enabled models only) | Official app documents local Bluetooth and Wi-Fi control | Blue Star's official app listing explicitly advertises remote Wi-Fi control and local Bluetooth control, multiple ACs and model-specific app features. It does not publish a public third-party protocol/API in the sources reviewed. | Do not reverse engineer or impersonate the official app. Seek a supported partner/API route. Until one is documented, show “official app supports it; OpenClone driver unavailable”. |
| Atomberg smart fans (Wi-Fi-enabled models only) | Atomberg Home Phoenix public cloud API | Atomberg publishes API documentation for smart fans and purifiers. Access requires the user's own API key and refresh token generated in the Atomberg Home app; access is limited to Wi-Fi devices on that account, with a stated 100 calls/day and 5 calls/second quota. | Viable opt-in cloud driver after secure credential storage, token refresh, rate limiting and user consent. Not a Bluetooth driver. |
| Other Indian-market ACs and fans (Voltas, Lloyd, Godrej, Havells, Orient, Crompton, etc.) | Varies by model: proprietary Wi-Fi, BLE, IR, or vendor cloud | No general public control API was verified for these brands in this research. A vendor's own mobile app is not evidence that third-party apps can use its private protocol. | Keep as “research/model verification required”. Add only when a public API, documented protocol, or user-owned bridge is available. |
| Legacy IR-controlled TVs, ACs, fans and set-top boxes across Indian brands | Infrared (IR) | Android ConsumerIrManager can transmit patterns only when the handset exposes FEATURE_CONSUMER_IR. Many phones do not have an IR emitter. A supported external Wi-Fi IR hub is another possible transport. | Add an IR driver only with a verified handset emitter or explicitly paired supported hub and a legally usable, model-matched code database. No IR hardware means no direct phone-to-appliance IR control. |

## Transport facts

### Wi-Fi / local network
- Android NSD/mDNS and SSDP can discover services that devices actually advertise. Discovery is not proof that the device accepts remote commands.
- Android TV Remote v2 uses authenticated TLS and protobuf messages. Pairing is user-authorized by a code displayed on the TV; the client certificate and TV certificate pin must be persisted securely.
- LG webOS and Samsung Tizen use different protocols and pairing rules. They must not be routed through an Android TV driver.
- Same Wi-Fi is usually required for local remotes. Guest Wi-Fi, AP/client isolation, VLANs and multicast filtering can block discovery or control.

### Bluetooth / BLE
- Android 12+ uses runtime BLUETOOTH_SCAN and/or BLUETOOTH_CONNECT permissions as appropriate. Companion Device Manager can provide a system pairing UI for supported companion devices.
- Bluetooth discovery or pairing does not mean an appliance exposes a command service. Standard audio HID/A2DP profiles do not provide AC temperature, fan-speed or TV channel commands.
- Blue Star documents local Bluetooth AC control for supported models, but no public third-party GATT/service specification was found. Do not advertise a generic Blue Star BLE driver until the vendor supplies a supported integration contract.
- Samsung TV Bluetooth support described in SmartThings help covers accessories such as audio devices, keyboards, mice and gamepads; it is not evidence of a generic Bluetooth TV remote API.

### Infrared
- Check PackageManager.FEATURE_CONSUMER_IR and ConsumerIrManager.hasIrEmitter() at runtime.
- Transmit only known, validated carrier/pulse patterns. A handset without an emitter needs an external IR bridge.
- Model-specific code sets and toggle-state limitations must be disclosed; IR usually cannot report the appliance's actual state.

## Proposed OpenClone experience

1. Extensions → Smart Remote (India): categories for TVs, ACs, fans and set-top boxes; search by brand/model; show Wi-Fi, Bluetooth, IR or Cloud API and a compatibility label.
2. Install means install/enable a signed extension package only after publisher/package verification and user consent. It must never silently install an unrelated vendor app or bypass Android's package installer.
3. After installation, request a pinned home-screen shortcut using Android's supported shortcut request flow. If the launcher declines, show a clear fallback to open Smart Remote from Extensions.
4. The shortcut opens the installed extension's own landing screen and saved device list. Device pairing, controls and permissions remain inside that extension.
5. Pairing is explicit and per device. Display brand/model, transport, requested permissions, local/cloud data flow and disconnect/remove controls.
6. Saved device credentials, client certificates and API refresh tokens are encrypted with Android Keystore-backed storage; never put them in exported profile backups or logs.
7. Keep TV, AC, fan and IR drivers separate behind a narrow versioned interface. Unsupported devices remain visible as unsupported, not as broken generic remotes.

## Delivery sequence

- Phase 1 — Android TV Remote v2: mDNS discovery, manual IP fallback, secure pairing, certificate pinning, reconnect, D-pad, Back/Home, media, volume, power and text input. Unit-test protobuf framing, handshake validation, certificate pinning and command encoding. Validate on physical India-market Android/Google TV devices.
- Phase 2 — LG webOS: SSDP discovery, SSAP pairing/key storage, remote/navigation/media/app actions, physical-device tests.
- Phase 3 — IR: runtime emitter detection, model-matched code library, test-transmit flow and optional supported Wi-Fi IR hub.
- Phase 4 — Vendor-authorized appliance integrations: Atomberg public API first; Blue Star only after an official public API/SDK or partner authorization is available; add other brands only with verifiable protocol evidence.
- Phase 5 — Samsung: implement only through a documented/authorized integration and a tested model/firmware matrix. Do not assume SmartThings UI support equals an open local API.

## Security and platform constraints

- Never brute-force pairing PINs, bypass TV prompts, disable certificate checks, use ADB as an undisclosed control backdoor, or extract vendor app credentials.
- Android 12+ Bluetooth permissions must be requested at point of use. Local network discovery and multicast permissions/locks must be scoped and released.
- Android 17/API 37 local-network access restrictions must be handled as target SDK support evolves.
- Android home-screen shortcut creation is launcher-mediated and may require user confirmation; OpenClone cannot guarantee every launcher accepts pinned shortcuts.
- Vendor cloud APIs require clear account consent, secure token storage, quotas, revocation and privacy disclosure.
- A successful discovery is not a successful pairing; a successful pairing is not proof every command is supported.

## Research sources

- Android wireless device discovery and NSD: https://developer.android.com/develop/connectivity/wifi
- Android NSD API and local-network permission notes: https://developer.android.com/reference/android/net/nsd/NsdManager
- Android Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Android IR API: https://developer.android.com/reference/android/hardware/ConsumerIrManager
- Google Android TV Remote service listing: https://play.google.com/store/apps/details?id=com.google.android.tv.remote.service
- Android TV Remote v2 Kotlin implementation and protocol notes: https://github.com/Gatuque/tv-remote
- Android TV Remote v2 protocol reference: https://github.com/tronikos/androidtvremote2
- LG UDAP protocol specification: https://webostv.developer.lge.com/assets/netcast/NetCast-UDAP.pdf
- LG Kotlin SSAP implementation reference: https://github.com/audev482/lgtv-kotlin
- Samsung India SmartThings TV pairing/control: https://www.samsung.com/in/support/tv-audio-video/use-the-smartthings-app-to-control-your-smart-tv/
- Blue Star official Smart AC app listing: https://play.google.com/store/apps/details?id=com.bluestarindia.bluesmart
- Atomberg public API documentation: https://developer.atomberg-iot.com/
- Open-source multi-protocol remote implementation reference (protocol comparison only): https://github.com/amolood/Universal-Remote

## Research limitations

This is a public-source feasibility review, not a physical-device certification. Exact model numbers and firmware versions were not supplied, so no individual appliance is certified compatible. Private vendor protocols are deliberately not treated as supported APIs. Physical-device pairing and command tests are required before a driver is marked Implemented.
