# OpenClone

OpenClone is an open-source Android app cloner and APK inspection tool.

## Working features

- Browse installed user apps and optionally system apps.
- Search installed apps by name or package ID.
- Inspect a single APK's package, version, SDK levels, and requested permissions.
- Build a clone from an installed app's base APK and its installed split APKs.
- Change the clone's app label and package ID.
- Optionally mark the launcher icon with a coloured badge so the clone is easy to distinguish.
- Rewrite the compiled binary manifest and resource table, then re-sign every APK with a unique per-install RSA signing identity.
- Verify each rebuilt APK signature before offering installation.
- Install the rebuilt APK set through Android's PackageInstaller confirmation flow.
- Export the rebuilt APK set as a ZIP archive.

## Compatibility limitations

No cloner can guarantee that every Android app will work after cloning. OpenClone currently changes the binary manifest and resource table, but does not rewrite arbitrary DEX string constants or bypass app integrity checks. Apps that hard-code their original package name, check their signing certificate, use package-bound Google services, licensing, anti-cheat, DRM, or server-side registration may fail or refuse to run. Clones start with separate, empty app data; original app data and sign-ins are not copied.

User-selected custom icon upload, DEX compatibility patches, clone update management, and clone uninstall management are not implemented yet. The optional icon badge marks the existing launcher icon with a coloured dot.

## Technology

- Kotlin and Jetpack Compose / Material 3
- Android minimum SDK 26 (Android 8.0)
- JDK 17, Android Gradle Plugin 8.9.1, Gradle 8.11.1
- Android apksig for APK signing and verification

## Build

Open the project in Android Studio and sync Gradle. CI runs:

```bash
gradle testDebugUnitTest assembleDebug
```

The CI workflow uploads the debug APK as an artifact when the build succeeds.

## Android package visibility and installation

OpenClone requests `QUERY_ALL_PACKAGES` to provide its installed-app browser and `REQUEST_INSTALL_PACKAGES` to hand rebuilt APKs to Android's package installer. App stores may restrict these permissions to approved core use cases; distribution must follow the store and platform policies. Android displays its own installation confirmation, and Play Protect may warn about re-signed APKs.

## Safety and privacy

OpenClone is intended for legitimate personal use with apps the user is authorized to inspect or modify. It does not attempt to bypass DRM, licensing, account security, anti-cheat, or integrity protections. Respect app terms, copyright, and applicable law. APKs and generated builds stay on-device unless explicitly exported.

## License

GNU General Public License version 3 only (GPL-3.0-only). See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
