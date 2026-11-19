package com.music.bitchord.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/*
 * BUDS B2: Windows Bluetooth transport (classic RFCOMM through Winsock) and device discovery
 * (bthprops.cpl). Windows only: everywhere else every entry point returns empty / failure.
 * JNA is loaded lazily the first time a Windows method runs; nothing Windows is touched at class load.
 *
 * Own minimal JNA interfaces (same pattern as DesktopProcessMemory.kt). Native structures are built and
 * read as raw byte blocks (Memory) through pure helpers, as DesktopProcessMemory does, instead of JNA
 * Structure subclasses (the repo notes Kotlin/JNA field-reflection pitfalls); layouts are unit tested.
 *
 * Privacy: nothing here logs. A device prints only a short hash of its address, never its name.
 */

/** A paired, connected earbuds device. [toString] never shows the name or the address. */
internal data class BudsDevice(val name: String, val address: Long /* 48-bit BD_ADDR */) {
    override fun toString(): String = "BudsDevice(#${BudsAddress.shortHash(address)})"
}

/** Failure codes B3 maps to messages. */
internal object BudsErrorCodes {
    const val ALREADY_IN_USE = "ALREADY_IN_USE"
    const val NOT_CONNECTED = "NOT_CONNECTED"
    const val TIMEOUT = "TIMEOUT"
    const val UNKNOWN = "UNKNOWN"
}

internal class BudsConnectException(val code: String, message: String) : Exception(message)

/** One entry as Windows reports it, before filtering. */
internal data class BudsRawDevice(
    val name: String,
    val address: Long,
    val connected: Boolean,
    val remembered: Boolean,
    val authenticated: Boolean,
    /** Windows lists the vendor control service (see [BudsIds.SERVICE_UUIDS]) for it: the reliable sign of compatible earbuds. */
    val vendorService: Boolean = false,
) {
    override fun toString(): String = "BudsRawDevice(#${BudsAddress.shortHash(address)})"
}

// ── pure helpers (unit tested, no Windows) ───────────────────────────────────────────────────────

internal object BudsAddress {
    private const val MASK = 0xFFFFFFFFFFFFL

    /** `AA:BB:CC:DD:EE:FF`, `AA-BB-...` or `AABBCCDDEEFF` to a 48-bit value; null for anything else. */
    fun fromMac(mac: String): Long? {
        val t = mac.trim()
        val hex = if (t.contains(':') || t.contains('-')) {
            val parts = t.split(':', '-')
            if (parts.size != 6 || parts.any { it.length != 2 }) return null
            parts.joinToString("")
        } else {
            t
        }
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return hex.toLong(16)
    }

    fun toMac(address: Long): String =
        (5 downTo 0).joinToString(":") { String.format("%02X", ((address and MASK) ushr (8 * it)).toInt() and 0xFF) }

    /** Four hex digits (FNV-1a over the six bytes). For logs only; it cannot be turned back into an address. */
    fun shortHash(address: Long): String {
        var h = 0xCBF29CE484222325UL
        for (i in 0 until 6) {
            h = h xor (((address and MASK) ushr (8 * i)) and 0xFF).toULong()
            h *= 0x100000001B3UL
        }
        return String.format("%04x", (h and 0xFFFFUL).toInt())
    }
}

/** Windows GUID memory layout: Data1 u32 LE, Data2 u16 LE, Data3 u16 LE, Data4 8 bytes in order. */
internal object BudsGuid {
    fun toBytes(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val out = ByteArray(16)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, (msb ushr 32).toInt())
        b.putShort(4, (msb ushr 16).toShort())
        b.putShort(6, msb.toShort())
        for (i in 0 until 8) out[8 + i] = (lsb ushr (56 - 8 * i)).toByte()
        return out
    }
}

