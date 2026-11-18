package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Voice commands, part V4: the "Experimental" block of Settings.
 *
 * Called from the Advanced category (`// VOICE V4 hook` in DesktopApp.kt) with the page's own
 * toggle and row-visibility helpers, the same way the memory report rows are. Everything the page
 * shows about a test (what was heard) lives in `remember` state and is gone when the page is left.
 */

/** Windows virtual-key codes for the Compose keys a hotkey may use. Modifier keys are not listed. */
private val VOICE_KEY_CODES: Map<Key, Int> = buildMap {
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { i, k -> put(k, 0x41 + i) }
    val digits = listOf(
        Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    )
    digits.forEachIndexed { i, k -> put(k, 0x30 + i) }
    val functionKeys = listOf(
        Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
    )
    functionKeys.forEachIndexed { i, k -> put(k, 0x70 + i) }
    put(Key.Spacebar, 0x20)
    put(Key.Enter, 0x0D)
    put(Key.Tab, 0x09)
    put(Key.Backspace, 0x08)
    put(Key.PageUp, 0x21)
    put(Key.PageDown, 0x22)
    put(Key.MoveEnd, 0x23)
    put(Key.MoveHome, 0x24)
    put(Key.DirectionLeft, 0x25)
    put(Key.DirectionUp, 0x26)
    put(Key.DirectionRight, 0x27)
    put(Key.DirectionDown, 0x28)
    put(Key.Insert, 0x2D)
    put(Key.Delete, 0x2E)
}

/** The Windows virtual-key code for a Compose key, or null for modifiers and unsupported keys. */
internal fun voiceVkFromComposeKey(key: Key): Int? = VOICE_KEY_CODES[key]

@Composable
internal fun DesktopVoiceSettingsRows(
    rowVisible: @Composable (title: String, subtitle: String) -> Boolean,
    toggle: @Composable (title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) -> Unit,
) {
    val enabled by DesktopVoiceSettings.enabled.collectAsState()

    toggle(
        "Voice commands (experimental)",
        "Hold a key combination anywhere, say \"play bad guy by billie eilish\", let go. Off by default. " +
            "Nothing is loaded, recorded or downloaded until you turn this on.",
        enabled,
        DesktopVoiceSettings::setEnabled,
    )

    if (rowVisible(
            "Privacy of voice commands",
            "Works offline. The model is loaded only while a command is processed and then unloaded. " +
                "Audio and text are not saved. Nothing is sent anywhere except downloads you start yourself.",
        )
    ) {
        VoiceBlock(
            "Privacy",
            "Works offline. The model is loaded only while a command is processed, then unloaded. " +
                "Audio and recognised text are not saved anywhere. Nothing is sent over the network " +
                "except the one-time downloads you start yourself.",
        )
    }

    if (!enabled) return

    VoiceModelBlock(rowVisible)
    VoiceProgramBlock(rowVisible)
    VoiceHotkeyBlock(rowVisible)

    val confirm by DesktopVoiceSettings.confirmBeforePlay.collectAsState()
    toggle(
        "Ask before playing when unsure",
        "When the best match for a song is a weak one, show the top three instead of playing the first",
        confirm,
        DesktopVoiceSettings::setConfirmBeforePlay,
    )

    VoiceTestBlock(rowVisible)
}

