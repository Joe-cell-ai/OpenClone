package org.openclone.app.clone

/**
 * One file-backed drawable resource discovered while walking a layer-list graph.
 *
 * [containsLayerList] is true when the XML contains at least one layer-list node. References are
 * collected only from those nodes, never from selector state branches or unrelated XML wrappers.
 */
internal data class LayerListDrawableResource(
    val resourceId: Int,
    val path: String,
    val rootName: String?,
    val containsLayerList: Boolean,
    val childResourceIds: List<Int>
)

/**
 * Bounded, cycle-safe traversal for custom launcher-icon replacement.
 *
 * It selects only the first PNG or VectorDrawable leaf in resource order. This preserves later
 * overlay layers and avoids flattening arbitrary drawables or changing state-specific artwork.
 */
internal object LayerListIconTraversal {
    const val MAX_DEPTH = 8
    const val MAX_NODES = 64

    fun findFirstReplaceableLeaf(
        rootResourceId: Int,
        inspect: (Int) -> LayerListDrawableResource?
    ): LayerListDrawableResource? {
        val visited = HashSet<Int>()

        fun visit(resourceId: Int, depth: Int): LayerListDrawableResource? {
            if (depth > MAX_DEPTH || visited.size >= MAX_NODES || !visited.add(resourceId)) return null
            val node = runCatching { inspect(resourceId) }.getOrNull() ?: return null
            if (node.resourceId != resourceId) return null

            if (node.containsLayerList) {
                for (childResourceId in node.childResourceIds) {
                    val leaf = visit(childResourceId, depth + 1)
                    if (leaf != null) return leaf
                }
                return null
            }

            return when (IconResourceKind.fromPath(node.path)) {
                IconResourceKind.PNG -> node
                IconResourceKind.XML -> node.takeIf { it.rootName == "vector" }
                IconResourceKind.OTHER -> null
            }
        }

        return visit(rootResourceId, 0)
    }
}
