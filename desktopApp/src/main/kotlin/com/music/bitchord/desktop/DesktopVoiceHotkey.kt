package com.music.bitchord.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Voice commands, part V3: the global push-to-talk hotkey.
 *
 * `RegisterHotKey` on a dedicated thread with its own message loop. No keyboard hook (antivirus
 * programs dislike those). WM_HOTKEY means "pressed"; the key is then polled with GetAsyncKeyState
 * every 20 ms until it is up, which is "released". Windows only; everywhere else the class reports
 * UNAVAILABLE and does nothing. Nothing here sees, stores or logs what is typed or said.
 */

/** A hotkey: [modifiers] is a MOD_* bit mask (Windows values), [vk] a Windows virtual-key code. */
internal data class VoiceHotkey(val modifiers: Int, val vk: Int) {

    /** "Ctrl+Alt+Space". Modifiers always in the order Ctrl, Alt, Shift, Win. */
    fun describe(): String {
        val parts = ArrayList<String>(4)
        if (modifiers and MOD_CONTROL != 0) parts += "Ctrl"
        if (modifiers and MOD_ALT != 0) parts += "Alt"
        if (modifiers and MOD_SHIFT != 0) parts += "Shift"
        if (modifiers and MOD_WIN != 0) parts += "Win"
        parts += keyName(vk) ?: "0x" + vk.toString(16).uppercase()
        return parts.joinToString("+")
    }

    /** A usable hotkey needs at least one modifier and a known key. */
    val isValid: Boolean
        get() = modifiers and MOD_MASK != 0 && vk in 1..0xFE && keyName(vk) != null

    companion object {
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val MOD_SHIFT = 0x0004
        const val MOD_WIN = 0x0008
        const val MOD_MASK = MOD_ALT or MOD_CONTROL or MOD_SHIFT or MOD_WIN
        const val VK_SPACE = 0x20

        val DEFAULT = VoiceHotkey(MOD_CONTROL or MOD_ALT, VK_SPACE)

        private val NAMED_KEYS: Map<String, Int> = mapOf(
            "space" to 0x20, "enter" to 0x0D, "tab" to 0x09, "backspace" to 0x08,
            "pageup" to 0x21, "pagedown" to 0x22, "end" to 0x23, "home" to 0x24,
            "left" to 0x25, "up" to 0x26, "right" to 0x27, "down" to 0x28,
            "insert" to 0x2D, "delete" to 0x2E,
        )

        /** The key's display name for a virtual-key code, or null when it is not a supported key. */
        fun keyName(vk: Int): String? = when (vk) {
            in 0x30..0x39 -> ('0' + (vk - 0x30)).toString()
            in 0x41..0x5A -> ('A' + (vk - 0x41)).toString()
            in 0x70..0x87 -> "F" + (vk - 0x6F)
            else -> NAMED_KEYS.entries.firstOrNull { it.value == vk }
                ?.key?.replaceFirstChar { it.uppercase() }
        }

        private fun keyCode(token: String): Int? {
            val t = token.trim().lowercase()
            if (t.length == 1) {
                val c = t[0]
                if (c in 'a'..'z') return 0x41 + (c - 'a')
                if (c in '0'..'9') return 0x30 + (c - '0')
            }
            if (t.length in 2..3 && t[0] == 'f') {
                val n = t.substring(1).toIntOrNull()
                if (n != null && n in 1..24) return 0x6F + n
            }
            return NAMED_KEYS[t] ?: if (t == "spacebar") 0x20 else null
        }

        /**
         * Reads text such as "Ctrl+Alt+Space" (case-insensitive, spaces around "+" allowed). Returns
         * null for anything that is not a valid hotkey: no modifier, no key, two keys, unknown names.
         */
        fun parse(text: String): VoiceHotkey? {
            val tokens = text.split('+').map { it.trim() }
            if (tokens.size < 2 || tokens.any { it.isEmpty() }) return null
            var mods = 0
            var key: Int? = null
            for (token in tokens) {
                val bit = when (token.lowercase()) {
                    "ctrl", "control" -> MOD_CONTROL
                    "alt" -> MOD_ALT
                    "shift" -> MOD_SHIFT
                    "win", "windows", "meta", "super" -> MOD_WIN
                    else -> 0
                }
                if (bit != 0) {
                    mods = mods or bit
                } else {
                    if (key != null) return null
                    key = keyCode(token) ?: return null
                }
            }
            val k = key ?: return null
            if (mods == 0) return null
            return VoiceHotkey(mods, k)
        }
    }
}

