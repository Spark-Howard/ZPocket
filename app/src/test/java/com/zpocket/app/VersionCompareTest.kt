package com.zpocket.app

import com.zpocket.app.ui.UpdateChecker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun parsesPlainAndVPrefixed() {
        assertArrayEquals(intArrayOf(1, 2, 0), UpdateChecker.parseVersion("1.2.0"))
        assertArrayEquals(intArrayOf(1, 2, 0), UpdateChecker.parseVersion("v1.2.0"))
        assertArrayEquals(intArrayOf(1, 2, 0), UpdateChecker.parseVersion("V1.2.0"))
        assertArrayEquals(intArrayOf(1, 2, 0, 1), UpdateChecker.parseVersion("1.2.0-1"))
    }

    @Test
    fun detectsNewerVersion() {
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.1.0"))
        assertTrue(UpdateChecker.isNewer("v1.1.0", "1.0.5"))
        assertTrue(UpdateChecker.isNewer("1.1.1", "1.1"))       // 缺省段按 0
        assertTrue(UpdateChecker.isNewer("2.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("10.0.0", "9.9.9"))    // 数值比较不是字符串比较
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("v1.1.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("1.1", "1.1.0"))      // 1.1 == 1.1.0
        assertFalse(UpdateChecker.isNewer("1.0.5", "1.1.0"))
    }
}
