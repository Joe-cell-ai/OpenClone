package org.openclone.app.virtualspace

import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo

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
                add(provider.toDeclaration().copy(authority = provider.authority))
            }
        }.distinctBy { it.kind to it.className }
    }
}

private fun ActivityInfo.toDeclaration(kind: GuestComponentDeclaration.Kind) =
    GuestComponentDeclaration(
        className = name,
        kind = kind,
        exported = exported,
        enabled = enabled,
        permission = permission,
        processName = processName
    )

private fun ServiceInfo.toDeclaration(kind: GuestComponentDeclaration.Kind) =
    GuestComponentDeclaration(
        className = name,
        kind = kind,
        exported = exported,
        enabled = enabled,
        permission = permission,
        processName = processName
    )

private fun ProviderInfo.toDeclaration() =
    GuestComponentDeclaration(
        className = name,
        kind = GuestComponentDeclaration.Kind.PROVIDER,
        exported = exported,
        enabled = enabled,
        permission = readPermission ?: writePermission,
        processName = processName,
        authority = authority
    )
