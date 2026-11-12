package com.music.bitchord.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/*
 * Muses Hub memory (H4): reads working-set memory and decides what to do about it.
 *
 * Three parts:
 *  1. [MemoryReader]: "how many MB do these PIDs use". [WindowsMemoryReader] is the only class here
 *     that touches Windows/JNA. Everywhere else [UnsupportedMemoryReader] returns 0.
 *  2. [HubMemorySampler]: total = Muses' own memory + the browser process tree (one call, see below).
 *  3. [MemoryGovernor]: pure policy from HUB_PLAN section 3 (warn / close least-recently-used / never
 *     touch the active service), with hysteresis.
 *
 * SHARED TREE (from H3): all live services share ONE browser process tree, so web memory is a single
 * number: feed `launcher.profileTreePids()` to the sampler ONCE. Never sum `treePids(service)`.
 * Because of that, the governor can only target one service when the launcher can close a single
 * window (`canCloseSingleWindow`). Otherwise it can only warn.
 */

/** Reads memory use of processes. Never throws; unreadable processes count as 0. */
internal interface MemoryReader {
    /** Sum of the working sets of [pids] in MB (1 MB = 1 MiB = 1,048,576 bytes), each PID counted once. */
    fun workingSetMb(pids: List<Long>): Long

    /** False when this reader cannot measure anything (non-Windows): every answer is then 0. */
    val supported: Boolean
        get() = true
}

/** Non-Windows reader. Always returns 0 and says so through [supported]. */
internal object UnsupportedMemoryReader : MemoryReader {
    override fun workingSetMb(pids: List<Long>): Long = 0L

    override val supported: Boolean
        get() = false
}

internal object MemoryReaders {
    fun forSystem(): MemoryReader =
        if (DesktopPlatform.isWindows) WindowsMemoryReader() else UnsupportedMemoryReader
}

internal object HubMemoryMath {
    const val BYTES_PER_MB: Long = 1024L * 1024L

    fun bytesToMb(bytes: Long): Long = if (bytes <= 0L) 0L else bytes / BYTES_PER_MB
}

/**
 * Windows reader: `GetProcessMemoryInfo` from psapi.dll, field `WorkingSetSize`.
 *
 * JNA 5.19.1 (jna-platform) has no binding for this call (checked in its Psapi/Kernel32 sources), so a
 * tiny binding is declared here. The PROCESS_MEMORY_COUNTERS struct is read from a raw [Memory]
 * block instead of a JNA Structure subclass, to avoid Kotlin/JNA field-reflection pitfalls:
 *   DWORD cb; DWORD PageFaultCount; SIZE_T PeakWorkingSetSize; SIZE_T WorkingSetSize; ... (8 SIZE_T in all).
 * SIZE_T is pointer sized, so offsets and total size use `Native.POINTER_SIZE` (works on 32 and 64 bit).
 *
 * Caveat: a working set includes pages shared between processes (Chromium processes share a lot), so
 * the sum can be higher than Task Manager's "Memory" column. That errs on the side of closing early.
 */
internal class WindowsMemoryReader : MemoryReader {
    /** The psapi function we need. Public JVM-level interface on purpose (JNA proxies it). */
    internal interface ProcessMemoryApi : StdCallLibrary {
        fun GetProcessMemoryInfo(process: WinNT.HANDLE, counters: Pointer, cb: Int): Boolean
    }

    /** How many PIDs could not be read in the last call (exited, or access denied). Diagnostics only. */
    @Volatile
    var lastUnreadablePids: Int = 0
        private set

    override val supported: Boolean
        get() = DesktopPlatform.isWindows

