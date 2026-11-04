package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationLayoutTest {
    @Test fun topLevelGroupsAreShortAndHaveNoDuplicateTargets() {
        val groups = SettingsMenuLayout.groups
        assertEquals(6, groups.size)
        val all = groups.flatMap { it.destinations }
        assertEquals(all.size, all.distinct().size)
        assertTrue(groups.all { it.destinations.isNotEmpty() })
    }

    @Test fun connectionAndDeviceSettingsRemainReachable() {
        val top = SettingsMenuLayout.groups.flatMap { it.destinations }.toSet()
        assertTrue(SettingsDestination.PAIRING in top)
        assertTrue(SettingsDestination.PRIVATE in top)
        assertTrue(SettingsDestination.PUBLIC in top)
        assertTrue(SettingsDestination.BACKGROUND in top)
        assertTrue(SettingsDestination.WEARABLE_ALERTS in top)
        assertTrue(SettingsDestination.WEARABLE_HISTORY in top)
        assertTrue(SettingsDestination.WEARABLE_TECH in top)
        assertTrue(SettingsDestination.SELF_CHECK in top)
        assertTrue(SettingsMenuLayout.search("标准蓝牙").contains(SettingsDestination.WEARABLE_TECH))
        assertTrue(SettingsMenuLayout.search("心率").contains(SettingsDestination.WEARABLE_ALERTS))
        assertTrue(SettingsMenuLayout.search("UDP").contains(SettingsDestination.PUBLIC))
        assertTrue(SettingsMenuLayout.search("关于软件").contains(SettingsDestination.ABOUT))
    }

    @Test fun networkGroupOpensItsDestinationsDirectlyWithoutThirdLevelPage() {
        val networkGroup = SettingsMenuLayout.groups.single { it.title == "网络" }
        assertEquals(listOf(SettingsDestination.PRIVATE, SettingsDestination.PUBLIC,
            SettingsDestination.BACKGROUND), networkGroup.destinations)
        assertEquals(3, networkGroup.destinations.distinct().size)
        assertFalse(SettingsDestination.entries.any { it.name == "NETWORK" })
        assertTrue(SettingsMenuLayout.search("私人中继").contains(SettingsDestination.PRIVATE))
        assertTrue(SettingsMenuLayout.search("公共节点").contains(SettingsDestination.PUBLIC))
    }
}
