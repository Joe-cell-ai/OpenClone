package org.openclone.app

import org.junit.Assert.*
import org.junit.Test

class UniversalRemoteModelsTest {
    private fun profile(
        transports: Set<RemoteTransport> = setOf(RemoteTransport.INFRARED),
        commands: List<RemoteCommandMapping> = listOf(
            RemoteCommandMapping(RemoteAction.POWER, RemoteTransport.INFRARED, "power")
        )
    ) = RemoteDeviceProfile("samsung-tv-001", "Example", "Model 1", ApplianceCategory.TELEVISION, transports, commands)

    @Test fun catalogueIncludesMajorApplianceCategories() {
        assertTrue(UniversalRemoteCatalogue.categories.containsAll(listOf(
            ApplianceCategory.TELEVISION, ApplianceCategory.AIR_CONDITIONER,
            ApplianceCategory.REFRIGERATOR, ApplianceCategory.FAN,
            ApplianceCategory.WASHING_MACHINE, ApplianceCategory.PROJECTOR
        )))
    }

    @Test fun infraredTaxonomyIncludesCommonLegacyAppliances() {
        val supported = UniversalRemoteCatalogue.categoriesFor(RemoteTransport.INFRARED)
        assertTrue(ApplianceCategory.TELEVISION in supported)
        assertTrue(ApplianceCategory.AIR_CONDITIONER in supported)
        assertTrue(ApplianceCategory.FAN in supported)
        assertFalse(ApplianceCategory.REFRIGERATOR in supported)
    }

    @Test fun smartTransportsCanRepresentRefrigerators() {
        assertTrue(UniversalRemoteCatalogue.canAttempt(ApplianceCategory.REFRIGERATOR, RemoteTransport.WIFI_LAN))
        assertTrue(UniversalRemoteCatalogue.canAttempt(ApplianceCategory.REFRIGERATOR, RemoteTransport.MATTER))
    }

    @Test fun profileResolvesPreferredTransportMapping() {
        val wifi = RemoteCommandMapping(RemoteAction.POWER, RemoteTransport.WIFI_LAN, "device.power")
        val item = profile(setOf(RemoteTransport.INFRARED, RemoteTransport.WIFI_LAN), listOf(
            RemoteCommandMapping(RemoteAction.POWER, RemoteTransport.INFRARED, "ir.power"), wifi
        ))
        assertEquals(wifi, item.mappingFor(RemoteAction.POWER, listOf(RemoteTransport.WIFI_LAN, RemoteTransport.INFRARED)))
    }

    @Test fun profileDoesNotInventActionsForTransport() {
        val item = profile()
        assertEquals(setOf(RemoteAction.POWER), item.actionsFor(RemoteTransport.INFRARED))
        assertTrue(item.actionsFor(RemoteTransport.WIFI_LAN).isEmpty())
    }

    @Test fun rejectsCommandMappingForUndeclaredTransport() {
        try {
            profile(commands = listOf(RemoteCommandMapping(RemoteAction.POWER, RemoteTransport.WIFI_LAN, "power")))
            fail("Expected invalid mapping to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsDuplicateActionAndTransportMappings() {
        try {
            profile(commands = listOf(
                RemoteCommandMapping(RemoteAction.POWER, RemoteTransport.INFRARED, "power1"),
                RemoteCommandMapping(RemoteAction.POWER, RemoteTransport.INFRARED, "power2")
            ))
            fail("Expected duplicate mappings to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun sceneIsBoundedAndContainsOnlyProfileActionReferences() {
        val scene = RemoteScene("movie-mode", "Movie Mode", listOf(
            RemoteSceneStep("living-room-tv", RemoteAction.POWER),
            RemoteSceneStep("living-room-tv", RemoteAction.INPUT)
        ))
        assertEquals(2, scene.steps.size)
        try {
            RemoteScene("empty", "Empty", emptyList())
            fail("Expected empty scene to be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
