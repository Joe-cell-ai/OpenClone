package org.openclone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InfraredProfileCodecTest {
    private fun sample() = SavedInfraredProfile(
        id = "profile-001",
        name = "Living room TV",
        brand = "Example",
        model = "TV-100",
        category = ApplianceCategory.TELEVISION,
        savedAtMillis = 1_700_000_000_000,
        commands = listOf(
            NamedInfraredCommand(
                "POWER",
                InfraredCommand.parse("38000", "9000,4500,560", null)
            ),
            NamedInfraredCommand(
                "VOLUME_UP",
                InfraredCommand.parse("38000", "4500,2250,560", null)
            )
        )
    )

    @Test fun roundTripsOfflineImportedProfilesAndCommandTimings() {
        val original = sample()
        val decoded = InfraredProfileCodec.decode(InfraredProfileCodec.encode(listOf(original)))
        assertEquals(1, decoded.size)
        assertEquals(original.id, decoded.single().id)
        assertEquals(original.brand, decoded.single().brand)
        assertEquals(original.model, decoded.single().model)
        assertEquals(original.category, decoded.single().category)
        assertEquals(original.commands.map { it.name }, decoded.single().commands.map { it.name })
        assertTrue(original.commands.zip(decoded.single().commands).all { (left, right) ->
            left.command.carrierHz == right.command.carrierHz &&
                left.command.timingsMicros.contentEquals(right.command.timingsMicros)
        })
    }

    @Test fun rejectsUnknownProfileSources() {
        val encoded = InfraredProfileCodec.encode(listOf(sample()))
            .replace("user-imported-flipper-ir", "unknown-source")
        assertTrue(runCatching { InfraredProfileCodec.decode(encoded) }.isFailure)
    }

    @Test fun rejectsDuplicateProfileIds() {
        val profile = sample()
        assertTrue(runCatching {
            InfraredProfileCodec.decode(InfraredProfileCodec.encode(listOf(profile, profile)))
        }.isFailure)
    }

    @Test fun rejectsOversizedProfilePayload() {
        assertTrue(runCatching {
            InfraredProfileCodec.decode(" ".repeat(InfraredProfileCodec.MAX_ENCODED_BYTES + 1))
        }.isFailure)
    }

    @Test fun newProfilesRequireBrandModelAndAtLeastOneCommand() {
        assertTrue(runCatching {
            InfraredProfileCodec.newProfile(
                "Remote", "", "TV-100", ApplianceCategory.TELEVISION, sample().commands
            )
        }.isFailure)
        assertTrue(runCatching {
            InfraredProfileCodec.newProfile(
                "Remote", "Example", "TV-100", ApplianceCategory.TELEVISION, emptyList()
            )
        }.isFailure)
    }
}
