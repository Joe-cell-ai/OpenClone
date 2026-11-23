package org.openclone.app.clone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LayerListIconTraversalTest {

    @Test
    fun nestedLayerListsResolveTheFirstSupportedLeafInResourceOrder() {
        val graph = mapOf(
            1 to node(1, "res/drawable/launcher.xml", "layer-list", true, listOf(2, 3)),
            2 to node(2, "res/drawable/nested.xml", "inset", true, listOf(4)),
            3 to node(3, "res/drawable/overlay.png"),
            4 to node(4, "res/drawable-nodpi/primary.png")
        )

        val result = LayerListIconTraversal.findFirstReplaceableLeaf(1, graph::get)

        assertNotNull(result)
        assertEquals(4, result?.resourceId)
        assertEquals("res/drawable-nodpi/primary.png", result?.path)
    }

    @Test
    fun unsupportedXmlAndMissingReferencesAreSkippedBeforeAVectorLeaf() {
        val graph = mapOf(
            10 to node(10, "res/drawable/launcher.xml", "layer-list", true, listOf(11, 404, 12)),
            11 to node(11, "res/drawable/background.xml", "shape"),
            12 to node(12, "res/drawable/foreground.xml", "vector")
        )

        val result = LayerListIconTraversal.findFirstReplaceableLeaf(10, graph::get)

        assertNotNull(result)
        assertEquals(12, result?.resourceId)
    }

    @Test
    fun cyclesDoNotPreventLaterSupportedLayersFromBeingFound() {
        val graph = mapOf(
            20 to node(20, "res/drawable/root.xml", "layer-list", true, listOf(21, 23)),
            21 to node(21, "res/drawable/nested.xml", "layer-list", true, listOf(20, 22)),
            22 to node(22, "res/drawable/unused.webp"),
            23 to node(23, "res/drawable/primary.png")
        )

        val result = LayerListIconTraversal.findFirstReplaceableLeaf(20, graph::get)

        assertNotNull(result)
        assertEquals(23, result?.resourceId)
    }

    @Test
    fun traversalStopsAtTheDepthLimitAndContinuesWithTheNextRootLayer() {
        val graph = mutableMapOf<Int, LayerListDrawableResource>()
        for (id in 1..10) {
            graph[id] = node(
                id,
                "res/drawable/layer$id.xml",
                "layer-list",
                true,
                if (id == 1) listOf(2, 99) else listOf(id + 1)
            )
        }
        graph[99] = node(99, "res/drawable/fallback.png")

        val result = LayerListIconTraversal.findFirstReplaceableLeaf(1, graph::get)

        assertNotNull(result)
        assertEquals(99, result?.resourceId)
    }

    @Test
    fun traversalInspectsNoMoreThanTheConfiguredNodeBudget() {
        var inspected = 0
        val children = (2..65).toList()
        val result = LayerListIconTraversal.findFirstReplaceableLeaf(1) { id ->
            inspected++
            when (id) {
                1 -> node(1, "res/drawable/root.xml", "layer-list", true, children)
                65 -> node(65, "res/drawable/last.png")
                else -> node(id, "res/drawable/shape$id.xml", "shape")
            }
        }

        assertNull(result)
        assertEquals(LayerListIconTraversal.MAX_NODES, inspected)
    }

    private fun node(
        id: Int,
        path: String,
        rootName: String? = null,
        containsLayerList: Boolean = false,
        children: List<Int> = emptyList()
    ) = LayerListDrawableResource(id, path, rootName, containsLayerList, children)
}
