# OpenClone Parallel-Space Rebuild

## Product decision

Rebuild OpenClone as a non-root, Parallel Space-style app virtualization product. The user explicitly chose the same *type of approach* used by Parallel Space (its vendor-described MultiDroid application virtualization), and explicitly rejected root-related functionality.

The product has only three user-facing areas:

1. **Space** — add installed apps to isolated virtual instances, launch them, manage instance state, and remove a virtual instance without touching the original app.
2. **Remote Control** — retain remote-control features for supported appliances and transports (Wi-Fi/LAN, Bluetooth LE, or an explicitly supported external IR/RF bridge). Do not claim the Pixel 10a has an IR emitter.
3. **Ad Block** — retain a user-enabled DNS filtering VPN with Android VPN consent, visible status, allow/deny rules, blocklist updates, and clear limitations.

Remove root-related features and unrelated product surfaces. Do not delete user data or signing material during migration. Do not remove Remote Control or Ad Block.

## Cloning architecture — non-root app virtualization

Parallel Space publicly describes its engine as MultiDroid, an application-virtualization engine. Its implementation is proprietary; do not claim access to or reproduce its private source. Build an original, maintainable non-root virtualization layer that follows the same broad app-container model.

Required architecture:
- Run cloned application components within OpenClone's host process using a dedicated virtualization/runtime layer; maintain a separate virtual package registry, per-clone application identity, virtual data directory, and component/task routing.
- Intercept and virtualize Android framework/service interactions needed by guest apps (package/application context, activities, services, providers, broadcasts, resources, files, and app-private preferences/databases) through explicit, testable adapters. Keep host and guest state separated.
- Install/import the selected APK into the virtual container; do not install a second package into an Android secondary user/profile as the primary cloning strategy.
- Preserve the original installed application and its data. A clone uninstall must remove only that clone's virtual data.
- Support ABI/64-bit APK handling and dependency/resource loading only where the runtime can do so safely and correctly.
- Use a capability/compatibility check before adding an app. Show unsupported reasons rather than hanging or pretending a clone works.
- Persist clone metadata and recover cleanly after process death/reboot. Provide bounded operations, structured errors, progress, cancellation, and useful diagnostics.
- Keep the design non-root: no `su`, root detection, root permission prompts, root shell, root-only backend, or root-dependent code paths. Do not use Shizuku as the primary cloning mechanism.
- Do not bypass Android signature verification, SELinux, secure-environment requirements, app licensing, or other platform security boundaries. Do not promise support for apps that explicitly require a secure environment or rely on unsupported native/runtime behavior.

Important engineering reality:
- Parallel Space's MultiDroid implementation is proprietary. A compatible, broad Android app virtualization engine is a substantial runtime project, not a UI-only change or a simple APK rename/re-sign operation.
- Android does not expose a public API that lets an ordinary app universally host arbitrary third-party APKs in its process. Implement only well-understood framework virtualization hooks, document unsupported APIs, and expand compatibility based on real tests.
- Some apps (especially banking/payment apps, apps with strict integrity/secure-environment checks, apps with complex native code, and apps that depend on privileged Google services) may refuse to run or may be unsupported. Never bypass those checks.
- Do not fall back silently to Android users/profiles, Shizuku, root, or APK package rewriting. Any future alternative must be explicitly presented as a separate mode and approved by the user.

## UI direction

Use a simple Parallel Space-inspired layout without copying its branding or assets:
- Home/Space: app grid, Add apps, clone badges, launch, long-press management.
- Remote: supported device discovery/pairing and remote controls.
- Ad Block: one clear on/off state, VPN permission, blocklist status, allowlist and diagnostics.
- Settings: only permissions, privacy, and essential app information.

No root settings, Shizuku setup, Extensions Store, APK inspector, signing identity manager, clone update dashboard, proxy catalogue, backup tools, or unrelated developer utilities in the primary product UI.

## Delivery gates

1. Audit current main and all existing feature dependencies. Identify every root/Shizuku reference, command, permission, UI entry, and dependency.
2. Remove root/Shizuku execution paths and root-only UI/dependencies from the rebuild branch. Verify there are no residual root commands or permission prompts.
3. Inspect existing APK import/clone code and design a small, isolated virtualization runtime with a documented compatibility matrix before wiring the UI.
4. Implement and test the runtime in stages: virtual package registry and storage isolation first; then activity/task routing; then services, providers, broadcasts, and framework adapters. Do not label a stage as working until guest apps actually launch and pass tests.
5. Preserve and harden Remote Control and Ad Block; no placeholder screens.
6. Run unit tests, lint, debug APK build, and available emulator/device tests. Test multiple APK architectures and representative simple apps before adding complex apps.
7. Work on `rebuild/parallel-space-core`; open a reviewable PR after a complete, buildable milestone. Never merge to main without the user's review.

## Acceptance criteria

- No root or Shizuku code, settings, prompts, dependencies, or shell commands remain in the product.
- The original app remains installed and its data is unchanged after adding, launching, and removing a clone.
- A supported clone launches inside the virtual runtime with separate app data and can be stopped/reopened.
- Unsupported apps receive a precise compatibility error; no endless spinner or false success state.
- Remote Control and Ad Block remain accessible and functional.
- Build, lint, and tests pass before a PR is offered.
