package org.openclone.app.virtualspace

import android.content.pm.PackageInfo
import android.content.pm.ComponentInfo

/**
 * A manifest-declared guest component. This is inventory metadata for the virtual runtime;
 * it does not imply that Android has registered the component in the host process.
 */
data class GuestComponentDeclaration(
    val className: String,
    val kind: Kind,
    val exported: Boolean,
    val enabled: Boolean,
    val permission: String?,
    val processName: String?,
    val authority: String? = null
) {
    enum class Kind { ACTIVITY, RECEIVER, SERVICE, PROVIDER }

    companion object {
        fun from(packageInfo: PackageInfo): List<GuestComponentDeclaration> = buildList {
            packageInfo.activities.orEmpty().forEach { add(it.toDeclaration(Kind.ACTIVITY)) }
            packageInfo.receivers.orEmpty().forEach { add(it.toDeclaration(Kind.RECEIVER)) }
            packageInfo.services.orEmpty().forEach { add(it.toDeclaration(Kind.SERVICE)) }
            packageInfo.providers.orEmpty().forEach { provider ->
                add(provider.toDeclaration(Kind.PROVIDER).copy(authority = provider.authority))
            }
        }.distinctBy { it.kind to it.className }
    }
}

private fun ComponentInfo.toDeclaration(kind: GuestComponentDeclaration.Kind) =
    GuestComponentDeclaration(
        className = name,
        kind = kind,
        exported = exported,
        enabled = enabled,
        permission = permission,
        processName = processName
    )
