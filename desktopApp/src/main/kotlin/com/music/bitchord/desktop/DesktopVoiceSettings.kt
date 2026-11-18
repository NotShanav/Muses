package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * Voice commands, part V4: the persisted settings and the install state.
 *
 * Only settings are saved here (a switch, a key combination and two paths). Nothing the owner says,
 * and nothing that was recognised, is ever written by this file or by the page that uses it.
 */

/**
 * The voice settings. `enabled` is false until the owner turns it on. Setting `enabled` or `hotkey`
 * tells the running controller to apply it (register or unregister the hotkey).
 */
internal object DesktopVoiceSettings {

    private val persistence = DesktopPersistence()

    private val _enabled = MutableStateFlow(persistence.boolean(KEY_ENABLED, false))
    private val _hotkey = MutableStateFlow(
        VoiceHotkey.parse(persistence.string(KEY_HOTKEY, ""))?.takeIf { it.isValid } ?: VoiceHotkey.DEFAULT,
    )
    private val _modelDir = MutableStateFlow(persistence.string(KEY_MODEL_DIR, "").ifBlank { null })
    private val _sidecarExe = MutableStateFlow(persistence.string(KEY_SIDECAR_EXE, "").ifBlank { null })
    private val _confirmBeforePlay = MutableStateFlow(persistence.boolean(KEY_CONFIRM, true))

    val enabled: StateFlow<Boolean> = _enabled
    val hotkey: StateFlow<VoiceHotkey> = _hotkey
    val modelDir: StateFlow<String?> = _modelDir
    val sidecarExe: StateFlow<String?> = _sidecarExe
    val confirmBeforePlay: StateFlow<Boolean> = _confirmBeforePlay

    fun setEnabled(value: Boolean) {
        persistence.saveBoolean(KEY_ENABLED, value)
        _enabled.value = value
        apply()
    }

    fun setHotkey(value: VoiceHotkey) {
        if (!value.isValid) return
        persistence.saveString(KEY_HOTKEY, value.describe())
        _hotkey.value = value
        apply()
    }

    fun setModelDir(value: String?) {
        persistence.saveString(KEY_MODEL_DIR, value.orEmpty())
        _modelDir.value = value?.ifBlank { null }
    }

    fun setSidecarExe(value: String?) {
        persistence.saveString(KEY_SIDECAR_EXE, value.orEmpty())
        _sidecarExe.value = value?.ifBlank { null }
    }

    fun setConfirmBeforePlay(value: Boolean) {
        persistence.saveBoolean(KEY_CONFIRM, value)
        _confirmBeforePlay.value = value
    }

    /** Tells the controller (if the app has built it yet) to re-read `enabled` and `hotkey`. */
    private fun apply() {
        runCatching { DesktopVoiceRuntime.controller.value?.refresh() }
    }

    private const val KEY_ENABLED = "voice_enabled"
    private const val KEY_HOTKEY = "voice_hotkey"
    private const val KEY_MODEL_DIR = "voice_model_dir"
    private const val KEY_SIDECAR_EXE = "voice_sidecar_exe"
    private const val KEY_CONFIRM = "voice_confirm_before_play"
}

/** Where a one-time download stands. Never holds any recognised text. */
internal sealed interface VoiceInstallState {
    object Idle : VoiceInstallState
    data class Running(val progress: Float) : VoiceInstallState
    object Done : VoiceInstallState
    data class Failed(val code: String) : VoiceInstallState
}

/**
 * The two downloads the owner can start. They run in this object's own scope, so leaving the
 * Settings page does not stop them; Cancel does. A finished download fills in the matching setting.
 */
internal object DesktopVoiceInstall {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _model = MutableStateFlow<VoiceInstallState>(VoiceInstallState.Idle)
    private val _program = MutableStateFlow<VoiceInstallState>(VoiceInstallState.Idle)
    val model: StateFlow<VoiceInstallState> = _model
    val program: StateFlow<VoiceInstallState> = _program

