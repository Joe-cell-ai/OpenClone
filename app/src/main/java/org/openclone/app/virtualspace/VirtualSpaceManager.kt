package org.openclone.app.virtualspace

import android.content.Context
import java.io.File

enum class GuestExecutionState {
    STORED,
    RUNTIME_NOT_AVAILABLE
}

data class VirtualSpaceEntry(
    val instance: VirtualAppInstance,
    val descriptor: VirtualApkDescriptor,
    val executionState: GuestExecutionState
)

/**
 * Coordinates APK validation and durable virtual-instance creation.
 *
 * Launch remains deliberately unavailable until activity/service/provider virtualization exists.
 * This prevents the UI from reporting a clone as runnable when it is only stored.
 */
class VirtualSpaceManager(context: Context) {
    private val appContext = context.applicationContext
    private val store = VirtualSpaceStore(File(appContext.filesDir, "virtual-space"))
    private val inspector = VirtualApkInspector(appContext)

    fun addApkSet(apkFiles: List<File>): VirtualSpaceEntry {
        val descriptor = inspector.inspect(apkFiles)
        require(descriptor.containsCode) {
            "This package contains no executable application code."
        }
        val instance = store.createInstance(
            originalPackage = descriptor.packageName,
            label = descriptor.label,
            sourceApks = apkFiles,
            components = descriptor.components
        )
        return VirtualSpaceEntry(instance, descriptor, GuestExecutionState.RUNTIME_NOT_AVAILABLE)
    }

    fun list(): List<VirtualAppInstance> = store.listInstances()

    fun remove(instanceId: String): Boolean = store.deleteInstance(instanceId)

    fun executionReadiness(instanceId: String): GuestExecutionState {
        requireNotNull(store.findInstance(instanceId)) { "Virtual app instance was not found." }
        return GuestExecutionState.RUNTIME_NOT_AVAILABLE
    }

    fun launch(instanceId: String): Nothing {
        requireNotNull(store.findInstance(instanceId)) { "Virtual app instance was not found." }
        throw UnsupportedOperationException(
            "The guest execution runtime is not implemented yet. The APK is safely stored, but it cannot launch inside OpenClone."
        )
    }
}
