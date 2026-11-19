package com.music.bitchord.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BUDS B2: the pure helpers only (addresses, GUID bytes, native layouts, error codes, filter, ref count). No Bluetooth. */
class DesktopBudsWindowsTest {

    private fun bytesOf(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ── addresses ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun macStringToAddressAndBack() {
        assertEquals(0xAABBCCDDEEFFL, BudsAddress.fromMac("AA:BB:CC:DD:EE:FF"))
        assertEquals(0xAABBCCDDEEFFL, BudsAddress.fromMac("aa-bb-cc-dd-ee-ff"))
        assertEquals(0x001122334455L, BudsAddress.fromMac("001122334455"))
        assertEquals("00:11:22:33:44:55", BudsAddress.toMac(0x001122334455L))
        assertEquals("AA:BB:CC:DD:EE:FF", BudsAddress.toMac(0xAABBCCDDEEFFL))
        val a = 0x0123456789ABL
        assertEquals(a, BudsAddress.fromMac(BudsAddress.toMac(a)))
    }

    @Test
    fun badMacStringsAreRejected() {
        for (s in listOf("", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:FF:00", "AA:BB:CC:DD:EE:GG", "AABBCCDDEEF", "A:BB:CC:DD:EE:FF", "AA.BB.CC.DD.EE.FF")) {
            assertNull(BudsAddress.fromMac(s), s)
        }
    }

    @Test
    fun shortHashIsStableShortAndNotTheAddress() {
        val h = BudsAddress.shortHash(0xAABBCCDDEEFFL)
        assertEquals(4, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(h, BudsAddress.shortHash(0xAABBCCDDEEFFL))
        assertNotEquals(h, BudsAddress.shortHash(0xAABBCCDDEE00L))
    }

    @Test
    fun deviceToStringNeverShowsNameOrAddress() {
        val d = BudsDevice("OnePlus Buds Pro 2", 0xAABBCCDDEEFFL)
        val s = d.toString()
        assertFalse(s.contains("OnePlus", ignoreCase = true))
        assertFalse(s.contains("AABBCCDDEEFF", ignoreCase = true))
        assertFalse(s.contains(0xAABBCCDDEEFFL.toString()))
        assertTrue(s.contains(BudsAddress.shortHash(0xAABBCCDDEEFFL)))
        val raw = BudsRawDevice("OnePlus Buds Pro 2", 0xAABBCCDDEEFFL, true, true, true).toString()
        assertFalse(raw.contains("OnePlus", ignoreCase = true))
    }

    // ── GUID and SOCKADDR_BTH ────────────────────────────────────────────────────────────────────

    @Test
    fun uuidToGuidByteOrder() {
        assertContentEquals(
            bytesOf(0x9A, 0x07, 0x00, 0x00, 0x02, 0xD1, 0xE1, 0x11, 0x9B, 0x23, 0x00, 0x02, 0x5B, 0x00, 0xA5, 0xA5),
            BudsGuid.toBytes(UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")),
        )
        assertContentEquals(
            bytesOf(0x07, 0x11, 0x00, 0x00, 0x02, 0xD1, 0xE1, 0x11, 0x9B, 0x23, 0x00, 0x02, 0x5B, 0x00, 0xA5, 0xA5),
            BudsGuid.toBytes(UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5")),
        )
    }

    @Test
    fun theServiceUuidsParseAndKeepTheirOrder() {
        val parsed = BudsIds.SERVICE_UUIDS.map { UUID.fromString(it) }
        assertEquals(2, parsed.size)
        assertEquals(0x079AL, parsed[0].mostSignificantBits ushr 32)
        assertEquals(0x1107L, parsed[1].mostSignificantBits ushr 32)
    }

    @Test
    fun sockaddrBthLayout() {
        val b = BudsSockaddr.build(0xAABBCCDDEEFFL, UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5"))
        assertEquals(30, b.size)
        assertContentEquals(bytesOf(0x20, 0x00), b.copyOfRange(0, 2))                                   // AF_BTH = 32
        assertContentEquals(bytesOf(0xFF, 0xEE, 0xDD, 0xCC, 0xBB, 0xAA, 0x00, 0x00), b.copyOfRange(2, 10))  // btAddr
        assertContentEquals(BudsGuid.toBytes(UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")), b.copyOfRange(10, 26))
        assertContentEquals(bytesOf(0, 0, 0, 0), b.copyOfRange(26, 30))                                   // port 0: SDP lookup
    }

    @Test
    fun sockaddrKeepsOnly48BitsOfTheAddress() {
        val b = BudsSockaddr.build(-1L, UUID(0L, 0L))
        assertContentEquals(bytesOf(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00), b.copyOfRange(2, 10))
    }

    // ── search params and device info ────────────────────────────────────────────────────────────

    @Test
    fun searchParamsLayoutOn64And32Bit() {
        val p64 = BudsSearchParams.bytes(8)
        assertEquals(40, p64.size)
        val b = ByteBuffer.wrap(p64).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(40, b.getInt(0))
        assertEquals(1, b.getInt(4))     // authenticated
        assertEquals(1, b.getInt(8))     // remembered
        assertEquals(0, b.getInt(12))    // unknown
        assertEquals(1, b.getInt(16))    // connected
        assertEquals(0, b.getInt(20))    // no inquiry, ever
        assertEquals(0, p64[24].toInt())
        assertEquals(32, BudsSearchParams.hRadioOffset(8))
        val p32 = BudsSearchParams.bytes(4)
        assertEquals(32, p32.size)
        assertEquals(28, BudsSearchParams.hRadioOffset(4))
        assertEquals(32, ByteBuffer.wrap(p32).order(ByteOrder.LITTLE_ENDIAN).getInt(0))
    }

    private fun deviceInfo(name: String, address: Long, connected: Boolean, remembered: Boolean = true, authenticated: Boolean = true): ByteArray {
        val out = ByteArray(BudsDeviceInfoLayout.SIZE)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, BudsDeviceInfoLayout.SIZE)
        b.putLong(8, address)
        b.putInt(16, 0x240404)
        b.putInt(20, if (connected) 1 else 0)
        b.putInt(24, if (remembered) 1 else 0)
        b.putInt(28, if (authenticated) 1 else 0)
        name.toByteArray(StandardCharsets.UTF_16LE).copyInto(out, 64)
        return out
    }

    @Test
    fun deviceInfoParses() {
        val raw = BudsDeviceInfoLayout.parse(deviceInfo("OnePlus Buds Pro 2", 0x112233445566L, true))!!
        assertEquals("OnePlus Buds Pro 2", raw.name)
        assertEquals(0x112233445566L, raw.address)
        assertTrue(raw.connected)
        assertTrue(raw.remembered)
        assertTrue(raw.authenticated)
        val off = BudsDeviceInfoLayout.parse(deviceInfo("realme Buds Air 5", 1L, false, remembered = false, authenticated = false))!!
        assertFalse(off.connected)
        assertFalse(off.remembered)
        assertFalse(off.authenticated)
    }

    @Test
    fun deviceInfoMasksTheAddressAndStopsAtTheNameTerminator() {
        val bytes = deviceInfo("DIZO GoPods D", -1L, true)
        // junk after the terminator must not leak into the name
        "XX".toByteArray(StandardCharsets.UTF_16LE).copyInto(bytes, 64 + ("DIZO GoPods D".length + 1) * 2)
        val raw = BudsDeviceInfoLayout.parse(bytes)!!
        assertEquals("DIZO GoPods D", raw.name)
        assertEquals(0xFFFFFFFFFFFFL, raw.address)
    }

    @Test
    fun shortDeviceInfoIsRejected() {
        assertNull(BudsDeviceInfoLayout.parse(ByteArray(100)))
        assertNull(BudsDeviceInfoLayout.parse(ByteArray(0)))
    }

    // ── error codes ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun winsockErrorsMapToCodes() {
        assertEquals(BudsErrorCodes.TIMEOUT, BudsSocketErrors.toCode(10060))
        assertEquals(BudsErrorCodes.ALREADY_IN_USE, BudsSocketErrors.toCode(10061))
        assertEquals(BudsErrorCodes.ALREADY_IN_USE, BudsSocketErrors.toCode(10048))
        assertEquals(BudsErrorCodes.ALREADY_IN_USE, BudsSocketErrors.toCode(10013))
        assertEquals(BudsErrorCodes.NOT_CONNECTED, BudsSocketErrors.toCode(10057))
        assertEquals(BudsErrorCodes.NOT_CONNECTED, BudsSocketErrors.toCode(10064))
        assertEquals(BudsErrorCodes.NOT_CONNECTED, BudsSocketErrors.toCode(10050))
        assertEquals(BudsErrorCodes.NOT_CONNECTED, BudsSocketErrors.toCode(10047))
        assertEquals(BudsErrorCodes.UNKNOWN, BudsSocketErrors.toCode(0))
        assertEquals(BudsErrorCodes.UNKNOWN, BudsSocketErrors.toCode(99999))
    }

    // ── filter ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun filterKeepsOnlyConnectedCompatibleDevicesOncePerAddress() {
        fun d(name: String, addr: Long, connected: Boolean) = BudsRawDevice(name, addr, connected, true, true)
        val list = listOf(
            d("OnePlus Buds Pro 2", 1, true),
            d("OnePlus Buds Pro 2", 1, true),            // same address twice
            d("realme Buds Air 5", 2, false),            // paired but not connected
            d("Sony WF-1000XM5", 3, true),               // connected, other brand
            d("", 4, true),                              // no name
            d("OPPO Enco X2", 5, true),
        )
        val got = BudsDeviceFilter.connectedCompatible(list)
        assertEquals(listOf(1L, 5L), got.map { it.address })
        assertEquals(listOf("OnePlus Buds Pro 2", "OPPO Enco X2"), got.map { it.name })
        assertTrue(BudsDeviceFilter.connectedCompatible(emptyList()).isEmpty())
    }

    // ── Winsock reference count ──────────────────────────────────────────────────────────────────

    @Test
    fun refCounterStartsOnceAndStopsOnTheLastRelease() {
        var starts = 0
        var stops = 0
        val c = BudsRefCounter(onFirst = { starts++; true }, onLast = { stops++ })
        assertTrue(c.acquire())
        assertTrue(c.acquire())
        assertEquals(1, starts)
        c.release()
        assertEquals(0, stops)
        c.release()
        assertEquals(1, stops)
        c.release()                                        // extra release changes nothing
        assertEquals(1, stops)
        assertEquals(0, c.holders())
        assertTrue(c.acquire())                           // a new round starts again
        assertEquals(2, starts)
    }

    @Test
    fun refCounterDoesNotCountAFailedStart() {
        var ok = false
        val c = BudsRefCounter(onFirst = { ok }, onLast = {})
        assertFalse(c.acquire())
        assertEquals(0, c.holders())
        ok = true
        assertTrue(c.acquire())
        assertEquals(1, c.holders())
    }

    // ── off Windows nothing is touched ───────────────────────────────────────────────────────────

    @Test
    fun offWindowsDiscoveryIsEmptyAndOpenFails() {
        if (DesktopPlatform.isWindows) return
        assertTrue(DesktopBudsDevices.connectedCompatible().isEmpty())
        val r = kotlinx.coroutines.runBlocking { WindowsRfcommTransport.open(BudsDevice("OnePlus Buds 4", 1L)) }
        assertTrue(r.isFailure)
        assertEquals(BudsErrorCodes.UNKNOWN, (r.exceptionOrNull() as BudsConnectException).code)
    }
}
