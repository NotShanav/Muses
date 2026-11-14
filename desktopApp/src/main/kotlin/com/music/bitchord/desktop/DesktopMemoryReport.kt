package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.management.ObjectName

/*
 * UPGRADE M3: memory report, a diagnostic tool. It changes no behaviour; it writes a text file that says
 * where the memory of this process goes (Java heap, non-heap pools, buffers, threads, native memory
 * tracking, working set and commit charge). Everything here is wrapped so it can never throw to the UI.
 * Remove the NativeMemoryTracking flag in desktopApp/build.gradle.kts when the investigation is over.
 */

/** Pure helpers: formatting and naming, so they can be tested. */
internal object MemoryReportFormat {

    /** Bytes as MB (MiB) with one decimal; a negative size (an undefined maximum) reads "n/a". */
    fun mb(bytes: Long): String {
        if (bytes < 0L) return "n/a"
        val tenths = (bytes * 10L) / ProbeMemoryMath.BYTES_PER_MB
        return "${tenths / 10L}.${tenths % 10L} MB"
    }

    /** A thread name with every run of digits as `#`, so "pool-3-thread-12" and "pool-4-thread-1" are one group. */
    fun threadGroup(name: String): String = name.replace(Regex("\\d+"), "#")

    /** Thread names grouped by [threadGroup], biggest group first, ties alphabetical. */
    fun threadGroups(names: List<String>): List<Pair<String, Int>> =
        names.groupingBy { threadGroup(it) }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /** A label that is safe in a file name: letters, digits, `-` and `_`; anything else becomes `_`. Never empty. */
    fun safeLabel(label: String): String =
        label.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifEmpty { "report" }

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** `memory-<yyyyMMdd-HHmmss>-<label>.txt` */
    fun fileName(label: String, time: LocalDateTime): String = "memory-${time.format(stamp)}-${safeLabel(label)}.txt"
}

internal object DesktopMemoryReport {

    private val startedAt = System.nanoTime()

    private val _lastPath = MutableStateFlow<String?>(null)

    /** Where the latest report went (or a short failure note), for the settings row. */
    val lastPath: StateFlow<String?> = _lastPath

    /** `$XDG_DATA_HOME/bitchord/memory-reports`, or the usual default under $HOME (same rule as the analysis home). */
    private fun directory(): Path {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.home")}/.local/share"
        return Path.of(base, "bitchord", "memory-reports")
    }

    private inline fun section(out: StringBuilder, title: String, block: StringBuilder.() -> Unit) {
        out.append("\n== ").append(title).append(" ==\n")
        runCatching { out.block() }.onFailure { out.append("(failed: ").append(it.toString()).append(")\n") }
    }