/** Our own minimal user32 binding (same pattern as `WindowsProcessMemoryProbe`). */
internal interface VoiceUser32 : StdCallLibrary {
    fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean
    fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
    fun GetMessage(msg: Pointer, hWnd: Pointer?, wMsgFilterMin: Int, wMsgFilterMax: Int): Int
    fun PostThreadMessage(idThread: Int, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean
    fun GetAsyncKeyState(vKey: Int): Short
}

/** Loads user32 once, only on Windows. */
internal object VoiceUser32Loader {
    val api: VoiceUser32? by lazy {
        if (!DesktopPlatform.isWindows) {
            null
        } else {
            runCatching {
                Native.load("user32", VoiceUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }.getOrNull()
        }
    }
}

/** Polls a key without any hook. False everywhere but Windows. */
internal object DesktopVoiceKeys {
    const val VK_ESCAPE = 0x1B

    fun isDown(vk: Int): Boolean {
        val api = VoiceUser32Loader.api ?: return false
        return runCatching { (api.GetAsyncKeyState(vk).toInt() and 0x8000) != 0 }.getOrDefault(false)
    }
}

/**
 * Registers one global hotkey and reports press and release. [start] may be called again with
 * another combination (it stops the old thread first). Callbacks run on the hotkey thread: keep them
 * short (the controller just launches a coroutine).
 */
internal class DesktopVoiceHotkey(
    private val onPressed: () -> Unit,
    private val onReleased: () -> Unit,
) {

    enum class Status { STOPPED, ACTIVE, TAKEN, UNAVAILABLE }

    private val _status = MutableStateFlow(Status.STOPPED)
    val status: StateFlow<Status> = _status

    private val lock = Any()
    private var thread: Thread? = null

    @Volatile
    private var threadId: Int = 0

    @Volatile
    private var stopping = false

    fun start(hotkey: VoiceHotkey) {
        stop()
        val api = VoiceUser32Loader.api
        if (api == null || !hotkey.isValid) {
            _status.value = Status.UNAVAILABLE
            return
        }
        val ready = CountDownLatch(1)
        var registered = false
        stopping = false
        val worker = Thread({
            var registeredHere = false
            try {
                threadId = Kernel32.INSTANCE.GetCurrentThreadId()
                registeredHere = api.RegisterHotKey(null, HOTKEY_ID, hotkey.modifiers or MOD_NOREPEAT, hotkey.vk)
                registered = registeredHere
            } catch (t: Throwable) {
                registeredHere = false
            } finally {
                ready.countDown()
            }
            if (registeredHere) {
                try {
                    loop(api, hotkey)
                } finally {
                    runCatching { api.UnregisterHotKey(null, HOTKEY_ID) }
                }
            }
        }, "muses-voice-hotkey")
        worker.isDaemon = true
        synchronized(lock) { thread = worker }
        worker.start()
        ready.await(2, TimeUnit.SECONDS)
        if (registered) {
            _status.value = Status.ACTIVE
            DesktopTrackLog.log("voice: hotkey registered")
        } else {
            _status.value = Status.TAKEN
            DesktopTrackLog.log("voice: hotkey could not be registered (taken by another program?)")
        }
    }

    /** Unregisters the hotkey and ends the thread. Safe to call at any time, also twice. */
    fun stop() {
        val worker: Thread?
        val id: Int
        synchronized(lock) {
            worker = thread
            id = threadId
            thread = null
            threadId = 0
        }
        stopping = true
        val api = VoiceUser32Loader.api
        if (worker != null && worker.isAlive && api != null && id != 0) {
            runCatching { api.PostThreadMessage(id, WM_QUIT, null, null) }
            runCatching { worker.join(500) }
        }
        _status.value = Status.STOPPED
    }

    private fun loop(api: VoiceUser32, hotkey: VoiceHotkey) {
        val msg = Memory(MSG_BYTES.toLong())
        try {
            while (!stopping) {
                msg.clear()
                val got = api.GetMessage(msg, null, 0, 0)
                if (got <= 0) break
                // MSG: HWND hwnd (pointer sized), UINT message.
                val message = msg.getInt(Native.POINTER_SIZE.toLong())
                if (message == WM_HOTKEY) holdCycle(api, hotkey)
            }
        } finally {
            msg.close()
        }
    }

    private fun holdCycle(api: VoiceUser32, hotkey: VoiceHotkey) {
        runCatching { onPressed() }
        val started = System.nanoTime()
        while (!stopping && isDown(api, hotkey.vk) &&
            System.nanoTime() - started < MAX_HOLD_NANOS
        ) {
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                break
            }
        }
        runCatching { onReleased() }
    }

    private fun isDown(api: VoiceUser32, vk: Int): Boolean =
        runCatching { (api.GetAsyncKeyState(vk).toInt() and 0x8000) != 0 }.getOrDefault(false)

    private companion object {
        const val HOTKEY_ID = 0x4D55 // "MU"
        const val MOD_NOREPEAT = 0x4000
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
        const val MSG_BYTES = 64
        const val POLL_MILLIS = 20L
        const val MAX_HOLD_NANOS = 20_000_000_000L
    }
}
