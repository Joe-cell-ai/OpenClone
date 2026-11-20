# Virtual runtime engine selection

Status: blocked pending a redistributable engine with verified Android 17 support.

## Why the APK rebuild path is being retired

The existing clone pipeline rewrites APK contents and signs the result. It is not an application virtualization runtime. The existing `VirtualSpaceStore` can retain an APK set and create a private data directory, but `VirtualSpaceManager.launch` explicitly reports that guest execution is unavailable. Repeated changes to APK rebuild progress cannot make this architecture behave like Parallel Space.

## Candidate audit

### FBlackBox/BlackBox

- The upstream README describes an in-process Android virtual engine.
- The GitHub repository currently has no declared licence and no root `LICENSE` file.
- Its repository metadata reports the latest pushed source date as 2024-04-12.
- Decision: do not copy, vendor, or link its source until the copyright holder provides a verifiable licence grant and the code can be audited for Android 17.

### asLody/VirtualApp

- The upstream project describes a virtual engine, but its current public repository has no declared licence and no root `LICENSE` file.
- Its README distinguishes the public source from newer commercial support.
- Decision: do not copy, vendor, or link its source until licensing and the exact Android 17 support boundary are verified.

### Android Virtualization Framework

Android's Virtualization Framework provides virtual machines and strong isolation, but it is not a drop-in in-process app-cloning engine. It does not by itself provide the Android framework compatibility layer needed to host arbitrary APK activities, services, providers, broadcasts, and package-manager behavior inside OpenClone.

## Architecture requirements

The runtime selected for OpenClone must provide, or permit us to implement and test:

1. Virtual package registry and APK/split-APK installation without rewriting the guest APK.
2. Guest process and activity lifecycle hosting.
3. Per-instance application data and cache isolation.
4. Virtual package manager, resources, class loading, and native library handling.
5. Service, provider, receiver, permission, and intent compatibility.
6. Explicit compatibility reporting for Android versions and device profiles.

## Acceptance gates

- Legal: source and binary redistribution rights are documented before dependency integration.
- Build: clean Android build and unit tests pass; dependency versions are pinned.
- Functional: a purpose-built harmless test APK imports and launches inside the host without being installed as a normal package.
- Isolation: two instances demonstrate independent files, preferences, and databases.
- Compatibility: Android 17 device test results are recorded; unsupported APIs fail visibly rather than pretending to work.
- Safety: no root, Shizuku, signature spoofing, SELinux bypass, or security/integrity bypass.

## Delivery sequence

1. Complete upstream licence and API compatibility audit.
2. Select or implement a legally usable runtime and isolate it behind an OpenClone adapter.
3. Deliver a minimal launch proof-of-concept with isolated data.
4. Expand component compatibility and test progressively.
5. Retire the APK rewrite workflow from the normal clone path only after the virtual runtime passes the functional gates.

No APK should be presented as a cloning fix until the functional and isolation gates pass.
