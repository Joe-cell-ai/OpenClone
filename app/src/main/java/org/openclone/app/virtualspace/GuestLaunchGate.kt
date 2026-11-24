package org.openclone.app.virtualspace

/**
 * Result of requesting guest execution.
 *
 * A stored APK is not an installed Android package and cannot be launched through
 * PackageManager. Until the host-process component runtime is implemented, callers
 * must surface this result instead of showing a successful launch or an endless spinner.
 */
sealed interface GuestLaunchResult {
    data class RuntimeUnavailable(
        val instanceId: String,
        val message: String = "This app is stored in OpenClone, but guest execution is not available yet."
    ) : GuestLaunchResult

    data class InstanceMissing(val instanceId: String) : GuestLaunchResult
}

/** Central launch gate. It deliberately never reports success without a real runtime. */
class GuestLaunchGate {
    fun requestLaunch(instanceId: String, instanceExists: Boolean): GuestLaunchResult =
        if (!instanceExists) {
            GuestLaunchResult.InstanceMissing(instanceId)
        } else {
            GuestLaunchResult.RuntimeUnavailable(instanceId)
        }
}
