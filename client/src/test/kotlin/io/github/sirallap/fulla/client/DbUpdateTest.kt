// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.remote.DbUpdateStatus
import io.github.sirallap.fulla.client.remote.EXPECTED_SCHEMA_VERSION
import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.MemorySessionStore
import io.github.sirallap.fulla.client.remote.Session
import io.github.sirallap.fulla.client.remote.Supabase
import io.github.sirallap.fulla.client.remote.checkSchemaVersion
import io.github.sirallap.fulla.client.remote.dbUpdateStatus
import io.github.sirallap.fulla.client.remote.supabaseProjectRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DbUpdateTest {

    private val endpoint = Endpoint.parse("abcdefghijklmnopqrst.supabase.co", "anon-key")!!
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val now = 1_900_000_000L

    private fun session() = Session("access-1", "refresh-1", now + 3600, "user-1", "alice@example.com")

    // ── dbUpdateStatus: pure, no I/O ────────────────────────────────────────

    @Test
    fun `a backend at or above the expected version is up to date`() {
        assertEquals(DbUpdateStatus.UpToDate, dbUpdateStatus(16, 16))
        assertEquals(DbUpdateStatus.UpToDate, dbUpdateStatus(17, 16))
    }

    @Test
    fun `a backend one short of the expected version needs an update, off by one`() {
        assertEquals(DbUpdateStatus.NeedsUpdate, dbUpdateStatus(15, 16))
    }

    @Test
    fun `a missing function, null, needs an update the same as a real low number, not zero`() {
        assertEquals(DbUpdateStatus.NeedsUpdate, dbUpdateStatus(null, 16))
        // 0 is a real (if absurd) answer, not "no answer": still below expected, still NeedsUpdate,
        // but through the same branch as any other low number, not treated as missing.
        assertEquals(DbUpdateStatus.NeedsUpdate, dbUpdateStatus(0, 16))
    }

    // ── checkSchemaVersion: the I/O wrapper ─────────────────────────────────

    @Test
    fun `an up to date backend's answer is read straight`() = runTest {
        val store = MemorySessionStore(session())
        val supabase = Supabase(endpoint, HttpClient(MockEngine { respond("$EXPECTED_SCHEMA_VERSION", HttpStatusCode.OK, json) }), store, clock = { now })
        assertEquals(DbUpdateStatus.UpToDate, checkSchemaVersion(supabase))
    }

    @Test
    fun `a 404, the function missing from the schema cache, needs an update`() = runTest {
        val store = MemorySessionStore(session())
        val body = """{"code":"PGRST202","details":"Searched for the function public.fulla_schema_version, but no matches were found in the schema cache","hint":null,"message":"Could not find the function"}"""
        val supabase = Supabase(endpoint, HttpClient(MockEngine { respond(body, HttpStatusCode.NotFound, json) }), store, clock = { now })
        assertEquals(DbUpdateStatus.NeedsUpdate, checkSchemaVersion(supabase))
    }

    @Test
    fun `a transient server error is unknown, never a banner from a phone that is merely offline`() = runTest {
        val store = MemorySessionStore(session())
        val supabase = Supabase(endpoint, HttpClient(MockEngine { respond("""{"message":"down"}""", HttpStatusCode.ServiceUnavailable, json) }), store, clock = { now })
        assertEquals(DbUpdateStatus.Unknown, checkSchemaVersion(supabase))
    }

    @Test
    fun `no session at all, a 401 before any request, is unknown, not a database problem`() = runTest {
        val store = MemorySessionStore()
        val supabase = Supabase(endpoint, HttpClient(MockEngine { respond("0", HttpStatusCode.OK, json) }), store, clock = { now })
        assertEquals(DbUpdateStatus.Unknown, checkSchemaVersion(supabase))
    }

    // ── supabaseProjectRef: pure, no I/O ────────────────────────────────────

    @Test
    fun `a valid project URL yields its ref`() {
        assertEquals("abcdefghijklmnopqrst", supabaseProjectRef("https://abcdefghijklmnopqrst.supabase.co"))
    }

    @Test
    fun `a trailing slash or a path suffix does not change the ref`() {
        assertEquals("abcdefghijklmnopqrst", supabaseProjectRef("https://abcdefghijklmnopqrst.supabase.co/"))
        assertEquals("abcdefghijklmnopqrst", supabaseProjectRef("https://abcdefghijklmnopqrst.supabase.co/rest/v1"))
    }

    @Test
    fun `http is refused, this app only ever speaks https to a project`() {
        assertNull(supabaseProjectRef("http://abcdefghijklmnopqrst.supabase.co"))
    }

    @Test
    fun `a host with no ref, or the wrong host, or garbage yields null`() {
        assertNull(supabaseProjectRef("https://supabase.co"))
        assertNull(supabaseProjectRef("https://example.com"))
        assertNull(supabaseProjectRef("not a url"))
        assertNull(supabaseProjectRef(""))
    }
}
