package io.github.tigaotg.openkinguard.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeDevicePolicyTest {
    @Test fun twoRolesOnOnePhoneProduceOneCard() {
        val merged = HomeDevicePolicy.collapse(listOf(
            HomeRolePresence("phone-a", "as-helper", "HELPER", true),
            HomeRolePresence("phone-a", "as-subject", "SUBJECT", false),
            HomeRolePresence("phone-b", "b", "HELPER", false),
        ))
        assertEquals(2, merged.size)
        assertEquals(setOf("HELPER", "SUBJECT"), merged[0].roles)
        assertTrue(merged[0].online)
        assertFalse(merged[1].online)
    }

    @Test fun duplicateLegacyRelationDoesNotCreateAnotherCard() {
        val merged = HomeDevicePolicy.collapse(listOf(
            HomeRolePresence("phone-a", "old", "HELPER", false),
            HomeRolePresence("phone-a", "old", "HELPER", false),
            HomeRolePresence("phone-a", "current", "HELPER", true),
        ))
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().relations.size)
        assertTrue(merged.single().online)
    }

    @Test fun offlineListContainsOnlySavedRelationsNotOnlineDevices() {
        val savedRelations = listOf(
            HomeRolePresence("paired-offline-a", "rel-a", "HELPER", false),
            HomeRolePresence("paired-online-b", "rel-b", "HELPER", true),
            HomeRolePresence("paired-offline-a", "rel-a-reverse", "SUBJECT", false),
            HomeRolePresence("paired-offline-c", "rel-c", "SUBJECT", false),
        )
        val offline = HomeDevicePolicy.collapse(savedRelations).filterNot { it.online }
        assertEquals(2, offline.size)
        assertEquals(setOf("paired-offline-a", "paired-offline-c"),
            offline.map { it.fingerprint }.toSet())
        assertEquals(2, offline.first().relations.size)
        assertFalse(offline.any { it.fingerprint == "paired-online-b" })
    }
}
