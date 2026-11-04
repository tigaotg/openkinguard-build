package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NicknameAndMapHandoffTest {
    @Test fun legacyPlaceholderMigratesToSystemNameWithoutLosingCustomName() {
        assertFalse(SystemNicknamePolicy.isLegacyCustom("我的设备"))
        assertFalse(SystemNicknamePolicy.isLegacyCustom(""))
        assertTrue(SystemNicknamePolicy.isLegacyCustom("妈妈的手机"))
        assertEquals("妈妈的手机", SystemNicknamePolicy.displayName(true,
            "妈妈的手机", "荣耀7X", "HONOR", "hi6250"))
        assertEquals("荣耀7X", SystemNicknamePolicy.displayName(false,
            "我的设备", "荣耀7X", "HONOR", "hi6250"))
    }

    @Test fun deviceModelIsFallbackAndUserDoesNotSeeDangerousControlCharacters() {
        assertEquals("HONOR hi6250", SystemNicknamePolicy.displayName(false,
            "", null, "HONOR", "hi6250"))
        assertEquals("荣耀7X", SystemNicknamePolicy.displayName(false,
            "", "\u202e荣耀7X\n", "HONOR", "hi6250"))
        assertEquals("安卓手机", SystemNicknamePolicy.displayName(false,
            "", null, "", ""))
    }

    @Test fun googleUsesWgs84LatitudeFirstWhileAmapMarksWgs84LongitudeFirst() {
        assertEquals("https://www.google.com/maps/search/?api=1&query=34.3416000%2C108.9402000",
            MapHandoffPolicy.googleUrl(343416000, 1089402000))
        assertEquals("https://uri.amap.com/marker?position=108.9402000%2C34.3416000" +
            "&name=%E8%AE%BE%E5%A4%87%E4%BD%8D%E7%BD%AE&coordinate=wgs84&callnative=1",
            MapHandoffPolicy.amapWebUrl(343416000, 1089402000))
    }

    @Test fun nativeMapsDeclareOriginalGpsDatumToAvoidWrongOffset() {
        val amap = MapHandoffPolicy.amapNativeParameters(343416000, 1089402000)
        assertEquals("34.3416000", amap["lat"])
        assertEquals("108.9402000", amap["lon"])
        assertEquals("1", amap["dev"])
        val baidu = MapHandoffPolicy.baiduNativeParameters(343416000, 1089402000)
        assertEquals("34.3416000,108.9402000", baidu["location"])
        assertEquals("wgs84", baidu["coord_type"])
        assertFalse(baidu.containsKey("dev"))
    }
}