    /** The whole report as text. Blocking and a little heavy: call it off the UI thread. Never throws. */
    fun snapshot(label: String): String {
        val out = StringBuilder()
        runCatching {
            section(out, "Report") {
                append("label: ").append(label).append('\n')
                append("time: ").append(LocalDateTime.now()).append('\n')
                append("uptime: ").append((System.nanoTime() - startedAt) / 1_000_000_000L).append(" s since this object loaded\n")
                append("window visible: ").append(DesktopWindowVisibility.visible.value).append('\n')
                append("java: ").append(System.getProperty("java.version")).append(" / ").append(System.getProperty("os.name")).append('\n')
                append("jvm args: ").append(ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ")).append('\n')
            }
            section(out, "Process (OS view)") {
                val probe = ProcessMemoryProbes.forSystem()
                val pid = ProcessHandle.current().pid()
                append("pid: ").append(pid).append('\n')
                if (probe.supported) {
                    append("working set: ").append(probe.workingSetMb(listOf(pid))).append(" MB\n")
                    append("commit charge: ").append(probe.commitMb(pid)).append(" MB\n")
                } else {
                    append("working set and commit charge: not supported on this OS, reported as 0\n")
                }
            }
            section(out, "Java heap and non-heap") {
                val memory = ManagementFactory.getMemoryMXBean()
                val heap = memory.heapMemoryUsage
                val nonHeap = memory.nonHeapMemoryUsage
                append("heap: used ").append(MemoryReportFormat.mb(heap.used))
                    .append(", committed ").append(MemoryReportFormat.mb(heap.committed))
                    .append(", max ").append(MemoryReportFormat.mb(heap.max)).append('\n')
                append("non-heap: used ").append(MemoryReportFormat.mb(nonHeap.used))
                    .append(", committed ").append(MemoryReportFormat.mb(nonHeap.committed)).append('\n')
            }
            section(out, "Memory pools") {
                for (pool in ManagementFactory.getMemoryPoolMXBeans()) {
                    val usage = pool.usage
                    append(pool.name).append(" (").append(pool.type).append("): used ")
                        .append(MemoryReportFormat.mb(usage.used))
                        .append(", committed ").append(MemoryReportFormat.mb(usage.committed))
                        .append(", max ").append(MemoryReportFormat.mb(usage.max)).append('\n')
                }
            }
            section(out, "Buffer pools") {
                for (pool in ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)) {
                    append(pool.name).append(": count ").append(pool.count)
                        .append(", used ").append(MemoryReportFormat.mb(pool.memoryUsed))
                        .append(", capacity ").append(MemoryReportFormat.mb(pool.totalCapacity)).append('\n')
                }
            }
            section(out, "Threads") {
                val names = Thread.getAllStackTraces().keys.map { it.name }
                append("thread count: ").append(ManagementFactory.getThreadMXBean().threadCount)
                    .append(" (peak ").append(ManagementFactory.getThreadMXBean().peakThreadCount).append(")\n")
                for ((group, count) in MemoryReportFormat.threadGroups(names).take(80)) {
                    append(count).append("  ").append(group).append('\n')
                }
            }
            section(out, "Classes and GC") {
                val classes = ManagementFactory.getClassLoadingMXBean()
                append("loaded classes: ").append(classes.loadedClassCount)
                    .append(" (total ").append(classes.totalLoadedClassCount)
                    .append(", unloaded ").append(classes.unloadedClassCount).append(")\n")
                for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
                    append(gc.name).append(": ").append(gc.collectionCount).append(" collections, ")
                        .append(gc.collectionTime).append(" ms\n")
                }
            }
            section(out, "Caches") {
                // DesktopArtworkCache keeps its size private and the fluid palette cache is private to
                // DesktopFluidity.kt; this diagnostic does not widen either. The artwork budget is exposed.
                append("artwork cache budget: ").append(MemoryReportFormat.mb(DesktopArtworkCache.budgetBytes)).append('\n')
            }
            section(out, "Native memory tracking (summary)") {
                val result = runCatching {
                    ManagementFactory.getPlatformMBeanServer().invoke(
                        ObjectName("com.sun.management:type=DiagnosticCommand"),
                        "vmNativeMemory",
                        arrayOf<Any>(arrayOf("summary")),
                        arrayOf("[Ljava.lang.String;"),
                    )
                }
                result.onSuccess { append(it?.toString() ?: "(no output)").append('\n') }
                result.onFailure { append("not available: ").append(it.toString()).append('\n') }
            }
        }.onFailure { out.append("\n(report stopped early: ").append(it.toString()).append(")\n") }
        return out.toString()
    }

    /** Collects a report on Dispatchers.IO and writes it; returns the path, or null if it could not be written. */
    suspend fun write(label: String): String? {
        val path = withContext(Dispatchers.IO) {
            runCatching {
                val text = snapshot(label)
                val folder = directory()
                Files.createDirectories(folder)
                val file = folder.resolve(MemoryReportFormat.fileName(label, LocalDateTime.now()))
                Files.writeString(file, text)
                file.toString()
            }.getOrNull()
        }
        _lastPath.value = path ?: "could not write the report"
        return path
    }
}

/** The switch (persisted, default off) and the automatic schedule for the diagnostic reports. */
internal object DesktopMemoryReportSettings {

    private const val KEY_COLLECT = "memory_report_collect"

    private val _enabled = MutableStateFlow(DesktopPersistence().boolean(KEY_COLLECT, false))

    val enabled: StateFlow<Boolean> = _enabled

    fun setEnabled(value: Boolean) {
        runCatching { DesktopPersistence().saveBoolean(KEY_COLLECT, value) }
        _enabled.value = value
    }

    private const val STARTUP_DELAY_MS = 90_000L
    private const val TRAY_DELAY_MS = 60_000L

    /**
     * Runs for the life of the application (from a LaunchedEffect in Main.kt). With the switch on it writes
     * `startup-90s` once, 90 seconds after start, and `tray-60s` once for each hide to the tray, 60 seconds
     * after the window went away; showing the window again cancels a pending one.
     */
    suspend fun runSchedule() {
        try {
            coroutineScope {
                launch {
                    delay(STARTUP_DELAY_MS)
                    if (_enabled.value) DesktopMemoryReport.write("startup-90s")
                }
                var pending: Job? = null
                DesktopWindowVisibility.visible.collect { visible ->
                    pending?.cancel()
                    pending = if (!visible) {
                        launch {
                            delay(TRAY_DELAY_MS)
                            if (_enabled.value && !DesktopWindowVisibility.visible.value) DesktopMemoryReport.write("tray-60s")
                        }
                    } else {
                        null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A diagnostic must never take the app down.
        }
    }
}

/**
 * The settings rows: the switch (drawn by [toggle], which the settings page passes in so the existing row
 * composable is reused) and a button that writes a report now, with the saved path under it.
 */
@Composable
internal fun DesktopMemoryReportSettingsRows(
    toggle: @Composable (title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) -> Unit,
) {
    val enabled by DesktopMemoryReportSettings.enabled.collectAsState()
    val lastPath by DesktopMemoryReport.lastPath.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    toggle(
        "Collect memory reports (diagnostic)",
        "Writes a memory report 90 seconds after start and 60 seconds after the window is hidden to the tray",
        enabled,
        DesktopMemoryReportSettings::setEnabled,
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        DesktopMemoryReport.write("manual")
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("Write memory report now") }
        lastPath?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