// ── Model ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceModelBlock(rowVisible: @Composable (String, String) -> Boolean) {
    val subtitleKeywords = "Speech model (Parakeet). Needed to understand what you say."
    if (!rowVisible("Voice model", subtitleKeywords)) return

    val chosenDir by DesktopVoiceSettings.modelDir.collectAsState()
    val install by DesktopVoiceInstall.model.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var finding by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<VoiceModelScan>>(emptyList()) }
    var askRemove by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    val managed = remember { DesktopVoicePaths.modelDir() }
    val status by produceState<VoiceModelStatus?>(null, chosenDir, install, tick) {
        value = withContext(Dispatchers.IO) { VoiceSettingsLogic.modelStatus(chosenDir, managed) }
    }

    DisposableEffect(Unit) { onDispose { candidates = emptyList() } }

    VoiceBlock(
        "Voice model",
        status?.let(VoiceSettingsLogic::modelStatusText) ?: "Checking...",
    ) {
        val running = install as? VoiceInstallState.Running
        if (running != null) {
            LinearProgressIndicator(
                progress = { running.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = DesktopAccent,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Downloading ${VoiceSettingsLogic.percent(running.progress)}",
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = DesktopVoiceInstall::cancelModel) { Text("Cancel") }
        } else {
            (install as? VoiceInstallState.Failed)?.let {
                VoiceNote(VoiceSettingsLogic.downloadErrorText(it.code))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !finding,
                    onClick = {
                        finding = true
                        message = null
                        scope.launch {
                            val found = withContext(Dispatchers.IO) {
                                runCatching { DesktopVoiceModelLocator.findExisting() }.getOrDefault(emptyList())
                            }
                            candidates = found.filter { it !is VoiceModelScan.NotFound }
                            if (candidates.isEmpty()) message = "No model found on this computer."
                            finding = false
                        }
                    },
                ) { Text(if (finding) "Searching..." else "Find on this computer") }
                OutlinedButton(
                    onClick = {
                        val dir = voiceChooseFile(directory = true, title = "Choose the speech model folder")
                        if (dir != null) {
                            when (val scan = DesktopVoiceModelLocator.check(dir)) {
                                is VoiceModelScan.Ready -> {
                                    DesktopVoiceSettings.setModelDir(scan.files.dir.toString())
                                    message = null
                                }
                                is VoiceModelScan.WrongFormat ->
                                    message = "Found a model in ${scan.dir}, but it is in a different format; " +
                                        "download the sherpa version. ${scan.detail}"
                                VoiceModelScan.NotFound ->
                                    message = "That folder does not contain the four model files."
                            }
                        }
                    },
                ) { Text("Choose folder...") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = DesktopVoiceInstall::downloadModel) {
                Text("Download (about ${DesktopVoiceModelSpec.APPROX_DOWNLOAD_MB} MB)")
            }
            if (status?.kind == VoiceModelStatus.Kind.READY) {
                val dir = status?.dir
                val removable = dir != null && VoiceSettingsLogic.isManagedModelDir(dir, managed)
                TextButton(onClick = { askRemove = true }) {
                    Text(if (removable) "Remove model" else "Forget this folder")
                }
            }
        }

        candidates.take(6).forEach { scan ->
            when (scan) {
                is VoiceModelScan.Ready -> Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Found: ${scan.files.dir}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = DesktopSecondary,
                    )
                    TextButton(onClick = {
                        DesktopVoiceSettings.setModelDir(scan.files.dir.toString())
                        candidates = emptyList()
                    }) { Text("Use this") }
                }
                is VoiceModelScan.WrongFormat -> VoiceNote(
                    "Found a model in ${scan.dir}, but it is in a different format; download the sherpa version",
                )
                VoiceModelScan.NotFound -> Unit
            }
        }
        message?.let { VoiceNote(it) }

        VoiceNote(
            "${DesktopVoiceModelSpec.LICENCE_NAME}. The model is not part of Muses and is only downloaded " +
                "when you press the button.",
        )
        TextButton(onClick = { DesktopExternalLinks.open(DesktopVoiceModelSpec.LICENCE_URL) }) {
            Text("Model page and licence")
        }
    }

    if (askRemove) {
        val dir = status?.dir
        val removable = dir != null && VoiceSettingsLogic.isManagedModelDir(dir, managed)
        AlertDialog(
            onDismissRequest = { askRemove = false },
            title = { Text(if (removable) "Remove the voice model?" else "Forget this model folder?") },
            text = {
                Text(
                    if (removable) {
                        "This deletes the model files Muses downloaded into $managed. You can download them again later."
                    } else {
                        "Muses will stop using $dir. Nothing in that folder is deleted."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askRemove = false
                    scope.launch {
                        if (removable && dir != null) {
                            withContext(Dispatchers.IO) { VoiceSettingsLogic.removeModelFiles(dir) }
                        }
                        DesktopVoiceSettings.setModelDir(null)
                        DesktopVoiceInstall.cancelModel()
                        tick++
                    }
                }) { Text(if (removable) "Remove" else "Forget") }
            },
            dismissButton = { TextButton(onClick = { askRemove = false }) { Text("Cancel") } },
        )
    }
}

