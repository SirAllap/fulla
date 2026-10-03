// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.remote.DbUpdateStatus
import io.github.sirallap.fulla.client.remote.EXPECTED_SCHEMA_VERSION
import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.MemorySessionStore
import io.github.sirallap.fulla.client.remote.Session
import io.github.sirallap.fulla.client.remote.Supabase
import io.github.sirallap.fulla.client.remote.checkSchemaVersion
import io.github.sirallap.fulla.client.remote.dbUpdateDelta
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
    fun a_backend_at_or_above_the_expected_version_is_up_to_date() {
        assertEquals(DbUpdateStatus.UpToDate, dbUpdateStatus(16, 16))
        assertEquals(DbUpdateStatus.UpToDate, dbUpdateStatus(17, 16))
    }

    @Test
    fun a_backend_one_short_of_the_expected_version_needs_an_update_off_by_one() {
        assertEquals(DbUpdateStatus.NeedsUpdate, dbUpdateStatus(15, 16))
    }

    @Test
    fun a_missing_function_null_needs_an_update_the_same_as_a_real_low_number_not_zero() {
        assertEquals(DbUpdateStatus.NeedsUpdate, dbUpdateStatus(null, 16))
        // 0 is a real (if absurd) answer, not "no answer": still below expected, still NeedsUpdate,
        // but through the same branch as any other low number, not treated as missing.
        assertEquals(DbUpdateStatus.NeedsUpdate, dbUpdateStatus(0, 16))
    }

    // ── dbUpdateDelta: pure, no I/O ──────────────────────────────────────────

    @Test
    fun a_missing_version_function_pastes_only_what_came_after_the_pre_0_1_13_baseline() {
        val migrations = listOf(14 to "-- 0014\n", 15 to "-- 0015\n", 16 to "-- 0016\n")
        assertEquals("-- 0016\n", dbUpdateDelta(migrations, backendVersion = null))
    }

    @Test
    fun a_known_version_pastes_only_what_is_strictly_above_it_in_order() {
        val migrations = listOf(16 to "-- 0016\n", 12 to "-- 0012\n", 13 to "-- 0013\n")
        assertEquals("-- 0013\n\n\n-- 0016\n", dbUpdateDelta(migrations, backendVersion = 12))
    }

    // ── checkSchemaVersion: the I/O wrapper ─────────────────────────────────

    @Test
    fun an_up_to_date_backend_s_answer_is_read_straight() = runTest {
        val store = MemorySessionStore(session())
        val supabase = Supabase(endpoint, HttpClient(MockEngine { respond("$EXPECTED_SCHEMA_VERSION", HttpStatusCode.OK, json) }), store, clock = { now })
        assertEquals(DbUpdateStatus.UpToDate, checkSchemaVersion(supabase))
    }

    @Test
    fun a_404_the_function_missing_from_the_schema_cache_needs_an_update() = runTest {
        val store = MemorySessionStore(session())
        val body = """{"code":"PGRST202","details":"Searched for the function public.fulla_schema_version, but no matches were found in the schema cache","hint":null,"message":"Could not find the function"}"""
        val supabase = Supabase(endpoint, HttpClient(MockEngine { respond(body, HttpStatusCode.NotFound, json) }), store, clock = { now })
        assertEquals(DbUpdateStatus.NeedsUpdate, checkSchemaVersion(supabase))
    }

    @Test
    fun a_transient_server_error_is_unknown_never_a_banner_from_a_phone_that_is_merely_offline() = runTest {
        val store = MemorySessionStore(session())
        val supabase = Supabase(endpoint, HttpClient(MockEngine { respond("""{"message":"down"}""", HttpStatusCode.ServiceUnavailable, json) }), store, clock = { now })
        assertEquals(DbUpdateStatus.Unknown, checkSchemaVersion(supabase))
    }

    @Test
    fun no_session_at_all_a_401_before_any_request_is_unknown_not_a_database_problem() = runTest {
        val store = MemorySessionStore()
        val supabase = Supabase(endpoint, HttpClient(MockEngine { respond("0", HttpStatusCode.OK, json) }), store, clock = { now })
        assertEquals(DbUpdateStatus.Unknown, checkSchemaVersion(supabase))
    }

    // ── supabaseProjectRef: pure, no I/O ────────────────────────────────────

    @Test
    fun a_valid_project_URL_yields_its_ref() {
        assertEquals("abcdefghijklmnopqrst", supabaseProjectRef("https://abcdefghijklmnopqrst.supabase.co"))
    }

    @Test
    fun a_trailing_slash_or_a_path_suffix_does_not_change_the_ref() {
        assertEquals("abcdefghijklmnopqrst", supabaseProjectRef("https://abcdefghijklmnopqrst.supabase.co/"))
        assertEquals("abcdefghijklmnopqrst", supabaseProjectRef("https://abcdefghijklmnopqrst.supabase.co/rest/v1"))
    }

    @Test
    fun http_is_refused_this_app_only_ever_speaks_https_to_a_project() {
        assertNull(supabaseProjectRef("http://abcdefghijklmnopqrst.supabase.co"))
    }

    @Test
    fun a_host_with_no_ref_or_the_wrong_host_or_garbage_yields_null() {
        assertNull(supabaseProjectRef("https://supabase.co"))
        assertNull(supabaseProjectRef("https://example.com"))
        assertNull(supabaseProjectRef("not a url"))
        assertNull(supabaseProjectRef(""))
    }
}
