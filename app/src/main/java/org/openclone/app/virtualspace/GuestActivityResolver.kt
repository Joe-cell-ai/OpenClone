package org.openclone.app.virtualspace

/**
 * Resolves an explicit guest activity component against the manifest inventory.
 *
 * This is the routing boundary for a future host proxy Activity. It does not instantiate
 * guest code or bypass Android component/exported rules.
 */
data class ResolvedGuestActivity(
    val instanceId: String,
    val packageName: String,
    val className: String,
    val exported: Boolean
)

class GuestActivityResolver {
    fun resolveExplicit(
        instance: VirtualAppInstance,
        targetPackage: String?,
        targetClass: String?,
        callerPackage: String?
    ): ResolvedGuestActivity? {
        if (targetPackage != instance.originalPackage || targetClass.isNullOrBlank()) return null

        val className = canonicalClassName(instance.originalPackage, targetClass)
            ?: return null
        val declaration = instance.components.firstOrNull {
            it.kind == GuestComponentDeclaration.Kind.ACTIVITY &&
                it.className == className &&
                it.enabled
        } ?: return null

        // Private activities are reachable only from within the same guest package.
        if (callerPackage != instance.originalPackage && !declaration.exported) return null

        return ResolvedGuestActivity(
            instanceId = instance.id,
            packageName = instance.originalPackage,
            className = declaration.className,
            exported = declaration.exported
        )
    }

    private fun canonicalClassName(packageName: String, requested: String): String? {
        val candidate = when {
            requested.startsWith(".") -> packageName + requested
            '.' !in requested -> "$packageName.$requested"
            else -> requested
        }
        if (!candidate.startsWith("$packageName.")) return null
        if (!CLASS_NAME.matches(candidate)) return null
        return candidate
    }

    private companion object {
        val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    }
}
