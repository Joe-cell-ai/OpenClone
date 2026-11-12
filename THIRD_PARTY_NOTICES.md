# Third-party notices

## APK Toolbox cloning components

OpenClone's APK rebuilding components are adapted from [APK Toolbox](https://github.com/bl3xand/APK-Toolbox), by the GitHub user **bl3xand**, at upstream commit `2f799c2e4bb37a8ea1fcf58ddf44cab991876e6e`.

Adapted files:
- `ManifestPatcher.kt`
- `ResourceTableRenamer.kt`
- `ApkRebuilder.kt`
- `SigningKey.kt`
- `SigningKeys.kt`
- `IconBadger.kt`

These components are distributed under **GNU General Public License version 3 only (GPL-3.0-only)**. Their source headers identify the upstream project. The full license is included in [LICENSE](LICENSE).

OpenClone's own code in this repository is also distributed under GPL-3.0-only. No code from the separate App Cloner repository was copied.

## ARSCLib test dependency

[ARSCLib](https://github.com/REAndroid/ARSCLib) by REAndroid is used only by the JVM unit tests to create binary Android XML fixtures. It is not packaged into the application. ARSCLib is licensed under Apache License 2.0.
