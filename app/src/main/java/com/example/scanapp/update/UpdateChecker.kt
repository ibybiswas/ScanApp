package com.example.scanapp.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Result of an update check against GitHub Releases. */
sealed class UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
        // Direct download URL for the release's .apk asset, or null if this
        // release has no APK attached (e.g. source-only release) — auto-install
        // has nothing to fetch in that case and falls back to just opening
        // releaseUrl in the browser, same as a manual "View" tap would.
        val apkDownloadUrl: String?
    ) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * Checks GitHub's Releases API for the latest published release tag and
 * compares it against the app's current versionName.
 *
 * Uses plain HttpURLConnection + a tiny hand-rolled extraction of "tag_name"
 * rather than pulling in a JSON library or networking dependency for a single
 * field — GitHub's response is predictable enough that a direct string scan
 * is reliable and keeps this dependency-free.
 */
object UpdateChecker {

    private const val REPO_OWNER = "ibyb007"
    private const val REPO_NAME = "ScanApp"
    private const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val RELEASES_PAGE_URL =
        "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    suspend fun checkForUpdate(currentVersionName: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext UpdateCheckResult.Error("No releases found (HTTP $responseCode)")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val latestTag = extractJsonStringField(body, "tag_name")
                ?: return@withContext UpdateCheckResult.Error("Couldn't read release info")

            val latestVersion = latestTag.removePrefix("v").removePrefix("V")
            val current = currentVersionName.removePrefix("v").removePrefix("V")

            if (isNewerVersion(latestVersion, current)) {
                UpdateCheckResult.UpdateAvailable(
                    currentVersion = currentVersionName,
                    latestVersion = latestTag,
                    releaseUrl = RELEASES_PAGE_URL,
                    apkDownloadUrl = extractApkAssetUrl(body)
                )
            } else {
                UpdateCheckResult.UpToDate(currentVersionName)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Update check failed")
        }
    }

    /** Minimal extraction of a top-level string field from a JSON object, without a JSON library. */
    private fun extractJsonStringField(json: String, field: String): String? {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    /**
     * Finds the .apk asset's browser_download_url inside the release JSON's
     * "assets" array, without a full JSON parser.
     *
     * GitHub's confirmed field order per asset is: url, browser_download_url,
     * id, node_id, name, label, ... — i.e. browser_download_url always comes
     * BEFORE name within the same asset object. So once the ".apk" name
     * match is found, this looks specifically *backward* from it for the
     * nearest browser_download_url, within a bounded window — wide enough to
     * cover the few fields between them (well under 250 chars even with long
     * URLs), but short enough that it won't reach back across a "}," asset
     * boundary into the *previous* sibling asset's browser_download_url.
     */
    private fun extractApkAssetUrl(json: String): String? {
        val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]*\\.apk)\"", RegexOption.IGNORE_CASE).find(json)
            ?: return null

        val windowRadius = 250
        val windowStart = (nameMatch.range.first - windowRadius).coerceAtLeast(0)
        val window = json.substring(windowStart, nameMatch.range.first)

        // findAll + lastOrNull rather than find, so if the window happens to
        // contain more than one browser_download_url (e.g. window reaches
        // slightly past an asset boundary), the one closest to this specific
        // "name" match wins — that's the one actually inside this asset's object.
        val urlPattern = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]*)\"")
        return urlPattern.findAll(window).lastOrNull()?.groupValues?.getOrNull(1)
    }

    /** Compares dot-separated numeric version strings, e.g. "1.10.2" > "1.9.0". */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLength = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }
}
