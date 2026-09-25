// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.remote.FullaError
import io.github.sirallap.fulla.client.remote.ProjectSetup
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectSetupTest {
    private val ref = "abcdefghijklmnopqrst"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** A fake Management API: the project comes up after two polls. */
    private fun setup(calls: MutableList<String>, createStatus: HttpStatusCode = HttpStatusCode.Created, existing: String = "[]"): ProjectSetup {
        var polls = 0
        val engine = MockEngine { req ->
            val path = req.url.encodedPath + (req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
            val body = (req.body as? TextContent)?.text.orEmpty()
            calls += "${req.method.value} $path ${if (body.isNotEmpty()) body else ""}".trim()
            assertEquals("Bearer sbp_test", req.headers[HttpHeaders.Authorization])
            when {
                path == "/v1/organizations" -> respond("""[{"id":"x","slug":"home-org","name":"Home"}]""", headers = json)
                path == "/v1/projects" && req.method.value == "GET" -> respond(existing, headers = json)
                path == "/v1/projects" && createStatus != HttpStatusCode.Created ->
                    respond("""{"message":"The following organization members have reached their maximum limits for the number of active free projects"}""", createStatus, json)
                path == "/v1/projects" -> respond("""{"ref":"$ref","status":"COMING_UP"}""", HttpStatusCode.Created, json)
                path == "/v1/projects/$ref" -> respond("""{"ref":"$ref","status":"${if (++polls >= 2) "ACTIVE_HEALTHY" else "COMING_UP"}"}""", headers = json)
                path.startsWith("/v1/projects/$ref/health") ->
                    respond("""[{"name":"db","status":"ACTIVE_HEALTHY"},{"name":"rest","status":"ACTIVE_HEALTHY"},{"name":"auth","status":"ACTIVE_HEALTHY"}]""", headers = json)
                path == "/v1/projects/$ref/database/query" -> respond("[]", HttpStatusCode.Created, json)
                path == "/v1/projects/$ref/config/auth" -> respond("{}", headers = json)
                path.startsWith("/v1/projects/$ref/api-keys") ->
                    respond("""[{"name":"anon","type":"legacy","api_key":"anon-key"},{"name":"service_role","api_key":"secret"}]""", headers = json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return ProjectSetup(HttpClient(engine), "sbp_test", pause = {})
    }

    @Test
    fun `creates, waits, installs, lets people sign up and returns the address and public key`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val steps = mutableListOf<ProjectSetup.Step>()
        val s = setup(calls)
        assertEquals(listOf(ProjectSetup.Organization("home-org", "Home")), s.organizations())
        val endpoint = s.run("home-org", "select 1;", "emea") { steps += it }
        assertEquals("https://$ref.supabase.co", endpoint.url)
        assertEquals("anon-key", endpoint.anonKey)
        assertEquals<List<ProjectSetup.Step>>(ProjectSetup.Step.entries.toList(), steps)
        val create = calls.first { it.startsWith("POST /v1/projects ") }
        assertTrue("\"organization_slug\":\"home-org\"" in create && "\"code\":\"emea\"" in create && "\"db_pass\":\"" in create)
        assertTrue(calls.any { it.startsWith("POST /v1/projects/$ref/database/query") && "select 1;" in it })
        assertTrue(calls.any { it.startsWith("PATCH /v1/projects/$ref/config/auth") && "\"mailer_autoconfirm\":true" in it })
        // The service key is never asked for by name, and never returned.
        assertTrue(endpoint.anonKey != "secret")
    }

    @Test
    fun `a full free plan and a bad token are told apart`() = runBlocking<Unit> {
        val limit = assertFailsWith<FullaError> { setup(mutableListOf(), HttpStatusCode.BadRequest).run("home-org", "", "emea") }
        assertEquals(ProjectSetup.PROJECT_LIMIT, limit.code)
        val engine = MockEngine { respond("""{"message":"Unauthorized"}""", HttpStatusCode.Unauthorized, json) }
        val refused = assertFailsWith<FullaError> { ProjectSetup(HttpClient(engine), "sbp_bad", pause = {}).organizations() }
        assertEquals(ProjectSetup.TOKEN_REFUSED, refused.code)
    }

    @Test
    fun `regions follow the phone's time zone`() {
        assertEquals("emea", ProjectSetup.regionFor("Europe/Lisbon"))
        assertEquals("americas", ProjectSetup.regionFor("America/Mexico_City"))
        assertEquals("apac", ProjectSetup.regionFor("Australia/Sydney"))
        assertEquals("emea", ProjectSetup.regionFor("UTC"))
    }

    @Test
    fun `trying again carries on with the project the first attempt made`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val endpoint = setup(calls, existing = """[{"ref":"$ref","name":"fulla","organization_slug":"home-org","status":"COMING_UP"},
            {"ref":"zzzzzzzzzzzzzzzzzzzz","name":"fulla","organization_slug":"other","status":"ACTIVE_HEALTHY"}]""")
            .run("home-org", "select 1;", "emea")
        assertEquals("https://$ref.supabase.co", endpoint.url)
        assertTrue(calls.none { it.startsWith("POST /v1/projects ") })
    }

    @Test
    fun `an existing project is listed and connected to, and one Fulla did not make keeps its sign-in settings`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val s = setup(calls, existing = """[{"ref":"$ref","name":"household","organization_slug":"home-org","region":"eu-west-1","status":"ACTIVE_HEALTHY"},
            {"ref":"zzzzzzzzzzzzzzzzzzzz","name":"old","organization_slug":"home-org","status":"REMOVED"}]""")
        assertEquals(listOf(ProjectSetup.Project(ref, "household", "home-org", "eu-west-1", paused = false)), s.projects())
        val endpoint = s.connect(ref, "select 1;", ownProject = false)
        assertEquals("https://$ref.supabase.co", endpoint.url)
        assertTrue(calls.none { it.startsWith("PATCH") })
        assertTrue(calls.any { it.startsWith("POST /v1/projects/$ref/database/query") })
    }
}