internal object BudsGuidRead {
    /** Inverse of [BudsGuid.toBytes]; null when fewer than 16 bytes remain at [offset]. */
    fun fromBytes(bytes: ByteArray, offset: Int = 0): UUID? {
        if (offset < 0 || bytes.size < offset + 16) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val d1 = b.getInt(offset).toLong() and 0xFFFFFFFFL
        val d2 = b.getShort(offset + 4).toLong() and 0xFFFFL
        val d3 = b.getShort(offset + 6).toLong() and 0xFFFFL
        var lsb = 0L
        for (i in 0 until 8) lsb = (lsb shl 8) or (bytes[offset + 8 + i].toLong() and 0xFFL)
        return UUID((d1 shl 32) or (d2 shl 16) or d3, lsb)
    }
}

/** The services Windows set up for a device (BluetoothEnumerateInstalledServices): does it offer the vendor control service? */
internal object BudsServices {
    const val MAX_GUIDS = 32

    /** [guidBytes] is the raw GUID array Windows filled in, [count] the number of entries it reports. */
    fun hasVendorService(guidBytes: ByteArray, count: Int): Boolean {
        val vendor = BudsIds.SERVICE_UUIDS.map { UUID.fromString(it) }.toSet()
        val n = count.coerceIn(0, MAX_GUIDS)
        for (i in 0 until n) {
            val g = BudsGuidRead.fromBytes(guidBytes, i * 16) ?: continue
            if (g in vendor) return true
        }
        return false
    }
}

/**
 * SOCKADDR_BTH, packed (pshpack1) = 30 bytes: addressFamily USHORT @0 (AF_BTH 32), btAddr ULONGLONG @2,
 * serviceClassId GUID @10, port ULONG @26 (0: Windows resolves the channel from the service UUID by SDP).
 */
internal object BudsSockaddr {
    const val SIZE = 30
    const val AF_BTH: Short = 32

    fun build(address: Long, service: UUID): ByteArray {
        val out = ByteArray(SIZE)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, AF_BTH)
        b.putLong(2, address and 0xFFFFFFFFFFFFL)
        BudsGuid.toBytes(service).copyInto(out, 10)
        b.putInt(26, 0)
        return out
    }
}

/**
 * BLUETOOTH_DEVICE_SEARCH_PARAMS: DWORD dwSize @0, BOOL fReturnAuthenticated @4, fReturnRemembered @8,
 * fReturnUnknown @12, fReturnConnected @16, fIssueInquiry @20, UCHAR cTimeoutMultiplier @24, HANDLE hRadio
 * (pointer aligned: @32 on 64 bit, @28 on 32 bit). Size 40 / 32. No inquiry is ever issued.
 */
internal object BudsSearchParams {
    fun hRadioOffset(pointerSize: Int): Int = if (pointerSize >= 8) 32 else 28

    fun size(pointerSize: Int): Int = hRadioOffset(pointerSize) + pointerSize

    fun bytes(pointerSize: Int): ByteArray {
        val size = size(pointerSize)
        val out = ByteArray(size)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, size)
        b.putInt(4, 1)      // fReturnAuthenticated
        b.putInt(8, 1)      // fReturnRemembered
        b.putInt(12, 0)     // fReturnUnknown
        b.putInt(16, 1)     // fReturnConnected
        b.putInt(20, 0)     // fIssueInquiry: never scan, only list what Windows already knows
        out[24] = 0         // cTimeoutMultiplier
        return out          // hRadio = NULL: all radios
    }
}

/**
 * BLUETOOTH_DEVICE_INFO (560 bytes): DWORD dwSize @0, BLUETOOTH_ADDRESS (8) @8, ULONG ulClassofDevice @16,
 * BOOL fConnected @20, fRemembered @24, fAuthenticated @28, SYSTEMTIME stLastSeen @32, stLastUsed @48 (16 each),
 * WCHAR szName[248] @64.
 */
internal object BudsDeviceInfoLayout {
    const val SIZE = 560
    private const val NAME_OFFSET = 64
    private const val NAME_CHARS = 248

