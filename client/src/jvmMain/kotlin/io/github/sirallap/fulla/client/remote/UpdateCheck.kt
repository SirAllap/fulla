// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

import io.github.sirallap.fulla.client.wire.Wire
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The GitHub-release updater: asks `api.github.com` about the latest release
 * of this repository, and downloads the release's APK asset from GitHub's own
 * hosts. This is the only code that talks to those hosts, and only when
 * checking for or fetching an update (Settings › About, on by default,
 * off-able there).
 */
data class Update(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
)

class UpdateCheck(
    httpClient: HttpClient,
    private val repo: String = "SirAllap/fulla",
) {
    private val http = httpClient.config { followRedirects = false }.also { client ->
        client.plugin(HttpSend).intercept { request ->
            if (request.url.protocol.name != "https" || request.url.host !in ALLOWED_HOSTS) {
                throw FullaError(FullaError.FOREIGN_HOST, "Refused a request to a host other than GitHub's.")
            }
            execute(request)
        }
    }

    /**
     * The repository's latest release, or null when there isn't one yet (a
     * private repository with nobody able to see it, or none published) — a
     * 404 here is "no update", not an error.
     */
    suspend fun latest(): Update? {
        val response = try {
            http.get("https://$API_HOST/repos/$repo/releases/latest") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "Fulla")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FullaError) {
            throw e
        } catch (e: Exception) {
            throw FullaError(FullaError.NETWORK, "Could not reach GitHub.")
        }
        if (response.status.value == 404) return null
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw FullaError(FullaError.SERVER, "GitHub answered with an error.", response.status.value)
        }
        val release = runCatching { Wire.json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw FullaError(FullaError.BAD_RESPONSE, "GitHub's answer could not be read.") }
        val version = release.str("tag_name") ?: return null
        val asset = release.arr("assets")?.map { it.jsonObject }
            ?.firstOrNull { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
            ?: return null
        val apkUrl = asset.str("browser_download_url") ?: return null
        val sha256 = asset.str("digest")?.removePrefix("sha256:")?.takeIf { it.isNotBlank() }
        return Update(
            version = version,
            notes = release.str("body")?.trim().orEmpty(),
            apkUrl = apkUrl,
            sizeBytes = (asset["size"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: 0,
            sha256 = sha256,
        )
    }

    /**
     * Downloads [update]'s APK to [destination], following redirects by hand
     * (GitHub signs its asset URLs, so a download is always at least one
     * redirect) but only to [ALLOWED_HOSTS]. Verifies the SHA-256 when
     * [Update.sha256] is known, deleting the file and throwing on a mismatch.
     * [onProgress] is called with bytes read so far and the total, when known.
     */
    suspend fun download(update: Update, destination: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        destination.parentFile?.mkdirs()
        var location = update.apkUrl
        var response = follow(location)
        var redirects = 0
        while (response.status.value in 300..399) {
            location = response.headers[HttpHeaders.Location]
                ?: throw FullaError(FullaError.BAD_RESPONSE, "GitHub redirected without saying where.")
            redirects++
            if (redirects > MAX_REDIRECTS) throw FullaError(FullaError.BAD_RESPONSE, "Too many redirects downloading the update.")
            response = follow(location)
        }
        if (response.status.value !in 200..299) {
            throw FullaError(FullaError.SERVER, "Could not download the update.", response.status.value)
        }
        val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: update.sizeBytes
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        try {
            val channel: ByteReadChannel = response.bodyAsChannel()
            FileOutputStream(destination).use { out ->
                val buffer = ByteArray(CHUNK)
                while (!channel.isClosedForRead) {
                    val chunk = channel.readRemaining(CHUNK.toLong())
                    while (!chunk.exhausted()) {
                        val n = chunk.readAtMostTo(buffer, 0, buffer.size)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        } catch (e: CancellationException) {
            destination.delete()
            throw e
        } catch (e: FullaError) {
            destination.delete()
            throw e
        } catch (e: Exception) {
            destination.delete()
            throw FullaError(FullaError.NETWORK, "The download was interrupted.")
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = update.sha256
        if (expected == null) {
            destination.delete()
            throw FullaError(FullaError.DIGEST_MISMATCH, "GitHub did not provide a checksum for this release; refusing to install it unverified.")
        }
        if (!expected.equals(hex, ignoreCase = true)) {
            destination.delete()
            throw FullaError(FullaError.DIGEST_MISMATCH, "The downloaded file did not match its expected checksum.")
        }
    }

    private suspend fun follow(location: String): HttpResponse = try {
        http.get { url(location) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: FullaError) {
        throw e
    } catch (e: Exception) {
        throw FullaError(FullaError.NETWORK, "Could not reach GitHub.")
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.arr(key: String): kotlinx.serialization.json.JsonArray? =
        (this[key] as? kotlinx.serialization.json.JsonArray)

    companion object {
        const val API_HOST = "api.github.com"
        val ALLOWED_HOSTS = setOf("github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
        private const val MAX_REDIRECTS = 5
        private const val CHUNK = 64 * 1024
    }
}
