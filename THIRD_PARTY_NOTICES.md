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

## ARSCLib dependency

[ARSCLib](https://github.com/REAndroid/ARSCLib) by REAndroid is used at runtime to generate adaptive-icon XML for the optional clone badge and by JVM unit tests to create binary Android XML fixtures. OpenClone packages a stripped jar without ARSCLib's duplicate Android framework and XML-pull stubs. ARSCLib is licensed under Apache License 2.0.

## dexlib2

[dexlib2](https://github.com/JesusFreke/smali) version 2.5.2 is used for parsing and rewriting DEX string references. It is distributed under the BSD 3-Clause license. See the upstream repository for the full license text.


## tun2proxy v0.8.7

OpenClone packages the Android shared-library builds from [tun2proxy](https://github.com/tun2proxy/tun2proxy), version v0.8.7, for the per-clone VPN proxy engine.

- License: MIT
- Release: https://github.com/tun2proxy/tun2proxy/releases/tag/v0.8.7
- Android archive SHA-256: `e85d311ade195fe90d6b018585bb86f2f0d8c5ec61f52603b016a6a5d78c6cc4`

The build downloads the pinned Android archive and verifies this digest before extracting the Android ABI shared libraries. The archive is not copied into the source repository.

MIT License

Copyright (c) @ssrlive, B. Blechschmidt and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
