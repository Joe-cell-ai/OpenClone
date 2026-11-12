# OpenClone

OpenClone is an open-source Android project exploring how to create independent copies of apps installed on a device.

> **Status: early development.** The APK transformation and cloning engine is not implemented yet. Universal compatibility cannot be promised because signing, split APK delivery, native libraries, app integrity checks, and Android platform restrictions can prevent cloning.

## Planned features

- Browse installed applications and inspect package metadata.
- Import and inspect APKs, including split APK sets where supported.
- Customize a clone's display name, icon, and application ID where technically feasible.
- Build and sign modified APKs for user-controlled installation.
- Track generated builds and provide compatibility diagnostics.

## Technology

- Kotlin
- Jetpack Compose and Material 3
- Minimum Android version: Android 8.0 (API 26)

## Safety and privacy

OpenClone is intended for legitimate personal use with apps the user is authorized to modify. It will not bypass DRM, licensing, account security, anti-cheat, or app-integrity protections. Users are responsible for respecting app terms, copyright, and applicable law. APKs and app data should remain on-device unless the user explicitly chooses to export them.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
