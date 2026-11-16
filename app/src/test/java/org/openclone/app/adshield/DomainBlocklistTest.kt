package org.openclone.app.adshield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBlocklistTest {
    @Test fun parsesPlainAndHostsFileEntries() {
        val policy = DomainBlocklist.fromLines(
            listOf("ads.example", "0.0.0.0 tracker.example # tracker", "127.0.0.1 metrics.example")
        )
        assertEquals(3, policy.blockedDomainCount)
        assertTrue(policy.isBlocked("ads.example"))
        assertTrue(policy.isBlocked("sub.tracker.example"))
        assertTrue(policy.isBlocked("metrics.example"))
    }

    @Test fun allowlistOverridesParentBlockRule() {
        val policy = DomainBlocklist.fromLines(
            blocklistLines = listOf("0.0.0.0 example.com"),
            allowlistLines = listOf("safe.example.com")
        )
        assertTrue(policy.isBlocked("ads.example.com"))
        assertFalse(policy.isBlocked("safe.example.com"))
        assertFalse(policy.isBlocked("sub.safe.example.com"))
    }

    @Test fun normalizationHandlesCaseTrailingDotAndIdn() {
        val policy = DomainBlocklist.fromLines(listOf("BÜCHER.example."))
        assertTrue(policy.isBlocked("xn--bcher-kva.example"))
        assertTrue(policy.isBlocked("BÜCHER.EXAMPLE."))
    }

    @Test fun invalidAndLocalHostnamesAreIgnored() {
        val policy = DomainBlocklist.fromLines(listOf("", "# comment", "localhost", "bad host", "a..b"))
        assertEquals(0, policy.blockedDomainCount)
        assertFalse(policy.isBlocked("localhost"))
    }

    @Test fun suffixMatchingDoesNotMatchLookalikeDomain() {
        val policy = DomainBlocklist.fromLines(listOf("example.com"))
        assertFalse(policy.isBlocked("notexample.com"))
        assertTrue(policy.isBlocked("cdn.example.com"))
    }
}
