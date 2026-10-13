package com.echohome.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCheckerTest {
    @Test fun newerReleaseWithOrWithoutV() {
        assertTrue(Versions.isNewer("v0.3.0", "0.2.0"))
        assertTrue(Versions.isNewer("0.2.1", "v0.2.0"))
        assertTrue(Versions.isNewer("v1.0.0", "0.9.9"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(Versions.isNewer("v0.2.0", "0.2.0"))
        assertFalse(Versions.isNewer("v0.1.9", "0.2.0"))
    }

    @Test fun comparesNumbersNotText() {
        assertTrue(Versions.isNewer("v0.10.0", "0.9.0"))
        assertFalse(Versions.isNewer("v0.9.0", "0.10.0"))
    }

    @Test fun unknownFormatIsNeverNewer() {
        assertFalse(Versions.isNewer("v0.3.0-beta", "0.2.0"))
        assertFalse(Versions.isNewer("nightly", "0.2.0"))
        assertFalse(Versions.isNewer("v0.3.0", "dev"))
    }

    @Test fun parsesTagFromGitHubJson() {
        val json = """{"tag_name":"v0.3.0","name":"Echo Home 0.3.0","draft":false,"prerelease":false}"""
        assertEquals("v0.3.0", ReleaseClient.parseTag(json))
    }
}