// ── Recognizer program ───────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceProgramBlock(rowVisible: @Composable (String, String) -> Boolean) {
    if (!rowVisible("Recognizer program", "The sherpa-onnx program that turns speech into text")) return

    val chosen by DesktopVoiceSettings.sidecarExe.collectAsState()
    val install by DesktopVoiceInstall.program.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var finding by remember { mutableStateOf(false) }

    val exe by produceState<Path?>(null, chosen, install) {
        value = withContext(Dispatchers.IO) { runCatching { DesktopVoiceSidecar.findExisting(chosen = chosen) }.getOrNull() }
    }

    VoiceBlock("Recognizer program", VoiceSettingsLogic.programStatusText(exe)) {
        val running = install as? VoiceInstallState.Running
        if (running != null) {
            LinearProgressIndicator(
                progress = { running.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = DesktopAccent,
                trackColor = Color.White.copy(alpha = 0.15f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Downloading ${VoiceSettingsLogic.percent(running.progress)}",
                color = DesktopSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = DesktopVoiceInstall::cancelProgram) { Text("Cancel") }
        } else {
            (install as? VoiceInstallState.Failed)?.let {
                VoiceNote(VoiceSettingsLogic.downloadErrorText(it.code))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !finding,
                    onClick = {
                        finding = true
                        message = null
                        scope.launch {
                            val found = withContext(Dispatchers.IO) {
                                runCatching { DesktopVoiceSidecar.findExisting(chosen = null) }.getOrNull()
                            }
                            if (found != null) DesktopVoiceSettings.setSidecarExe(found.toString())
                            else message = "The program was not found on this computer."
                            finding = false
                        }
                    },
                ) { Text(if (finding) "Searching..." else "Find") }
                OutlinedButton(
                    onClick = {
                        val file = voiceChooseFile(directory = false, title = "Choose sherpa-onnx-offline")
                        if (file != null) {
                            val name = file.fileName?.toString().orEmpty()
                            if (VoiceSettingsLogic.looksLikeProgramName(name)) {
                                DesktopVoiceSettings.setSidecarExe(file.toString())
                                message = null
                            } else {
                                message = "That file is not called ${DesktopVoiceSidecarSpec.EXE_NAME}."
                            }
                        }
                    },
                ) { Text("Choose file...") }
                OutlinedButton(onClick = DesktopVoiceInstall::downloadProgram) { Text("Download") }
            }
            message?.let { VoiceNote(it) }
            if (!DesktopVoiceSidecarSpec.VERIFIED) {
                VoiceNote(
                    "The download link for this program has not been checked yet. If Download fails, get " +
                        "sherpa-onnx-offline from the project's releases page and use Choose file.",
                )
                TextButton(onClick = { DesktopExternalLinks.open(DesktopVoiceSidecarSpec.RELEASES_PAGE) }) {
                    Text("Open the releases page")
                }
            }
        }
    }
}

// ── Hotkey ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceHotkeyBlock(rowVisible: @Composable (String, String) -> Boolean) {
    if (!rowVisible("Voice hotkey", "Hold these keys to talk, let go to run the command. Works while Muses is in the tray.")) return

    val hotkey by DesktopVoiceSettings.hotkey.collectAsState()
    var capturing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    val status by produceState(DesktopVoiceHotkey.Status.STOPPED, hotkey) {
        while (true) {
            value = DesktopVoiceRuntime.controller.value?.hotkeyStatus() ?: DesktopVoiceHotkey.Status.STOPPED
            delay(1000)
        }
    }

    // While a combination is being captured the live hotkey is released, so pressing the current
    // combination does not start a recording. It is registered again afterwards.
    DisposableEffect(capturing) {
        if (capturing) runCatching { DesktopVoiceRuntime.controller.value?.stop() }
        onDispose {
            if (capturing) runCatching { DesktopVoiceRuntime.controller.value?.refresh() }
        }
    }
    LaunchedEffect(capturing) { if (capturing) runCatching { focus.requestFocus() } }

    VoiceBlock("Voice hotkey", "Hold these keys to talk, let go to run the command. Works while Muses is in the tray.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (capturing) "Press the keys..." else hotkey.describe(),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, if (capturing) DesktopAccent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (!capturing || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val noModifier = !event.isCtrlPressed && !event.isAltPressed &&
                            !event.isShiftPressed && !event.isMetaPressed
                        if (event.key == Key.Escape && noModifier) {
                            capturing = false
                            message = null
                            return@onPreviewKeyEvent true
                        }
                        val combo = VoiceSettingsLogic.hotkeyFrom(
                            vk = voiceVkFromComposeKey(event.key),
                            ctrl = event.isCtrlPressed,
                            alt = event.isAltPressed,
                            shift = event.isShiftPressed,
                            win = event.isMetaPressed,
                        )
                        if (combo != null) {
                            DesktopVoiceSettings.setHotkey(combo)
                            capturing = false
                            message = null
                        } else if (voiceVkFromComposeKey(event.key) != null) {
                            message = "Hold Ctrl, Alt, Shift or Win together with the key."
                        }
                        true
                    }
                    .clickable {
                        capturing = true
                        message = null
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
            TextButton(onClick = {
                capturing = false
                message = null
                DesktopVoiceSettings.setHotkey(VoiceHotkey.DEFAULT)
            }) { Text("Reset") }
        }
        if (capturing) VoiceNote("Press Esc to cancel.")
        message?.let { VoiceNote(it) }
        when (status) {
            DesktopVoiceHotkey.Status.TAKEN ->
                VoiceNote("Another program already uses this combination. Press other keys.")
            DesktopVoiceHotkey.Status.UNAVAILABLE ->
                VoiceNote("The global hotkey only works on Windows.")
            else -> Unit
        }
    }
}

