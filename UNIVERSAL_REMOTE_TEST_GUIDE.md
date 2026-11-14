# Universal Remote Centre — test and compatibility guide

## What the current build can do

- Transmit raw IR patterns with Android's built-in `ConsumerIrManager`, only on phones that report an IR emitter and support the selected carrier frequency.
- Decode supported Pronto Hex type-0000 codes and transmit them through the built-in emitter.
- Import raw-signal Flipper `.ir` files (parsed protocol entries are not yet encoded).
- Save imported commands locally as offline remotes with a user-entered brand, exact model and appliance category. Search, delete and per-button transmission are available.
- Discover selected DNS-SD advertisements for Google Cast, AirPlay and Matter on the local network.
- Scan BLE advertisements with runtime permission handling, a 15-second timeout and a 64-device display cap.

## What discovery does not mean

A discovered Cast, AirPlay, Matter or BLE endpoint is not automatically controllable. This build does not implement Cast/AirPlay control, Matter commissioning/controller APIs, BLE GATT pairing/writes, RF 315/433 MHz transmission, or manufacturer cloud integrations. Do not show these as working remote controls.

## IR profile import and testing

1. Obtain an IR file for the exact appliance model from a source you trust.
2. Import the raw Flipper `.ir` file. Only raw timing entries are accepted by the current parser.
3. Enter the remote name, brand, exact model and category, then save it offline.
4. Keep the appliance in sight and test one button at a time.
5. Confirm the appliance responds before relying on the command. Different revisions of a model can use different codes.
6. If the phone has no built-in IR emitter, the app cannot transmit through `ConsumerIrManager`; a supported external transmitter bridge is required and is not yet integrated.

Saved imported profiles are labelled user-imported, not verified. Do not describe them as manufacturer-verified.

## Appliance limitations

Refrigerators, washing machines, smart plugs, lights, curtains and air purifiers are controllable only if the specific model exposes a documented IR, LAN, BLE, Matter, RF-hub or manufacturer API interface. Many appliances do not expose any remote-control interface. A compatible smart controller/bridge or manufacturer integration is required in those cases. Safety interlocks and appliance-local safety controls must never be bypassed.

## Release validation still required

- Android CI build, lint, unit tests and API 26/34/35 emulator tests must pass.
- Physical testing is required on an IR-equipped Android phone and at least one device for each claimed protocol.
- Test BLE permissions, scan stop/restart, lifecycle teardown and devices with BLE absent.
- Test DNS-SD discovery on real Wi-Fi networks, including guest/client-isolated networks.
- Confirm imported IR codes on the exact brand/model and report unsupported devices clearly.
- Do not publish Matter, BLE, Cast/AirPlay, RF or cloud control as implemented until a working, reviewed adapter and device-specific tests exist.
