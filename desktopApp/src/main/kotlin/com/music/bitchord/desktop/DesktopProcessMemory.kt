package com.music.bitchord.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/*
 * Process memory probe (Muses upgrade M3). Reads the working set and commit charge of processes with
 * psapi `GetProcessMemoryInfo`. Used by the memory report; it changes no behaviour. Copied in spirit
 * from a probe that already ran in a previous build of this project, with the Hub parts removed.
 * Windows only: everywhere else [UnsupportedProcessMemoryProbe] answers 0.
 */

/** Reads memory use of processes. Never throws; unreadable processes count as 0. */
internal interface ProcessMemoryProbe {
    /** Sum of the working sets of [pids] in MB (1 MB = 1 MiB = 1,048,576 bytes), each PID counted once. */
    fun workingSetMb(pids: List<Long>): Long

    /** Commit charge (private pagefile-backed memory) of [pid] in MB, 0 when unreadable. */
    fun commitMb(pid: Long): Long = 0L

    /** False when this reader cannot measure anything (non-Windows): every answer is then 0. */
    val supported: Boolean
        get() = true
}

/** Non-Windows reader. Always returns 0 and says so through [supported]. */
internal object UnsupportedProcessMemoryProbe : ProcessMemoryProbe {
    override fun workingSetMb(pids: List<Long>): Long = 0L

    override val supported: Boolean
        get() = false
}

internal object ProcessMemoryProbes {
    fun forSystem(): ProcessMemoryProbe =
        if (DesktopPlatform.isWindows) WindowsProcessMemoryProbe() else UnsupportedProcessMemoryProbe
}

internal object ProbeMemoryMath {
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
internal class WindowsProcessMemoryProbe : ProcessMemoryProbe {
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
        return ProbeMemoryMath.bytesToMb(bytes)
    }

    private fun readOne(psapi: ProcessMemoryApi, pid: Long, buffer: Memory, offset: Int = WORKING_SET_OFFSET): Long? {
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
                            buffer.getLong(offset.toLong())
                        } else {
                            buffer.getInt(offset.toLong()).toLong() and 0xFFFFFFFFL
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

    /** PagefileUsage = commit charge; field index 6 of the 8 SIZE_T fields after cb and PageFaultCount. */
    override fun commitMb(pid: Long): Long {
        if (!DesktopPlatform.isWindows) return 0L
        val psapi = api ?: return 0L
        return runCatching {
            Memory(COUNTERS_BYTES.toLong()).use { buffer ->
                ProbeMemoryMath.bytesToMb(readOne(psapi, pid, buffer, COMMIT_OFFSET) ?: 0L)
            }
        }.getOrDefault(0L)
    }

    private companion object {
        /** Offset of PagefileUsage: after cb, PageFaultCount and six SIZE_T fields. */
        val COMMIT_OFFSET: Int
            get() = 8 + 6 * Native.POINTER_SIZE

        /** sizeof(PROCESS_MEMORY_COUNTERS): 2 DWORD + 8 SIZE_T (72 bytes on 64 bit, 40 on 32 bit). */
        val COUNTERS_BYTES: Int
            get() = 8 + 8 * Native.POINTER_SIZE

        /** Offset of WorkingSetSize: after cb, PageFaultCount and PeakWorkingSetSize. */
        val WORKING_SET_OFFSET: Int
            get() = 8 + Native.POINTER_SIZE
    }
}