// ── Test ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceTestBlock(rowVisible: @Composable (String, String) -> Boolean) {
    if (!rowVisible("Test the microphone", "Records 4 seconds and shows what was heard here. Nothing is stored.")) return

    val chosenDir by DesktopVoiceSettings.modelDir.collectAsState()
    val chosenExe by DesktopVoiceSettings.sidecarExe.collectAsState()
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(VoiceTestPhase.IDLE) }
    var heard by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    // What was heard stays on this page only and is dropped when the page is left.
    DisposableEffect(Unit) {
        onDispose {
            heard = null
            problem = null
        }
    }

    VoiceBlock("Test the microphone", "Records 4 seconds and shows what was heard here. Nothing is stored.") {
        OutlinedButton(
            enabled = phase == VoiceTestPhase.IDLE,
            onClick = {
                heard = null
                problem = null
                scope.launch {
                    phase = VoiceTestPhase.LISTENING
                    try {
                        val result = withContext(Dispatchers.IO) {
                            runVoiceTest(chosenDir, chosenExe) { phase = VoiceTestPhase.THINKING }
                        }
                        result.onSuccess { heard = it.ifBlank { "(nothing recognised)" } }
                        result.onFailure { problem = VoiceMessages.forCode(VoiceSettingsLogic.testCodeOf(it)) }
                    } finally {
                        phase = VoiceTestPhase.IDLE
                    }
                }
            },
        ) {
            Text(
                when (phase) {
                    VoiceTestPhase.IDLE -> "Record 4 seconds"
                    VoiceTestPhase.LISTENING -> "Listening..."
                    VoiceTestPhase.THINKING -> "Thinking..."
                },
            )
        }
        heard?.let {
            Spacer(Modifier.height(6.dp))
            Text("Heard: $it", style = MaterialTheme.typography.bodyMedium)
        }
        problem?.let { VoiceNote(it) }
        VoiceNote("If there is no sound: allow desktop apps to use your microphone in Windows Settings > Privacy > Microphone.")
    }
}

private enum class VoiceTestPhase { IDLE, LISTENING, THINKING }

/**
 * Records four seconds and recognises them. Returns the text (kept only by the caller's state) or a
 * failure whose code maps to a sentence. Runs on a background thread. Logs nothing.
 */
private suspend fun runVoiceTest(
    chosenDir: String?,
    chosenExe: String?,
    onThinking: () -> Unit,
): Result<String> {
    val status = VoiceSettingsLogic.modelStatus(chosenDir, DesktopVoicePaths.modelDir())
    val files = status.files ?: return Result.failure(VoiceException(VoiceErrors.MODEL_MISSING))
    val exe = DesktopVoiceSidecar.findExisting(chosen = chosenExe)
        ?: return Result.failure(VoiceException(VoiceErrors.PROGRAM_MISSING))
    val started = System.currentTimeMillis()
    val pcm = DesktopVoiceRecorder().record(4000) { System.currentTimeMillis() - started >= 4000 }
        .getOrElse { return Result.failure(it) }
    onThinking()
    return SherpaSidecarRecognizer(exe, files).transcribe(pcm)
}

// ── Small pieces ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceBlock(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, color = DesktopSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun VoiceNote(text: String) {
    Text(
        text,
        color = DesktopSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A native file or folder chooser, as the other pages use. Null when cancelled. */
private fun voiceChooseFile(directory: Boolean, title: String): Path? = runCatching {
    val chooser = javax.swing.JFileChooser(java.io.File(System.getProperty("user.home").orEmpty()))
    chooser.dialogTitle = title
    chooser.fileSelectionMode =
        if (directory) javax.swing.JFileChooser.DIRECTORIES_ONLY else javax.swing.JFileChooser.FILES_ONLY
    if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return@runCatching null
    chooser.selectedFile?.toPath()?.takeIf { Files.exists(it) }
}.getOrNull()
