// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.remote.Endpoint
import io.github.sirallap.fulla.client.remote.FullaApi
import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.InviteProblem
import io.github.sirallap.fulla.client.remote.InviteRefused
import io.github.sirallap.fulla.client.remote.MemorySessionStore
import io.github.sirallap.fulla.client.remote.Session
import io.github.sirallap.fulla.client.remote.Supabase
import io.github.sirallap.fulla.client.wire.Wire
import io.github.sirallap.fulla.core.sync.SyncEngine
import io.github.sirallap.fulla.core.sync.SyncState
import io.github.sirallap.fulla.client.sync.Edits
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseTest {

    private val endpoint = Endpoint.parse("abcdefghijklmnopqrst.supabase.co", "anon-key")!!
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val now = 1_900_000_000L

    private fun session(token: String = "access-1") = Session(token, "refresh-1", now + 3600, "user-1", "alice@example.com")

    private fun client(store: MemorySessionStore, handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        Supabase(endpoint, HttpClient(MockEngine { handler(it) }), store, clock = { now })

    private fun HttpRequestData.text(): String = (body as TextContent).text

    @Test
    fun `endpoints are the household's own project over https and nothing else`() {
        assertEquals("https://abcdefghijklmnopqrst.supabase.co",
            Endpoint.parse(" https://abcdefghijklmnopqrst.supabase.co/rest/v1/ ", "k")!!.url)
        assertEquals(Endpoint.Problem.NOT_HTTPS, Endpoint.problem("http://abcdefghijklmnopqrst.supabase.co", "k"))
        assertEquals(Endpoint.Problem.NOT_SUPABASE, Endpoint.problem("https://example.com", "k"))
        assertEquals(Endpoint.Problem.NOT_SUPABASE, Endpoint.problem("https://abcdefghijklmnopqrst.supabase.co.example.com", "k"))
        assertEquals(Endpoint.Problem.NOT_SUPABASE, Endpoint.problem("https://abcdefghijklmnopqrst.supabase.co:8443", "k"))
        assertEquals(Endpoint.Problem.BAD_KEY, Endpoint.problem("abcdefghijklmnopqrst.supabase.co", " "))
        assertEquals(Endpoint.Problem.NOT_A_URL, Endpoint.problem("", "k"))
        assertNull(Endpoint.parse("https://example.com", "k"))
    }

    @Test
    fun `an rpc carries the anon key and the person's token`() = runTest {
        val store = MemorySessionStore(session())
        val supabase = client(store) { req ->
            assertEquals("/rest/v1/rpc/fulla_ping", req.url.encodedPath)
            assertEquals("anon-key", req.headers["apikey"])
            assertEquals("Bearer access-1", req.headers[HttpHeaders.Authorization])
            respond("""{"api_version":1,"server_time":"2030-01-15T12:00:00.000Z","signed_in":true}""", HttpStatusCode.OK, json)
        }
        assertEquals(1, FullaApi(supabase).ping())
    }

    @Test
    fun `a 401 refreshes once and repeats the call`() = runTest {
        val store = MemorySessionStore(session())
        var refreshes = 0
        val supabase = client(store) { req ->
            when {
                req.url.encodedPath == "/auth/v1/token" -> {
                    refreshes++
                    assertTrue("refresh-1" in req.text())
                    respond(sessionJson("access-2"), HttpStatusCode.OK, json)
                }
                req.headers[HttpHeaders.Authorization] == "Bearer access-1" -> respond("{}", HttpStatusCode.Unauthorized, json)
                else -> respond("[]", HttpStatusCode.OK, json)
            }
        }
        val api = FullaApi(supabase)
        listOf(async { api.myHouseholds() }, async { api.myHouseholds() }, async { api.myHouseholds() }).awaitAll()
        assertEquals(1, refreshes)
        assertEquals("access-2", store.load()!!.accessToken)
    }

    @Test
    fun `a refused refresh ends the session`() = runTest {
        val store = MemorySessionStore(session())
        val supabase = client(store) { req ->
            if (req.url.encodedPath == "/auth/v1/token") respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, json)
            else respond("{}", HttpStatusCode.Unauthorized, json)
        }
        val e = assertFailsWith<FullaError> { FullaApi(supabase).myHouseholds() }
        assertEquals(FullaError.SESSION_EXPIRED, e.code)
        assertTrue(e.needsSignIn)
        assertNull(store.load())
    }

    @Test
    fun `backend errors surface their code from details`() = runTest {
        val supabase = client(MemorySessionStore(session())) {
            respond("""{"code":"PT403","message":"Your role does not allow this.","details":"forbidden_role","hint":null}""",
                HttpStatusCode.Forbidden, json)
        }
        val e = assertFailsWith<FullaError> { FullaApi(supabase).configGet("h") }
        assertEquals(FullaError.FORBIDDEN_ROLE, e.code)
        assertEquals(403, e.status)
        assertEquals("Your role does not allow this.", e.message)
    }

    @Test
    fun `network failures are transient errors, not crashes`() = runTest {
        val supabase = client(MemorySessionStore(session())) { throw java.io.IOException("offline") }
        val e = assertFailsWith<FullaError> { FullaApi(supabase).ping() }
        assertEquals(FullaError.NETWORK, e.code)
        assertTrue(e.isTransient)
    }

    @Test
    fun `redirects are not followed`() = runTest {
        val supabase = client(MemorySessionStore(session())) {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://example.com/"))
        }
        assertFailsWith<FullaError> { FullaApi(supabase).ping() }
    }

    @Test
    fun `signing in stores the session, and signing up may wait for a confirmation`() = runTest {
        val store = MemorySessionStore()
        val supabase = client(store) { req ->
            when (req.url.encodedPath) {
                "/auth/v1/token" -> respond(sessionJson("access-9"), HttpStatusCode.OK, json)
                "/auth/v1/signup" -> respond("""{"id":"user-2","email":"bob@example.com"}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NoContent, json)
            }
        }
        assertEquals("access-9", supabase.signIn("alice@example.com", "correct horse").accessToken)
        assertEquals("access-9", store.load()!!.accessToken)
        assertNull(supabase.signUp("bob@example.com", "battery staple"))
        supabase.signOut()
        assertNull(store.load())
    }

    @Test
    fun `wrong passwords and unconfirmed emails are told apart`() = runTest {
        var answer = """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""
        val supabase = client(MemorySessionStore()) { respond(answer, HttpStatusCode.BadRequest, json) }
        assertEquals(FullaError.INVALID_CREDENTIALS, assertFailsWith<FullaError> { supabase.signIn("a@example.com", "x") }.code)
        answer = """{"code":400,"error_code":"email_not_confirmed","msg":"Email not confirmed"}"""
        assertEquals(FullaError.EMAIL_NOT_CONFIRMED, assertFailsWith<FullaError> { supabase.signIn("a@example.com", "x") }.code)
    }

    @Test
    fun `a push sends whole rows and reads back the server's version`() = runTest {
        val row = Edits.create(Fixtures.expense(), true, Instant.parse("2030-01-15T12:00:00Z"))
        val stored = Fixtures.expense(id = row.id, amount = 999, stamp = "2030-01-15T13:00:00.000Z")
        var sent: JsonObject? = null
        val supabase = client(MemorySessionStore(session())) { req ->
            sent = Wire.json.parseToJsonElement(req.text()).jsonObject
            respond("""{"results":[{"mutation_id":"m1","transaction_id":"${row.id}","ok":true,"applied":false,
                "warnings":[],"server_transaction":${Wire.transaction(stored.copy(serverSeq = 7))},
                "conflict":{"winner":"server","overwritten":[{"field":"amount_minor","before":1234,"after":999}]}}]}""",
                HttpStatusCode.OK, json)
        }
        val mutation = SyncEngine.toMutation(row, "phone", "m1")
        val results = FullaApi(supabase).push(Fixtures.HOUSEHOLD, listOf(mutation))
        val m = sent!!["p_mutations"]!!.jsonArray.single().jsonObject
        assertEquals("upsert", m["type"]!!.jsonPrimitive.content)
        assertEquals(1234, m["transaction"]!!.jsonObject["amount_minor"]!!.jsonPrimitive.content.toInt())
        assertTrue("base_client_updated_at" !in m)

        val outcome = SyncEngine.applyPush(listOf(row), results)
        assertEquals(999, outcome.rows.single().transaction.amountMinor)
        assertEquals(SyncState.SYNCED, outcome.rows.single().state)
        assertEquals("1234", outcome.notes.single().changes.single().before)
    }

    @Test
    fun `a pull page reads rows, cursor and config`() = runTest {
        val t = Fixtures.expense().copy(serverSeq = 41)
        val supabase = client(MemorySessionStore(session())) {
            respond("""{"transactions":[${Wire.transaction(t)}],"cursor":41,"has_more":true,"config_version":3,
                "config":{"config_version":3},"server_time":"2030-01-15T12:00:00.000Z"}""", HttpStatusCode.OK, json)
        }
        val page = FullaApi(supabase).pull(Fixtures.HOUSEHOLD, 40, 2, 500)
        assertEquals(t.id, page.transactions.single().id)
        assertEquals(41, page.cursor)
        assertTrue(page.hasMore)
        assertNotNull(page.config)
    }

    @Test
    fun `a bad invite code is an answer, not an error`() = runTest {
        val supabase = client(MemorySessionStore(session())) {
            respond("""{"ok":false,"error":"invite_expired"}""", HttpStatusCode.OK, json)
        }
        val e = assertFailsWith<InviteRefused> { FullaApi(supabase).inviteAccept("ABCD-EFGH", "Bob", "B", 1) }
        assertEquals(InviteProblem.EXPIRED, e.problem)
    }

    private fun sessionJson(token: String) =
        """{"access_token":"$token","refresh_token":"refresh-2","expires_in":3600,"token_type":"bearer",
           "user":{"id":"user-1","email":"alice@example.com"}}"""
}

class InviteLinkTest {
    @Test
    fun `an invite link carries the project and the code, and nothing else gets through`() {
        val link = io.github.sirallap.fulla.client.remote.InviteLink(Endpoint.parse("abcdefghijklmnopqrst.supabase.co", "anon.key+/=")!!, "K7QD-M2XP")
        assertEquals(link, io.github.sirallap.fulla.client.remote.InviteLink.parse(link.toUri()))
        assertNull(io.github.sirallap.fulla.client.remote.InviteLink.parse("fulla://join?u=https%3A%2F%2Fexample.com&k=x&c=ABCD"))
        assertNull(io.github.sirallap.fulla.client.remote.InviteLink.parse("https://join?u=x"))
        assertNull(io.github.sirallap.fulla.client.remote.InviteLink.parse("fulla://join?u=abcdefghijklmnopqrst.supabase.co&k=x"))
    }
}

class QrCodeTest {
    @Test
    fun `an invite link survives becoming a QR code and being read back`() {
        val link = io.github.sirallap.fulla.client.remote.InviteLink(Endpoint.parse("abcdefghijklmnopqrst.supabase.co", "anon.key.value")!!, "K7QD-M2XP").toUri()
        val qr = io.github.sirallap.fulla.client.remote.QrCode.of(link)
        // Render with a quiet zone, 4 pixels per module, and decode it as a camera would.
        val scale = 4
        val side = (qr.size + 8) * scale
        val pixels = IntArray(side * side) { i ->
            val x = i % side / scale - 4
            val y = i / side / scale - 4
            if (x in 0 until qr.size && y in 0 until qr.size && qr[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(side, side, pixels)))
        assertEquals(link, com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text)
    }
}

class GoogleSignInTest {
    private val endpoint = Endpoint.parse("abcdefghijklmnopqrst.supabase.co", "anon-key")!!

    @Test
    fun `a Google ID token is exchanged for a session, with the raw nonce`() = kotlinx.coroutines.test.runTest {
        val store = MemorySessionStore()
        var body: JsonObject? = null
        val supabase = Supabase(endpoint, HttpClient(MockEngine { req ->
            assertEquals("/auth/v1/token", req.url.encodedPath)
            assertEquals("id_token", req.url.parameters["grant_type"])
            body = Wire.json.parseToJsonElement((req.body as TextContent).text).jsonObject
            respond("""{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"u","email":"alice@example.com"}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), store)
        val nonce = io.github.sirallap.fulla.client.remote.Nonce.create()
        supabase.signInWithIdToken("google", "google-id-token", nonce.raw)
        assertEquals("google", body!!["provider"]!!.jsonPrimitive.content)
        assertEquals(nonce.raw, body!!["nonce"]!!.jsonPrimitive.content)
        assertEquals("alice@example.com", store.load()!!.email)
        assertEquals(64, nonce.hashed.length)
        assertTrue(nonce.hashed != nonce.raw)
    }
}
