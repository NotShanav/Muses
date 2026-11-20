package com.music.bitchord.desktop

import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// AUDIO FIX: the small, pure pieces of the freeze work (retry delay, freeze detection, thread-dump
// text) and the watchdog thread that uses them. Nothing here touches audio hardware or a window
// except [DesktopFreezeWatchdog], which only posts an empty task to the AWT event queue.

/** How long a failed device-list refresh waits before its next try. */
internal object AudioRetryPolicy {
    /** Attempts after the first one before the last good list is simply kept until the next change. */
    const val MAX_RETRIES = 4

    /** Delay before retry number [failures] (1 based): 250, 500, 1000, then 2000 ms. */
    fun delayMs(failures: Int): Long = 250L shl (failures - 1).coerceIn(0, 3)
}

internal enum class FreezeEvent { NONE, FROZE, RECOVERED }

/**
 * Decides when the UI thread counts as frozen and reports each freeze exactly once.
 *
 * [onCheck] gets how long the oldest unanswered probe has been waiting (null when none is). The
 * first check at or above [limitMs] reports [FreezeEvent.FROZE]; further checks of the same freeze
 * report nothing; the first check after it answers reports [FreezeEvent.RECOVERED], and
 * [peakMs] then holds the longest wait seen.
 */
internal class UiFreezeTracker(private val limitMs: Long = 4_000L) {
    private var reported = false

    var peakMs: Long = 0L
        private set

    fun onCheck(stalledForMs: Long?): FreezeEvent {
        if (stalledForMs != null && stalledForMs >= limitMs) {
            peakMs = maxOf(peakMs, stalledForMs)
            if (reported) return FreezeEvent.NONE
            reported = true
            return FreezeEvent.FROZE
        }
        if (!reported) return FreezeEvent.NONE
        reported = false
        return FreezeEvent.RECOVERED
    }

    fun resetPeak() {
        peakMs = 0L
    }
}

/** Thread names, states and the top [maxFrames] frames of each stack; no arguments, no values. */
internal fun formatThreadDump(
    traces: Map<Thread, Array<StackTraceElement>>,
    maxFrames: Int = 40,
): String = buildString {
    traces.entries.sortedBy { it.key.name }.forEach { (thread, frames) ->
        append("\"").append(thread.name).append("\" ")
        append(if (thread.isDaemon) "daemon " else "").append(thread.state).append('\n')
        frames.take(maxFrames).forEach { append("    at ").append(it).append('\n') }
        if (frames.size > maxFrames) append("    ... ").append(frames.size - maxFrames).append(" more\n")
    }
}

/**
 * Daemon thread that checks once a second that the UI thread still runs tasks. A probe posted to
 * the AWT event queue that has not run after 4 s means the UI is frozen: every thread's stack goes
 * into desktop.log once, under a heading, and the native audio trace is written. It never blocks
 * and never touches the UI itself, and it waits 10 s at start so a slow launch is not reported.
 */
internal object DesktopFreezeWatchdog {

    private val started = AtomicBoolean(false)
    private const val START_GRACE_MS = 10_000L
    private const val CHECK_EVERY_MS = 1_000L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread(::watch, "Muses-FreezeWatchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun watch() {
        val tracker = UiFreezeTracker()
        // Nanotime a probe was posted at; 0 while none is waiting. The probe itself resets it.
        val outstandingSince = AtomicLong(0L)
        runCatching { Thread.sleep(START_GRACE_MS) }
        while (true) {
            runCatching {
                if (outstandingSince.get() == 0L) {
                    outstandingSince.set(System.nanoTime().coerceAtLeast(1L))
                    EventQueue.invokeLater { outstandingSince.set(0L) }
                }
                val since = outstandingSince.get()
                val stalledMs = if (since == 0L) null else (System.nanoTime() - since) / 1_000_000L
                when (tracker.onCheck(stalledMs)) {
                    FreezeEvent.FROZE -> reportFreeze(stalledMs ?: 0L)
                    FreezeEvent.RECOVERED -> {
                        DesktopTrackLog.log("UI responsive again after about ${tracker.peakMs} ms")
                        tracker.resetPeak()
                    }
                    FreezeEvent.NONE -> Unit
                }
            }
            try {
                Thread.sleep(CHECK_EVERY_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun reportFreeze(stalledMs: Long) {
        DesktopWindowsAudio.flushTrace()
        val dump = runCatching { formatThreadDump(Thread.getAllStackTraces()) }
            .getOrElse { "thread dump failed: ${it.javaClass.simpleName}" }
        DesktopTrackLog.log(
            "=== UI FREEZE: the UI thread has not run a task for $stalledMs ms. " +
                "Thread dump follows (see also audio-trace.log) ===\n$dump=== end of thread dump ===",
        )
    }
}
