package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** A Chromium browser Muses Hub can launch in app mode. */
internal data class HubBrowser(val name: String, val executable: Path)

/** The Windows folders browsers are installed under. Injectable so detection is testable. */
internal data class HubBrowserEnv(
    val localAppData: Path,
    val programFiles: Path? = null,
    val programFilesX86: Path? = null,
) {
    companion object {
        /** Same fallbacks as DesktopBrowserSignIn: LOCALAPPDATA, else user.home/AppData/Local. */
        fun fromSystem(): HubBrowserEnv {
            val local = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home")).resolve("AppData/Local")
            return HubBrowserEnv(
                localAppData = local,
                programFiles = System.getenv("ProgramFiles")?.takeIf(String::isNotBlank)?.let(Paths::get),
                programFilesX86 = System.getenv("ProgramFiles(x86)")?.takeIf(String::isNotBlank)?.let(Paths::get),
            )
        }
    }
}

/** Finds Brave, Chrome or Edge. Own implementation; DesktopBrowserSignIn is not touched. */
internal object HubBrowserFinder {
    private data class Spec(val kind: HubBrowserPreference, val name: String, val relative: String)

    /** Default order for AUTO: Brave, Chrome, Edge. */
    private val specs = listOf(
        Spec(HubBrowserPreference.BRAVE, "Brave", "BraveSoftware/Brave-Browser/Application/brave.exe"),
        Spec(HubBrowserPreference.CHROME, "Chrome", "Google/Chrome/Application/chrome.exe"),
        Spec(HubBrowserPreference.EDGE, "Edge", "Microsoft/Edge/Application/msedge.exe"),
    )

    /**
     * Every path worth checking, best first. A specific preference goes first, then the AUTO
     * order for the rest, so a missing preferred browser still leaves the Hub usable. Within one
     * browser: per-user install, then Program Files, then Program Files (x86).
     */
    fun candidates(preference: HubBrowserPreference, env: HubBrowserEnv): List<HubBrowser> {
        val preferred = specs.firstOrNull { it.kind == preference }
        val ordered = if (preferred == null) specs else listOf(preferred) + specs.filter { it !== preferred }
        val roots = listOfNotNull(env.localAppData, env.programFiles, env.programFilesX86)
        return ordered.flatMap { spec -> roots.map { root -> HubBrowser(spec.name, root.resolve(spec.relative)) } }
    }

    /** The first candidate whose executable exists, or null when none is installed. */
    fun find(preference: HubBrowserPreference, env: HubBrowserEnv, exists: (Path) -> Boolean): HubBrowser? =
        candidates(preference, env).firstOrNull { exists(it.executable) }

    /** Real lookup for this machine. Windows only; other systems get null. */
    fun findInstalled(preference: HubBrowserPreference): HubBrowser? {
        if (!DesktopPlatform.isWindows) return null
        return find(preference, HubBrowserEnv.fromSystem()) { Files.isRegularFile(it) }
    }
}

/** Launch command construction. Pure: no files are created and nothing is started here. */
internal object HubBrowserLaunch {
    /**
     * One profile folder for every Hub service, so logins and extensions persist. It sits next to
     * the other Muses data (`Muses/Browser Sign In` is the sign-in profile).
     */
    fun profileDir(env: HubBrowserEnv): Path =
        env.localAppData.resolve("Muses").resolve("Hub").resolve("Profile")

    /**
     * The full command line: executable first, then flags. Pass it straight to ProcessBuilder.
     * The caller creates [profileDir] (Chromium also creates it). [url] must be an http/https URL
     * as accepted by [HubUrls.normalize]; anything else is refused.
     *
     * Flags: `--app` opens a window with no tabs or address bar; `--user-data-dir` selects the
     * Muses-owned profile; `--no-first-run` and `--no-default-browser-check` suppress first-launch
     * prompts; `--disable-background-mode` makes the browser exit when its last window closes, so
     * closing a service really frees its memory. No `--disable-features` is used.
     */
    fun buildLaunchArgs(browser: HubBrowser, profileDir: Path, url: String): List<String> {
        val safeUrl = HubUrls.normalize(url)
            ?: throw IllegalArgumentException("Hub URL must be http or https: $url")
        return listOf(
            browser.executable.toString(),
            "--app=$safeUrl",
            "--user-data-dir=${profileDir.toAbsolutePath()}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-background-mode",
        )
    }
}