    fun parse(bytes: ByteArray): BudsRawDevice? {
        if (bytes.size < SIZE) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val address = b.getLong(8) and 0xFFFFFFFFFFFFL
        var chars = 0
        while (chars < NAME_CHARS && b.getShort(NAME_OFFSET + chars * 2).toInt() != 0) chars++
        val name = String(bytes, NAME_OFFSET, chars * 2, StandardCharsets.UTF_16LE).trim()
        return BudsRawDevice(
            name = name,
            address = address,
            connected = b.getInt(20) != 0,
            remembered = b.getInt(24) != 0,
            authenticated = b.getInt(28) != 0,
        )
    }
}

/** Winsock error to the codes B3 shows. The ALREADY_IN_USE mapping is a best guess (UNVERIFIED on hardware). */
internal object BudsSocketErrors {
    const val WSAEACCES = 10013
    const val WSAEINVAL = 10022
    const val WSAEAFNOSUPPORT = 10047
    const val WSAEADDRINUSE = 10048
    const val WSAEADDRNOTAVAIL = 10049
    const val WSAENETDOWN = 10050
    const val WSAENETUNREACH = 10051
    const val WSAECONNABORTED = 10053
    const val WSAECONNRESET = 10054
    const val WSAEISCONN = 10056
    const val WSAENOTCONN = 10057
    const val WSAETIMEDOUT = 10060
    const val WSAECONNREFUSED = 10061
    const val WSAEHOSTDOWN = 10064
    const val WSAEHOSTUNREACH = 10065

    fun toCode(winsockError: Int): String = when (winsockError) {
        WSAEACCES, WSAEADDRINUSE, WSAECONNREFUSED, WSAEISCONN -> BudsErrorCodes.ALREADY_IN_USE
        WSAETIMEDOUT -> BudsErrorCodes.TIMEOUT
        WSAEAFNOSUPPORT, WSAENETDOWN, WSAENETUNREACH, WSAECONNABORTED, WSAECONNRESET, WSAENOTCONN,
        WSAEHOSTDOWN, WSAEHOSTUNREACH, WSAEADDRNOTAVAIL -> BudsErrorCodes.NOT_CONNECTED
        else -> BudsErrorCodes.UNKNOWN
    }
}

/** Connected AND compatible, one entry per address, in Windows' order. */
internal object BudsDeviceFilter {
    fun connectedCompatible(raw: List<BudsRawDevice>): List<BudsDevice> =
        raw.filter { it.connected && (it.vendorService || (it.name.isNotBlank() && DesktopBudsNames.isLikelyCompatible(it.name))) }
            .distinctBy { it.address }
            .map { BudsDevice(it.name.ifBlank { "Earbuds" }, it.address) }
}

/** Shared start / stop of Winsock: [onFirst] on 0 to 1 (false = failed, nothing counted), [onLast] on 1 to 0. */
internal class BudsRefCounter(private val onFirst: () -> Boolean, private val onLast: () -> Unit) {
    private var count = 0

    @Synchronized
    fun acquire(): Boolean {
        if (count == 0 && !onFirst()) return false
        count++
        return true
    }

    @Synchronized
    fun release() {
        if (count == 0) return
        count--
        if (count == 0) onLast()
    }

    @Synchronized
    fun holders(): Int = count
}

// ── Windows layer (only reached on Windows) ──────────────────────────────────────────────────────

internal interface BudsWs2 : StdCallLibrary {
    fun WSAStartup(versionRequested: Short, data: Pointer): Int
    fun WSACleanup(): Int
    fun socket(af: Int, type: Int, protocol: Int): Pointer?
    fun connect(s: Pointer, name: Pointer, nameLen: Int): Int
    fun send(s: Pointer, buf: ByteArray, len: Int, flags: Int): Int
    fun recv(s: Pointer, buf: ByteArray, len: Int, flags: Int): Int
    fun closesocket(s: Pointer): Int
    fun setsockopt(s: Pointer, level: Int, optName: Int, optVal: Pointer, optLen: Int): Int
    fun WSAGetLastError(): Int
}

