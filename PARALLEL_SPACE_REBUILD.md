# OpenClone Parallel-Space Rebuild

## Product decision

Rebuild OpenClone around three user-facing areas only:

1. **Space** — add installed apps to isolated instances, launch them, manage instance state, and remove an instance without touching the original app.
2. **Remote Control** — retain the remote-control feature family as a first-class screen. Appliance control is capability-gated by the target device and available transport (Wi-Fi/LAN, Bluetooth LE, or an explicitly supported external IR/RF bridge); do not claim the Pixel has an IR emitter.
3. **Ad Block** — retain a user-enabled DNS filtering VPN with explicit Android VPN consent, visible status, allow/deny rules, blocklist updates, and clear limitations.

Remove unrelated product surfaces from the app navigation and, after dependency analysis, remove their implementation and dependencies. Do not delete user data or signing material as part of migration. Do not remove Remote Control or Ad Block.

## Cloning architecture

Use AppDualZuku (https://github.com/nathanatgit/AppDualZuku) as a technical reference for Shizuku/root-mediated Android workspace management, not as code to copy blindly.

The primary clone backend should manage Android users/profiles through an explicit privileged backend:
- Shizuku is the preferred non-root execution path, with clear setup and permission state.
- Root is an optional advanced backend only when detected and explicitly enabled.
- Create/manage a supported Android secondary user or managed profile, install the selected original package into that space, enumerate installed apps there, and launch/uninstall only after confirmation.
- Keep original-user packages and data untouched.
- Persist workspace IDs and per-app instance metadata; reconcile against Android's actual user/package state after process death and reboot.
- Use bounded command execution, structured exit/error parsing, cancellation, and progress. Never leave a permanent spinner on a failed command.
- If profile creation is blocked by OS policy or ROM limits, show the exact restriction and a supported recovery path. Never silently bypass policy restrictions.

Do not promise Parallel Space's proprietary in-process virtualization behavior. Android's public APIs do not give an ordinary third-party app a universal way to host arbitrary APKs inside its own process. A real Android user/profile provides OS-level data separation, but launch/switch behavior and background notifications vary by Android build and profile type. Verify these behaviors on Pixel 10a / GrapheneOS before claiming parity.

The existing APK-rewrite engine must not be the default clone path. Retain it only as an explicitly labelled optional compatibility fallback if it remains useful after tests; do not silently mutate packages, signatures, or app integrity/security checks.

## UI direction

Use a simple Parallel Space-inspired layout without copying its branding or assets:
- Home/Space: app grid, Add apps, instance badges, launch, long-press management.
- Remote: supported device discovery/pairing and remote controls.
- Ad Block: one clear on/off state, VPN permission, blocklist status, allowlist and diagnostics.
- Settings: only permissions, execution mode, privacy, and essential app information.

No Extensions Store, APK inspector, signing identity manager, clone update dashboard, proxy catalogue, backup tools, or unrelated developer utilities in the primary product UI.

## Delivery gates

1. Inspect current main and all existing feature dependencies before removing anything.
2. Implement the workspace backend and test its command/state machine independently.
3. Add UI only when the underlying action works and reports real system state.
4. Preserve and harden Remote Control and Ad Block; no placeholder screens.
5. Run unit tests, lint, debug APK build, and available emulator tests.
6. Test workspace creation, app install-existing, launch, removal, reboot recovery, and permission denial on a real Pixel 10a / GrapheneOS device before claiming device compatibility.
7. Work on `rebuild/parallel-space-core`; open a reviewable PR after the first complete, buildable milestone. Never merge to main without the user's review.

## Known platform constraints

- Shizuku permission and its running service are required for the non-root shell path.
- Android may block adding users/profiles due to device policy, ROM limits, or system configuration.
- Profile isolation is not the same as Parallel Space's proprietary virtualization engine; some apps and notification flows may behave differently.
- Ad Block uses Android's single active VPN slot per user/profile and DNS filtering cannot reliably block embedded DoH or server-side ads.
- Remote-control compatibility is model/protocol-specific and requires explicit pairing and verified device identity.
