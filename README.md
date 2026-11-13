# OpenClone

OpenClone is an open-source Android app cloner and APK inspection tool.

## Working features

- Browse installed user apps and optionally system apps.
- Search installed apps by name or package ID.
- Inspect a single APK's package, version, SDK levels, and requested permissions.
- Run a best-effort compatibility preflight during APK inspection, highlighting declared Firebase/Cloud Messaging and Google services integration indicators, shared-user constraints, and Play Integrity/licensing indicators alongside general package/signature limitations.
- Import standalone APKs and split-APK archives in `.apks`, `.xapk`, `.apkm`, and ZIP formats, validate package/version consistency, and clone the complete APK set.
- Preserve `.obb` expansion files included in imported `.xapk` / ZIP bundles, rename package-bound OBB filenames, and include them under `Android/obb/<clone-package>/` in clone ZIP exports.
- Build a clone from an installed app's base APK and its installed split APKs.
- Change the clone's app label and package ID.
- Optionally mark the launcher icon with a coloured badge in one of four corners so the clone is easy to distinguish.
- Upload and preview a custom PNG launcher icon for installed-app clones that use direct raster PNG launcher resources.
- Run clone rebuilds as persistent WorkManager jobs, with progress reporting, user cancellation, and recovery of the active build after reopening OpenClone.
- Rewrite the compiled binary manifest and resource table, then re-sign every APK with a selected RSA signing identity encrypted at rest using Android Keystore.
- Create and select multiple signing identities, and import/export password-protected PKCS#12 backups.
- Verify each rebuilt APK signature before offering installation.
- Install the rebuilt APK set through Android's PackageInstaller confirmation flow.
- Export the rebuilt APK set as a ZIP archive.
- Browse OpenClone-created clones, see when an installed original app has a newer version, rebuild updates with the clone's matching saved signing identity, launch clones, and uninstall them with Android's system confirmation.

## Compatibility limitations

No cloner can guarantee that every Android app will work after cloning. OpenClone changes the binary manifest and resource table and performs a best-effort rewrite of package-name string references in every classes*.dex file. It deliberately preserves class/type descriptors and does not bypass app integrity checks. Apps that hard-code their original package name, check their signing certificate, use package-bound Google services, licensing, anti-cheat, DRM, or server-side registration may fail or refuse to run. Clones start with separate, empty app data; original app data and sign-ins are not copied.

Custom icon replacement supports direct raster PNG launcher resources and adaptive-icon XML whose foreground resolves directly to PNG resources. Adaptive icon backgrounds and masks are preserved; vector/XML foregrounds, nested layer-list foregrounds, and non-PNG raster resources retain their original artwork. The optional badge is applied only to supported adaptive-icon XML and PNG resources. DEX rewriting is experimental and limited to package-name string references with token-boundary checks; it does not rename classes, type descriptors, or arbitrary binary data. Some apps depend on package strings in formats the rewriter does not recognize, and others may break if they expect the original package identity. The build memory preflight estimates resource-table and DEX working-set needs, but is not a guarantee against every out-of-memory condition. OpenClone deliberately does not patch DEX integrity, signature, licensing, DRM, anti-cheat, or security checks. The compatibility preflight is heuristic: it checks declared permissions and manifest metadata only, cannot prove runtime compatibility, and may not detect integrations that are configured dynamically. External bundle import requires a complete base APK plus matching split APKs. OBB expansion files are preserved when included in imported `.xapk` / ZIP bundles and are included in exported ZIPs under `Android/obb/<clone-package>/`; Android does not allow OpenClone to silently install those files, so the user must place them with a file manager that has access to the Android/obb directory. OBB files belonging to already-installed source apps are not automatically discovered. The optional icon badge marks the existing launcher icon with a coloured dot in one of four selectable corners. Clone update detection compares the original app's installed version code with the version recorded at clone build time. Rebuilding an update requires the original app to remain installed and a matching saved signing identity to be available.

## Technology

- Kotlin and Jetpack Compose / Material 3
- Android minimum SDK 26 (Android 8.0)
- JDK 17, Android Gradle Plugin 8.9.1, Gradle 8.11.1
- Android apksig for APK signing and verification

## Build

Open the project in Android Studio and sync Gradle. CI runs unit tests, Android lint, and a debug APK build:

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

A separate Android emulator matrix runs instrumentation smoke tests on API 26 (minimum supported) and API 34. The CI workflow uploads the debug APK as an artifact when the build succeeds.

## Android package visibility and installation

OpenClone requests `QUERY_ALL_PACKAGES` to provide its installed-app browser, `REQUEST_INSTALL_PACKAGES` to hand rebuilt APKs to Android's package installer, and `REQUEST_DELETE_PACKAGES` for user-confirmed clone removal. App stores may restrict these permissions to approved core use cases; distribution must follow the store and platform policies. Android displays its own installation confirmation, and Play Protect may warn about re-signed APKs.

## Safety and privacy

OpenClone is intended for legitimate personal use with apps the user is authorized to inspect or modify. It does not attempt to bypass DRM, licensing, account security, anti-cheat, or integrity protections. Respect app terms, copyright, and applicable law. APKs and generated builds stay on-device unless explicitly exported.

## License

GNU General Public License version 3 only (GPL-3.0-only). See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
