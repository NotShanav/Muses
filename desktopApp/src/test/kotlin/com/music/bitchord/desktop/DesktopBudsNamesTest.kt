package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BUDS B1: name matching. Pure. */
class DesktopBudsNamesTest {

    @Test
    fun compatibleFamiliesMatch() {
        val yes = listOf(
            "OnePlus Buds Pro 2", "OnePlus Buds 3", "ONEPLUS BUDS 4", "oneplus_buds_z2",
            "OnePlus Nord Buds 2r", "OnePlus Nord Buds 3 Pro", "Nord Buds 2",
            "OnePlus Bullets Wireless Z2 ANC",
            "OPPO Enco Air3 Pro", "OPPO Enco X2", "Enco Free3",
            "realme Buds Air 5 Pro", "realme Buds T300", "Realme Buds Q2s", "realme Buds Wireless 3",
            "realme TechLife Buds T100",
            "DIZO Wireless Power", "DIZO GoPods D", "dizo gopods",
        )
        for (n in yes) assertTrue(DesktopBudsNames.isLikelyCompatible(n), n)
    }

    @Test
    fun otherBrandsAndOtherProductsDoNotMatch() {
        val no = listOf(
            "", "   ", "Buds", "AirPods Pro", "Galaxy Buds2 Pro", "Sony WF-1000XM5", "boAt Airdopes 141",
            "Nothing Ear (2)", "Jabra Elite 75t", "Bose QuietComfort",
            "OnePlus Watch 2", "OnePlus Pad", "OnePlus Nord CE 3", "realme Watch S", "realme Band 2",
            "OPPO Reno 8", "DIZO Watch D", "Encore Speaker",
        )
        for (n in no) assertFalse(DesktopBudsNames.isLikelyCompatible(n), n)
    }
}