    private val api: ProcessMemoryApi? by lazy {
        if (!DesktopPlatform.isWindows) {
            null
        } else {
            runCatching {
                Native.load("psapi", ProcessMemoryApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }.getOrNull()
        }
    }

    override fun workingSetMb(pids: List<Long>): Long {
        if (!DesktopPlatform.isWindows || pids.isEmpty()) return 0L
        val psapi = api
        if (psapi == null) {
            lastUnreadablePids = pids.distinct().size
            return 0L
        }
        var bytes = 0L
        var unreadable = 0
        runCatching {
            Memory(COUNTERS_BYTES.toLong()).use { buffer ->
                for (pid in pids.distinct()) {
                    val ws = readOne(psapi, pid, buffer)
                    if (ws == null) unreadable++ else bytes += ws
                }
            }
        }.onFailure { unreadable = pids.distinct().size; bytes = 0L }
        lastUnreadablePids = unreadable
        return HubMemoryMath.bytesToMb(bytes)
    }

    private fun readOne(psapi: ProcessMemoryApi, pid: Long, buffer: Memory): Long? {
        if (pid <= 0L || pid > Int.MAX_VALUE.toLong()) return null
        return runCatching {
            val handle: WinNT.HANDLE? =
                Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt())
            if (handle == null) {
                null
            } else {
                try {
                    buffer.clear()
                    buffer.setInt(0, COUNTERS_BYTES)
                    if (psapi.GetProcessMemoryInfo(handle, buffer, COUNTERS_BYTES)) {
                        if (Native.POINTER_SIZE == 8) {
                            buffer.getLong(WORKING_SET_OFFSET.toLong())
                        } else {
                            buffer.getInt(WORKING_SET_OFFSET.toLong()).toLong() and 0xFFFFFFFFL
                        }
                    } else {
                        null
                    }
                } finally {
                    Kernel32.INSTANCE.CloseHandle(handle)
                }
            }
        }.getOrNull()
    }

    private companion object {
        /** sizeof(PROCESS_MEMORY_COUNTERS): 2 DWORD + 8 SIZE_T (72 bytes on 64 bit, 40 on 32 bit). */
        val COUNTERS_BYTES: Int
            get() = 8 + 8 * Native.POINTER_SIZE

        /** Offset of WorkingSetSize: after cb, PageFaultCount and PeakWorkingSetSize. */
        val WORKING_SET_OFFSET: Int
            get() = 8 + Native.POINTER_SIZE
    }
}

/** One measurement: Muses itself plus the web (browser) side. */
internal data class HubMemorySample(
    val musesMb: Long,
    val webMb: Long,
    /** False when the reader cannot measure (non-Windows); the figures are then not real. */
    val measured: Boolean,
) {
    val totalMb: Long
        get() = musesMb + webMb
}

/**
 * Builds a [HubMemorySample].
 *
 * Muses' own figure = working set of the Muses process (same reader, own PID). If that reads 0
 * (non-Windows, or the call failed) it falls back to the JVM's used heap (`Runtime`), which
 * UNDER-counts (no native/GPU memory) but is better than 0.
 */
internal class HubMemorySampler(
    private val reader: MemoryReader,
    private val ownPid: Long = ProcessHandle.current().pid(),
    private val jvmHeapMb: () -> Long = {
        val rt = Runtime.getRuntime()
        HubMemoryMath.bytesToMb(rt.totalMemory() - rt.freeMemory())
    },
) {
    /** [webPids] = `launcher.profileTreePids()`, passed once. Muses' own PID is removed if present. */
    fun sample(webPids: List<Long>): HubMemorySample {
        val own = reader.workingSetMb(listOf(ownPid))
        val musesMb = if (own > 0L) own else jvmHeapMb().coerceAtLeast(0L)
        val web = webPids.filter { it != ownPid }
        val webMb = if (web.isEmpty()) 0L else reader.workingSetMb(web)
        return HubMemorySample(musesMb = musesMb, webMb = webMb, measured = reader.supported)
    }
}

/** A service as the governor sees it. [lastActiveMillis] = when it was last the active service. */
internal data class HubGovernorService(
    val id: String,
    val state: HubServiceState,
    val lastActiveMillis: Long,
)

internal sealed interface MemoryAction {
    /** Nothing to do. */
    data object None : MemoryAction

    /** Show a warning (sent once each time the level rises, not on every poll). */
    data object Warn : MemoryAction

    /** Close this non-active service. Only produced when a single window can be closed. */
    data class Hibernate(val id: String) : MemoryAction
}

internal enum class MemoryLevel { NORMAL, WARNING, OVER_CAP }

