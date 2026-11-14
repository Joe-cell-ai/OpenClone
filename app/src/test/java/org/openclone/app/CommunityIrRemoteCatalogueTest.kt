package org.openclone.app

import org.junit.Assert.fail
import org.junit.Test

class CommunityIrRemoteCatalogueTest {
    @Test fun rejectsTraversalBeforeAnyNetworkRequest() {
        assertRejected("../escape.ir")
        assertRejected("TVs/../escape.ir")
    }

    @Test fun rejectsNonIrDownloads() {
        assertRejected("TVs/Sony/remote.txt")
    }

    @Test fun rejectsIncompleteProfilePaths() {
        assertRejected("remote.ir")
        assertRejected("TVs")
    }

    private fun assertRejected(path: String) {
        try {
            CommunityIrdbRepository.download(path)
            fail("Expected unsafe or unsupported catalogue path to be rejected: $path")
        } catch (_: IllegalArgumentException) {
            // Expected: validation occurs before opening a network connection.
        }
    }
}
