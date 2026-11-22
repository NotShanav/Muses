package com.music.bitchord.desktop

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/*
 * WEB1: serves the browser app from the classpath folder `web/` (desktopApp/src/main/resources/web/).
 * The path sanitiser, content types, cache rules and ETag handling are pure and tested; only
 * [loadFromClasspath] touches the class loader, and tests pass their own loader instead.
 */

internal object DesktopWebStatic {

    /** Content-Security-Policy for pages: everything from this origin only, no inline script. */
    const val CSP = "default-src 'self'; connect-src 'self'; img-src 'self' data: blob:; " +
        "media-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; " +
        "form-action 'self'"

    /** Looks for the name of a content-hashed asset, e.g. `app-3fa9c21b.js`. */
    private val hashed = Regex("""[.-][0-9a-f]{8,}\.[a-z0-9]+$""")

    /**
     * The resource name under `web/` for a raw request path, or null when the path is unsafe or is not
     * a file: no `..`, no hidden names, no empty segments, no directories, and only letters, digits
     * and `._-/`.
     */
    fun resolve(rawPath: String): String? {
        if (rawPath.isEmpty() || rawPath[0] != '/') return null
        if (rawPath == "/") return "index.html"
        val decoded = WebPaths.percentDecode(rawPath.substring(1), plusAsSpace = false) ?: return null
        if (decoded.isEmpty() || decoded.length > 200 || decoded.endsWith("/")) return null
        for (segment in decoded.split('/')) {
            if (segment.isEmpty() || segment.startsWith(".")) return null
        }
        if (decoded.any { !(it.code < 128 && it.isLetterOrDigit()) && it !in "._-/" }) return null
        return decoded
    }

    fun contentType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "webmanifest" -> "application/manifest+json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }

    /** Hashed assets never change under their name; everything else is revalidated with the ETag. */
    fun cacheControl(name: String): String =
        if (hashed.containsMatchIn(name)) "public, max-age=31536000, immutable" else "no-cache"

    /** A strong ETag from the content (first 8 bytes of SHA-1, quoted). */
    fun etag(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return "\"" + digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) } + "\""
    }

    /** Whether an `If-None-Match` header names [etag] (or is `*`). */
    fun etagMatches(ifNoneMatch: String?, etag: String): Boolean {
        if (ifNoneMatch.isNullOrBlank()) return false
        val wanted = ifNoneMatch.trim()
        if (wanted == "*") return true
        return wanted.split(',').any { candidate ->
            candidate.trim().removePrefix("W/") == etag
        }
    }

    /** The page for `/` when the `web/` folder has no `index.html` yet. */
    fun fallbackPage(): String =
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>Muses Web</title></head><body><h1>Muses Web is running</h1>" +
            "<p>The web app files are not part of this build yet.</p></body></html>"

    /** Loads `web/<name>` from the class path, or null when it is not there. */
    fun loadFromClasspath(resource: String): ByteArray? {
        val loader = Thread.currentThread().contextClassLoader ?: DesktopWebStatic::class.java.classLoader
        return runCatching { loader?.getResourceAsStream(resource)?.use { it.readBytes() } }.getOrNull()
    }

    /** One static answer: 200 with the file, 304 when the browser already has it, or 404. */
    fun serve(
        rawPath: String,
        ifNoneMatch: String?,
        loader: (String) -> ByteArray? = DesktopWebStatic::loadFromClasspath,
    ): WebResponse.Bytes {
        val name = resolve(rawPath) ?: return notFound()
        val bytes = loader("web/$name")
            ?: if (name == "index.html") fallbackPage().toByteArray(StandardCharsets.UTF_8) else return notFound()
        val tag = etag(bytes)
        val headers = HashMap<String, String>()
        headers["ETag"] = tag
        headers["Cache-Control"] = cacheControl(name)
        if (name.endsWith(".html")) headers["Content-Security-Policy"] = CSP
        val type = contentType(name)
        if (etagMatches(ifNoneMatch, tag)) return WebResponse.Bytes(304, type, ByteArray(0), headers)
        return WebResponse.Bytes(200, type, bytes, headers)
    }

    private fun notFound(): WebResponse.Bytes =
        WebResponse.Bytes(404, "text/plain; charset=utf-8", "Not found".toByteArray(StandardCharsets.UTF_8))
}

/** The pairing page shown to a device on the network that has no session yet. */
internal object DesktopWebPages {

    private fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** A plain form, no script and no inline style, so the page's own CSP can stay strict. */
    fun pairing(message: String?): String =
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>Pair with Muses</title></head><body><h1>Pair with Muses</h1>" +
            "<p>Enter the 6 digit code shown in Muses on the computer: Settings, Experimental, Muses Web.</p>" +
            (if (message != null) "<p role=\"alert\">${escape(message)}</p>" else "") +
            "<form method=\"post\" action=\"/pair\">" +
            "<label for=\"code\">Code</label> " +
            "<input id=\"code\" name=\"code\" inputmode=\"numeric\" autocomplete=\"one-time-code\" " +
            "maxlength=\"6\" pattern=\"[0-9]{6}\" required> " +
            "<button type=\"submit\">Pair</button></form></body></html>"
}