/**
 * Memory policy (HUB_PLAN section 3):
 *  - total > warn: [MemoryAction.Warn] (once when the level rises).
 *  - total > cap: also close the least-recently-used non-active LIVE service
 *    ([MemoryAction.Hibernate]); one per decision, then a settle period so the browser can release
 *    memory before the next one is judged.
 *  - If nothing can be closed (only the active service is open, or single-window close is not possible)
 *    only the warning is given. The ACTIVE service and HIBERNATED services are never chosen.
 *
 * Hysteresis: thresholds are strict (`>`), and a level is left only after the total falls
 * [margin] below its threshold (5% of the threshold, at least 32 MB: 80 MB at 1600, 102 MB at 2048).
 * So a total hovering around a limit gives one warning, not a stream. Hibernating itself needs
 * `total > cap` (never acts below the cap).
 *
 * Holds a little state (level, last hibernate time). Call [reset] when polling stops.
 * Time is passed in so tests stay deterministic.
 */
internal class MemoryGovernor(
    private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
) {
    @Volatile
    var level: MemoryLevel = MemoryLevel.NORMAL
        private set

    private var lastHibernateAtMillis: Long? = null

    @Synchronized
    fun decide(
        totalMb: Long,
        services: List<HubGovernorService>,
        settings: HubSettings,
        nowMillis: Long = System.currentTimeMillis(),
        /** Pass `launcher.canCloseSingleWindow`. False (default) means "never produce Hibernate". */
        canCloseSingleWindow: Boolean = false,
    ): List<MemoryAction> {
        val s = settings.validated()
        val warn = s.memoryWarnMb.toLong()
        val cap = s.memoryCapMb.toLong()
        val previous = level
        val next = nextLevel(previous, totalMb, warn, cap)
        level = next

        val actions = ArrayList<MemoryAction>(2)
        if (next.ordinal > previous.ordinal) actions += MemoryAction.Warn
        if (totalMb > cap && canCloseSingleWindow && !isSettling(nowMillis)) {
            val victim = leastRecentlyUsedLive(services)
            if (victim != null) {
                actions += MemoryAction.Hibernate(victim.id)
                lastHibernateAtMillis = nowMillis
            }
        }
        return if (actions.isEmpty()) listOf(MemoryAction.None) else actions
    }

    /** Forget all state (no live services / polling stopped). */
    @Synchronized
    fun reset() {
        level = MemoryLevel.NORMAL
        lastHibernateAtMillis = null
    }

    private fun isSettling(nowMillis: Long): Boolean {
        val last = lastHibernateAtMillis ?: return false
        val elapsed = nowMillis - last
        // A clock that moved backwards (elapsed < 0) does not block forever.
        return elapsed in 0 until settleMillis
    }

    /** Oldest `lastActiveMillis` among LIVE services; ties go to the earlier entry in the list. */
    private fun leastRecentlyUsedLive(services: List<HubGovernorService>): HubGovernorService? =
        services.filter { it.state == HubServiceState.LIVE }.minByOrNull { it.lastActiveMillis }

    private fun nextLevel(previous: MemoryLevel, total: Long, warn: Long, cap: Long): MemoryLevel {
        if (total > cap) return MemoryLevel.OVER_CAP
        return when (previous) {
            MemoryLevel.NORMAL ->
                if (total > warn) MemoryLevel.WARNING else MemoryLevel.NORMAL

            MemoryLevel.WARNING ->
                if (total <= warn - margin(warn)) MemoryLevel.NORMAL else MemoryLevel.WARNING

            MemoryLevel.OVER_CAP ->
                when {
                    total > cap - margin(cap) -> MemoryLevel.OVER_CAP
                    total <= warn - margin(warn) -> MemoryLevel.NORMAL
                    else -> MemoryLevel.WARNING
                }
        }
    }

    private fun margin(threshold: Long): Long = (threshold / 20L).coerceAtLeast(MIN_MARGIN_MB)

    companion object {
        const val DEFAULT_SETTLE_MILLIS: Long = 10_000L
        const val MIN_MARGIN_MB: Long = 32L
    }
}
