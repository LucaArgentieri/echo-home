package com.echohome.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Release tags such as "v0.3.0" or "0.3.0". Anything else (pre-releases, names) is not a release. */
object Versions {
    private val pattern = Regex("""v?(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

    /** Each part below 1000, so one number compares the whole version. Null when the format is unknown. */
    private fun key(version: String): Long? {
        val m = pattern.matchEntire(version.trim()) ?: return null
        val (major, minor, patch) = m.groupValues.drop(1).map { it.toLong() }
        return (major * 1_000 + minor) * 1_000 + patch
    }

    /** True only when [latest] is a higher version than [installed]. */
    fun isNewer(latest: String, installed: String): Boolean {
        val a = key(latest) ?: return false
        val b = key(installed) ?: return false
        return a > b
    }
}

/**
 * GitHub's latest published release (drafts and pre-releases are skipped by GitHub). The only thing
 * sent is a plain GET for the public repository: no identifiers, nothing about the device or the user.
 */
object ReleaseClient {
    private const val URL_LATEST = "https://api.github.com/repos/LucaArgentieri/echo-home/releases/latest"

    /** Blocking: call from a background thread. Returns the tag, e.g. "v0.3.0". Throws on network or format problems. */
    fun fetchLatestTag(): String {
        val connection = URL(URL_LATEST).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "EchoHome")
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return parseTag(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseTag(json: String): String = JSONObject(json).getString("tag_name")
}
