# OpenClone

OpenClone is an open-source Android project for inspecting installed applications and exploring how independent app copies can be created.

## Current implementation
- Browse installed user applications and optionally system applications.
- Search by app label or package name.
- View package name, version, and APK component count.
- Export an installed app's base APK and available split APK files as a ZIP archive.
- Import one APK file and inspect its label, package ID, version, SDK levels, and requested permission count.

## Planned cloning workflow
- Inspect APKs and split APK sets.
- Customize clone display name, icon, and application ID where technically feasible.
- Rebuild and sign modified APKs for user-controlled installation.
- Track generated builds and provide compatibility diagnostics.

**Cloning status:** APK transformation, package-ID rewriting, signing pipeline, and clone installation are not implemented yet. Exporting an APK set is a backup/export feature, not cloning.

## Technology
- Kotlin, Jetpack Compose, Material 3
- Minimum Android SDK 26 (Android 8.0)
- JDK 17, Android Gradle Plugin 8.9.1, Gradle 8.11.1

## Build
Open in Android Studio and sync Gradle. CI builds with `gradle assembleDebug`.

## Android package visibility
The app requests `QUERY_ALL_PACKAGES` for its core installed-app browser. Google Play restricts this permission to approved core use cases; alternative distribution must still follow platform policies.

## Safety and privacy
OpenClone is for legitimate personal use with apps the user is authorized to inspect or modify. It will not bypass DRM, licensing, account security, anti-cheat, or app-integrity protections. Respect app terms, copyright, and applicable law. Files stay on-device unless explicitly exported.

## License
GNU GPL version 3 or later. See [LICENSE](LICENSE) and the [official text](https://www.gnu.org/licenses/gpl-3.0.html).