    private var modelJob: Job? = null
    private var programJob: Job? = null

    @Synchronized
    fun downloadModel() {
        if (_model.value is VoiceInstallState.Running) return
        _model.value = VoiceInstallState.Running(0f)
        modelJob = scope.launch {
            try {
                val result = DesktopVoiceModelDownloader.download(DesktopVoicePaths.modelDir()) { p ->
                    if (_model.value is VoiceInstallState.Running) _model.value = VoiceInstallState.Running(p)
                }
                val files = result.getOrNull()
                if (files != null) {
                    DesktopVoiceSettings.setModelDir(files.dir.toString())
                    _model.value = VoiceInstallState.Done
                } else {
                    val error = result.exceptionOrNull()
                    _model.value = if (error is CancellationException) {
                        VoiceInstallState.Idle
                    } else {
                        VoiceInstallState.Failed(VoiceSettingsLogic.codeOf(error))
                    }
                }
            } catch (e: CancellationException) {
                _model.value = VoiceInstallState.Idle
                throw e
            }
        }
    }

    @Synchronized
    fun cancelModel() {
        modelJob?.cancel()
        _model.value = VoiceInstallState.Idle
    }

    @Synchronized
    fun downloadProgram() {
        if (_program.value is VoiceInstallState.Running) return
        _program.value = VoiceInstallState.Running(0f)
        programJob = scope.launch {
            try {
                val result = DesktopVoiceSidecar.download { p ->
                    if (_program.value is VoiceInstallState.Running) _program.value = VoiceInstallState.Running(p)
                }
                val exe = result.getOrNull()
                if (exe != null) {
                    DesktopVoiceSettings.setSidecarExe(exe.toString())
                    _program.value = VoiceInstallState.Done
                } else {
                    val error = result.exceptionOrNull()
                    _program.value = if (error is CancellationException) {
                        VoiceInstallState.Idle
                    } else {
                        VoiceInstallState.Failed(VoiceSettingsLogic.codeOf(error))
                    }
                }
            } catch (e: CancellationException) {
                _program.value = VoiceInstallState.Idle
                throw e
            }
        }
    }

    @Synchronized
    fun cancelProgram() {
        programJob?.cancel()
        _program.value = VoiceInstallState.Idle
    }
}

/** What the page says about the model folder. */
internal data class VoiceModelStatus(
    val kind: Kind,
    val dir: Path?,
    val detail: String?,
    val files: VoiceModelFiles?,
) {
    enum class Kind { NOT_INSTALLED, READY, WRONG_FORMAT }
}

/** Pure helpers for the Settings page. No UI, no network, no microphone. */
internal object VoiceSettingsLogic {

    /**
     * Looks at the chosen folder first, then the folder this app downloads into. A complete model
     * anywhere wins; otherwise the first wrong-format folder is reported; otherwise not installed.
     */
    fun modelStatus(
        chosen: String?,
        managed: Path,
        check: (Path) -> VoiceModelScan = { DesktopVoiceModelLocator.check(it) },
    ): VoiceModelStatus {
        val dirs = ArrayList<Path>(2)
        val chosenPath = chosen?.takeIf { it.isNotBlank() }?.let { runCatching { Paths.get(it) }.getOrNull() }
        if (chosenPath != null) dirs += chosenPath
        if (chosenPath == null || !sameFolder(chosenPath, managed)) dirs += managed
        var wrong: VoiceModelStatus? = null
        for (dir in dirs) {
            when (val scan = check(dir)) {
                is VoiceModelScan.Ready ->
                    return VoiceModelStatus(VoiceModelStatus.Kind.READY, scan.files.dir, null, scan.files)
                is VoiceModelScan.WrongFormat ->
                    if (wrong == null) wrong = VoiceModelStatus(VoiceModelStatus.Kind.WRONG_FORMAT, scan.dir, scan.detail, null)
                VoiceModelScan.NotFound -> Unit
            }
        }
        return wrong ?: VoiceModelStatus(VoiceModelStatus.Kind.NOT_INSTALLED, null, null, null)
    }