internal interface BudsBth : StdCallLibrary {
    fun BluetoothFindFirstDevice(params: Pointer, info: Pointer): Pointer?
    fun BluetoothFindNextDevice(find: Pointer, info: Pointer): Boolean
    fun BluetoothFindDeviceClose(find: Pointer): Boolean
    fun BluetoothEnumerateInstalledServices(radio: Pointer?, info: Pointer, count: Pointer, guids: Pointer): Int
}

/** Lazy loaders. Both are null off Windows or when the library cannot be loaded. */
internal object BudsNative {
    const val AF_BTH = 32
    const val SOCK_STREAM = 1
    const val BTHPROTO_RFCOMM = 3
    const val SOL_SOCKET = 0xFFFF
    const val SO_SNDTIMEO = 0x1005
    const val SO_RCVTIMEO = 0x1006
    const val WINSOCK_2_2: Short = 0x0202

    val ws2: BudsWs2? by lazy {
        if (!DesktopPlatform.isWindows) {
            null
        } else {
            runCatching { Native.load("ws2_32", BudsWs2::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
        }
    }

    val bth: BudsBth? by lazy {
        if (!DesktopPlatform.isWindows) {
            null
        } else {
            runCatching { Native.load("bthprops.cpl", BudsBth::class.java, W32APIOptions.DEFAULT_OPTIONS) }.getOrNull()
        }
    }

    /** INVALID_SOCKET is ~0 (all ones, pointer sized): -1 on 64 bit, 0xFFFFFFFF on 32 bit. */
    fun isInvalidSocket(s: Pointer?): Boolean {
        if (s == null) return true
        val v = Pointer.nativeValue(s)
        return v == -1L || v == 0xFFFFFFFFL
    }

    /** Bluetooth devices Windows already knows (no inquiry). Empty on any failure. */
    fun enumerate(onError: (String) -> Unit = {}): List<BudsRawDevice> {
        val api = bth ?: return emptyList<BudsRawDevice>()
        return runCatching {
            val paramBytes = BudsSearchParams.bytes(Native.POINTER_SIZE)
            Memory(paramBytes.size.toLong()).use { params ->
                Memory(BudsDeviceInfoLayout.SIZE.toLong()).use { info ->
                    params.write(0, paramBytes, 0, paramBytes.size)
                    info.clear()
                    info.setInt(0, BudsDeviceInfoLayout.SIZE)
                    val find = api.BluetoothFindFirstDevice(params, info)
                    if (find == null) {
                        emptyList<BudsRawDevice>()
                    } else {
                        val out = ArrayList<BudsRawDevice>()
                        try {
                            var more = true
                            var guard = 0
                            while (more && guard++ < 256) {
                                BudsDeviceInfoLayout.parse(info.getByteArray(0, BudsDeviceInfoLayout.SIZE))?.let { raw ->
                                    // Only for connected devices; `info` still holds this device here.
                                    out.add(if (raw.connected) raw.copy(vendorService = hasVendorService(api, info)) else raw)
                                }
                                info.clear()
                                info.setInt(0, BudsDeviceInfoLayout.SIZE)
                                more = api.BluetoothFindNextDevice(find, info)
                            }
                        } finally {
                            api.BluetoothFindDeviceClose(find)
                        }
                        out
                    }
                }
            }
        }.getOrElse {
            onError(it.javaClass.simpleName)
            emptyList<BudsRawDevice>()
        }
    }

    /** True when Windows lists the vendor control service for the device in [info]. False on any failure. */
    private fun hasVendorService(api: BudsBth, info: Pointer): Boolean = runCatching {
        val bytesLen = BudsServices.MAX_GUIDS * 16
        Memory(bytesLen.toLong()).use { guids ->
            Memory(4).use { count ->
                guids.clear()
                count.setInt(0, BudsServices.MAX_GUIDS)
                val rc = api.BluetoothEnumerateInstalledServices(null, info, count, guids)
                rc == 0 && BudsServices.hasVendorService(guids.getByteArray(0, bytesLen), count.getInt(0))
            }
        }
    }.getOrDefault(false)
}

/** Winsock start / stop shared by all open transports; the last close calls WSACleanup. */
internal object BudsWinsock {
    private val counter = BudsRefCounter(
        onFirst = {
            val api = BudsNative.ws2
            api != null && runCatching {
                Memory(512).use { data -> api.WSAStartup(BudsNative.WINSOCK_2_2, data) == 0 }
            }.getOrDefault(false)
        },
        onLast = { runCatching { BudsNative.ws2?.WSACleanup() } },
    )

    fun acquire(): Boolean = counter.acquire()
    fun release() = counter.release()
}

internal object DesktopBudsDevices {
    /**
     * Paired devices that are connected right now and look compatible. Blocking (a short native call): call it
     * off the UI thread. No inquiry, no cache, nothing logged.
     */
    fun connectedCompatible(): List<BudsDevice> {
        if (!DesktopPlatform.isWindows) return emptyList()
        var failure: String? = null
        val raw = BudsNative.enumerate { failure = it }
        val kept = BudsDeviceFilter.connectedCompatible(raw)
        if (DesktopBudsSettings.debugFrames.value) logScan(raw, kept.size, failure)
        return kept
    }

    @Volatile
    private var lastScanLine: String? = null

    /** Debug frames only. Never a name or an address: a short hash per device plus yes/no flags. Printed when it changes. */
    private fun logScan(raw: List<BudsRawDevice>, kept: Int, failure: String?) {
        val line = buildString {
            append("scan: bthprops=").append(if (BudsNative.bth == null) "missing" else "ok")
            append(" raw=").append(raw.size)
            append(" connected=").append(raw.count { it.connected })
            append(" kept=").append(kept)
            if (failure != null) append(" error=").append(failure)
            for (d in raw) {
                append(" [#").append(BudsAddress.shortHash(d.address))
                append(" conn=").append(if (d.connected) 1 else 0)
                append(" vendor=").append(if (d.vendorService) 1 else 0)
                append(" name=").append(if (DesktopBudsNames.isLikelyCompatible(d.name)) 1 else 0)
                append(']')
            }
        }
        if (line == lastScanLine) return
        lastScanLine = line
        System.err.println("buds: $line")
    }
}

/** One RFCOMM socket to the earbuds. Create with [open]. */
internal class WindowsRfcommTransport(
    private val device: BudsDevice,
    private val uuids: List<UUID>,
) : BudsTransport {

    @Volatile
    private var socket: Pointer? = null
    private val closed = AtomicBoolean(false)
    private val holdsWinsock = AtomicBoolean(false)

    companion object {
        const val CONNECT_TIMEOUT_MS = 6_000L
        const val RECEIVE_TIMEOUT_MS = 500
        const val SEND_TIMEOUT_MS = 5_000

        /** Tries the service UUIDs in order; the first success wins. Never throws: a failure is a [BudsConnectException]. */
        suspend fun open(device: BudsDevice): Result<WindowsRfcommTransport> {
            if (!DesktopPlatform.isWindows) {
                return Result.failure(BudsConnectException(BudsErrorCodes.UNKNOWN, "Earbuds control works on Windows only"))
            }
            return withContext(Dispatchers.IO) {
                val transport = WindowsRfcommTransport(device, BudsIds.SERVICE_UUIDS.map { UUID.fromString(it) })
                transport.connectBlocking().map { transport }
            }
        }
    }

    private fun failure(code: String, message: String): Result<Unit> = Result.failure(BudsConnectException(code, message))

    private fun connectBlocking(): Result<Unit> {
        val api = BudsNative.ws2 ?: return failure(BudsErrorCodes.UNKNOWN, "Winsock is not available")
        if (!BudsWinsock.acquire()) return failure(BudsErrorCodes.UNKNOWN, "Winsock did not start")
        holdsWinsock.set(true)
        var firstError: String? = null
        try {
            for (uuid in uuids) {
                val error = tryService(api, uuid)
                if (error == null) return Result.success(Unit)
                if (firstError == null) firstError = error   // the first UUID is the one that matters; the second times out on most models
            }
        } catch (t: Throwable) {
            if (firstError == null) firstError = BudsErrorCodes.UNKNOWN
        }
        close()
        return failure(firstError ?: BudsErrorCodes.UNKNOWN, "Could not open the earbuds link")
    }

    /** Null = connected (socket stored). Otherwise the error code. The connect runs on a worker so it can be abandoned. */
    private fun tryService(api: BudsWs2, uuid: UUID): String? {
        val s = api.socket(BudsNative.AF_BTH, BudsNative.SOCK_STREAM, BudsNative.BTHPROTO_RFCOMM)
        if (BudsNative.isInvalidSocket(s) || s == null) return BudsSocketErrors.toCode(api.WSAGetLastError())
        val addr = BudsSockaddr.build(device.address, uuid)
        val mem = Memory(addr.size.toLong())
        mem.write(0, addr, 0, addr.size)
        val result = CompletableFuture<Int>()
        thread(isDaemon = true, name = "muses-buds-connect") {
            val code = try {
                if (api.connect(s, mem, addr.size) == 0) 0 else api.WSAGetLastError()
            } catch (t: Throwable) {
                -1
            }
            result.complete(code)
        }
        val code = try {
            result.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            runCatching { api.closesocket(s) }        // makes the blocked connect return
            runCatching { result.get(1, TimeUnit.SECONDS) }
            return BudsErrorCodes.TIMEOUT
        } catch (e: Exception) {
            runCatching { api.closesocket(s) }
            return BudsErrorCodes.UNKNOWN
        }
        if (code != 0) {
            runCatching { api.closesocket(s) }
            return if (code < 0) BudsErrorCodes.UNKNOWN else BudsSocketErrors.toCode(code)
        }
        setTimeout(api, s, BudsNative.SO_RCVTIMEO, RECEIVE_TIMEOUT_MS)
        setTimeout(api, s, BudsNative.SO_SNDTIMEO, SEND_TIMEOUT_MS)
        socket = s
        return null
    }

    private fun setTimeout(api: BudsWs2, s: Pointer, option: Int, ms: Int) {
        runCatching {
            Memory(4).use { value ->
                value.setInt(0, ms)
                api.setsockopt(s, BudsNative.SOL_SOCKET, option, value, 4)
            }
        }
    }

    /** Bytes read; -1 when the link is closed or broken. Receive timeouts loop here so a cancelled coroutine can stop. */
    override suspend fun read(buffer: ByteArray): Int {
        while (true) {
            currentCoroutineContext().ensureActive()
            val r = withContext(Dispatchers.IO) { recvOnce(buffer) }
            if (r != 0) return r
        }
    }

    /** n > 0 bytes, 0 = receive timeout (try again), -1 = closed or failed. */
    private fun recvOnce(buffer: ByteArray): Int {
        val s = socket ?: return -1
        val api = BudsNative.ws2 ?: return -1
        val n = try {
            api.recv(s, buffer, buffer.size, 0)
        } catch (t: Throwable) {
            return -1
        }
        if (n > 0) return n
        if (n == 0) return -1                       // the buds closed the link
        return if (api.WSAGetLastError() == BudsSocketErrors.WSAETIMEDOUT) 0 else -1
    }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val api = BudsNative.ws2 ?: throw IOException("link closed")
            var offset = 0
            while (offset < bytes.size) {
                val s = socket ?: throw IOException("link closed")
                val chunk = if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)
                val n = api.send(s, chunk, chunk.size, 0)
                if (n <= 0) throw IOException("send failed (${BudsSocketErrors.toCode(api.WSAGetLastError())})")
                offset += n
            }
        }
    }

    /** Idempotent: closes the socket (a blocked read then returns -1) and releases Winsock. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val s = socket
        socket = null
        val api = BudsNative.ws2
        if (s != null && api != null) runCatching { api.closesocket(s) }
        if (holdsWinsock.compareAndSet(true, false)) runCatching { BudsWinsock.release() }
    }
}
