// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.UpdateCheck
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val apkBytes = "not really an apk, just test bytes".repeat(200).toByteArray()
    private val apkSha256 = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

    private val releaseBody = """
        {
          "tag_name": "v0.2.0",
          "body": "  - fixed a thing  \n",
          "assets": [
            {"name": "fulla-v0.2.0.aab", "browser_download_url": "https://github.com/SirAllap/fulla/releases/download/v0.2.0/fulla-v0.2.0.aab", "size": 999},
            {"name": "fulla-v0.2.0.apk", "browser_download_url": "https://github.com/SirAllap/fulla/releases/download/v0.2.0/fulla-v0.2.0.apk", "size": ${apkBytes.size}, "digest": "sha256:$apkSha256"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses the latest release, picking the apk asset and its digest`() = runBlocking<Unit> {
        val engine = MockEngine { req ->
            assertEquals("api.github.com", req.url.host)
            assertEquals("/repos/SirAllap/fulla/releases/latest", req.url.encodedPath)
            assertEquals("application/vnd.github+json", req.headers[HttpHeaders.Accept])
            assertEquals("Fulla", req.headers[HttpHeaders.UserAgent])
            respond(releaseBody, headers = json)
        }
        val update = UpdateCheck(HttpClient(engine)).latest()
        assertEquals("v0.2.0", update?.version)
        assertEquals("- fixed a thing", update?.notes)
        assertEquals("https://github.com/SirAllap/fulla/releases/download/v0.2.0/fulla-v0.2.0.apk", update?.apkUrl)
        assertEquals(apkBytes.size.toLong(), update?.sizeBytes)
        assertEquals(apkSha256, update?.sha256)
    }

    @Test
    fun `a 404 (no release yet, or a private repo) means no update`() = runBlocking<Unit> {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
        assertNull(UpdateCheck(HttpClient(engine)).latest())
    }

    @Test
    fun `a server error is an error, not silently no update`() {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        assertFailsWith<FullaError> { runBlocking { UpdateCheck(HttpClient(engine)).latest() } }
    }

    @Test
    fun `the parsed version is compared with the core semver rule`() = runBlocking<Unit> {
        val engine = MockEngine { respond(releaseBody, headers = json) }
        val update = UpdateCheck(HttpClient(engine)).latest()!!
        assertTrue(io.github.sirallap.fulla.core.version.Versions.isNewer("v0.1.0", update.version))
        assertFalse(io.github.sirallap.fulla.core.version.Versions.isNewer("v0.2.0", update.version))
        assertFalse(io.github.sirallap.fulla.core.version.Versions.isNewer("v0.3.0", update.version))
    }

    @Test
    fun `download follows redirects only to allowed GitHub hosts`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val engine = MockEngine { req ->
            calls += req.url.host
            when (req.url.host) {
                "github.com" -> respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/abc"))
                "objects.githubusercontent.com" -> respond(apkBytes,
                    headers = headersOf(HttpHeaders.ContentLength, apkBytes.size.toString()))
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val update = io.github.sirallap.fulla.client.remote.Update(
            version = "v0.2.0", notes = "", apkUrl = "https://github.com/SirAllap/fulla/releases/download/v0.2.0/fulla-v0.2.0.apk",
            sizeBytes = apkBytes.size.toLong(), sha256 = apkSha256,
        )
        val dest = File.createTempFile("fulla-update-test", ".apk").also { it.delete() }
        val progress = mutableListOf<Pair<Long, Long>>()
        UpdateCheck(HttpClient(engine)).download(update, dest) { read, total -> progress += read to total }
        assertEquals(listOf("github.com", "objects.githubusercontent.com"), calls)
        assertTrue(dest.exists())
        assertEquals(apkBytes.size.toLong(), dest.length())
        assertTrue(progress.isNotEmpty())
        dest.delete()
    }

    @Test
    fun `a redirect to a host outside the allowlist is refused`() = runBlocking<Unit> {
        val engine = MockEngine { req ->
            when (req.url.host) {
                "github.com" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example.com/apk"))
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val update = io.github.sirallap.fulla.client.remote.Update(
            version = "v0.2.0", notes = "", apkUrl = "https://github.com/SirAllap/fulla/releases/download/v0.2.0/fulla-v0.2.0.apk",
            sizeBytes = apkBytes.size.toLong(), sha256 = null,
        )
        val dest = File.createTempFile("fulla-update-test", ".apk").also { it.delete() }
        val error = assertFailsWith<FullaError> { UpdateCheck(HttpClient(engine)).download(update, dest) }
        assertEquals(FullaError.FOREIGN_HOST, error.code)
        assertFalse(dest.exists())
    }

    @Test
    fun `a missing digest fails closed instead of installing unverified`() = runBlocking<Unit> {
        val engine = MockEngine { req ->
            when (req.url.host) {
                "github.com" -> respond(apkBytes)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val update = io.github.sirallap.fulla.client.remote.Update(
            version = "v0.2.0", notes = "", apkUrl = "https://github.com/SirAllap/fulla/releases/download/v0.2.0/fulla-v0.2.0.apk",
            sizeBytes = apkBytes.size.toLong(), sha256 = null,
        )
        val dest = File.createTempFile("fulla-update-test", ".apk")
        val error = assertFailsWith<FullaError> { UpdateCheck(HttpClient(engine)).download(update, dest) }
        assertEquals(FullaError.DIGEST_MISMATCH, error.code)
        assertFalse(dest.exists())
    }

    @Test
    fun `a digest mismatch deletes the file and errors`() = runBlocking<Unit> {
        val engine = MockEngine { req ->
            when (req.url.host) {
                "github.com" -> respond(apkBytes)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val update = io.github.sirallap.fulla.client.remote.Update(
            version = "v0.2.0", notes = "", apkUrl = "https://github.com/SirAllap/fulla/releases/download/v0.2.0/fulla-v0.2.0.apk",
            sizeBytes = apkBytes.size.toLong(), sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
        )
        val dest = File.createTempFile("fulla-update-test", ".apk")
        val error = assertFailsWith<FullaError> { UpdateCheck(HttpClient(engine)).download(update, dest) }
        assertEquals(FullaError.DIGEST_MISMATCH, error.code)
        assertFalse(dest.exists())
    }
}