    fun modelStatusText(status: VoiceModelStatus): String = when (status.kind) {
        VoiceModelStatus.Kind.READY -> "Ready at ${status.dir}"
        VoiceModelStatus.Kind.WRONG_FORMAT ->
            "Found a model in ${status.dir}, but it is in a different format; download the sherpa version"
        VoiceModelStatus.Kind.NOT_INSTALLED -> "Not installed"
    }

    fun programStatusText(exe: Path?): String =
        if (exe == null) "Not found" else "Ready at $exe"

    /** True when [a] and [b] are the same folder once made absolute and normalised. */
    fun sameFolder(a: Path, b: Path): Boolean = try {
        a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()
    } catch (e: Exception) {
        false
    }

    /** "Remove model" may delete only the folder this app downloads into. */
    fun isManagedModelDir(dir: Path, managed: Path): Boolean = sameFolder(dir, managed)

    /**
     * Deletes the four model files (and any `.part` leftovers) from [dir], then the folder if it is
     * empty. Nothing else in the folder is touched. Returns true when no model file is left.
     */
    fun removeModelFiles(dir: Path): Boolean {
        var ok = true
        for (spec in DesktopVoiceModelSpec.files) {
            for (name in listOf(spec.name, spec.name + ".part")) {
                val file = dir.resolve(name)
                try {
                    Files.deleteIfExists(file)
                } catch (e: Exception) {
                    ok = false
                }
            }
        }
        runCatching {
            Files.newDirectoryStream(dir).use { stream ->
                if (!stream.iterator().hasNext()) Files.deleteIfExists(dir)
            }
        }
        return ok
    }

    /** Only a file called like the sherpa offline program is accepted when the owner picks one. */
    fun looksLikeProgramName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower == DesktopVoiceSidecarSpec.EXE_NAME || lower == DesktopVoiceSidecarSpec.EXE_NAME_POSIX
    }

    /** A hotkey from a pressed key, or null while only modifiers are down or no modifier is held. */
    fun hotkeyFrom(vk: Int?, ctrl: Boolean, alt: Boolean, shift: Boolean, win: Boolean): VoiceHotkey? {
        if (vk == null) return null
        var mods = 0
        if (ctrl) mods = mods or VoiceHotkey.MOD_CONTROL
        if (alt) mods = mods or VoiceHotkey.MOD_ALT
        if (shift) mods = mods or VoiceHotkey.MOD_SHIFT
        if (win) mods = mods or VoiceHotkey.MOD_WIN
        return VoiceHotkey(mods, vk).takeIf { it.isValid }
    }

    /** The error code carried by a failed [Result], or a generic one. */
    fun codeOf(error: Throwable?): String = (error as? VoiceException)?.code ?: "download_failed"

    /** The error code of a failed microphone test (recorder or recognizer), or a generic one. */
    fun testCodeOf(error: Throwable?): String = (error as? VoiceException)?.code ?: VoiceErrors.RECORD_FAILED

    /** One plain sentence for a failed download or extract. Never contains audio or text. */
    fun downloadErrorText(code: String): String = when {
        code == "network" -> "Could not reach the download server. Check the connection and try again."
        code.startsWith("http_") -> "The download server answered with an error (${code.removePrefix("http_")})."
        code == "bad_size" || code == "bad_hash" -> "The downloaded file did not match what was expected, so it was deleted."
        code == "extract_failed" || code == "extract_timeout" -> "Windows could not unpack the archive."
        code == "program_not_in_archive" -> "The archive did not contain the recognizer program."
        code == "unsupported_platform" -> "This download is only available on Windows. Choose the program yourself."
        code == "model_not_ready" -> "The model files were not complete after the download."
        else -> "The download failed."
    }

    /** "42 %" for a 0..1 progress value. */
    fun percent(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100f).toInt()} %"
}
