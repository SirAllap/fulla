// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client.remote

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.net.URI

/**
 * The schema level this build was written against. Bumped whenever the
 * constant returned by `public.fulla_schema_version()` is bumped
 * (`supabase/migrations/0016_schema_version.sql`); a Kotlin test reads the
 * migration's SQL and fails if the two drift apart.
 */
const val EXPECTED_SCHEMA_VERSION = 16

/** What Settings should tell the owner about the backend's schema. */
sealed class DbUpdateStatus {
    /** The backend's schema is at least [EXPECTED_SCHEMA_VERSION]. */
    data object UpToDate : DbUpdateStatus()

    /** A confirmed-old or pre-`fulla_schema_version` backend: show the banner. */
    data object NeedsUpdate : DbUpdateStatus()

    /** The call failed in a way that says nothing about the schema (offline, 5xx, a bad reply). Never show the banner for this. */
    data object Unknown : DbUpdateStatus()
}

/**
 * Pure: given what `fulla_schema_version` answered ([backendVersion], or
 * null when the function does not exist at all -- a backend from before this
 * feature shipped, which self-hosted Supabase never migrated) and the
 * version this build expects, decides whether Settings should nudge the
 * owner. No I/O: this is the function under test, not [checkSchemaVersion].
 */
fun dbUpdateStatus(backendVersion: Int?, expected: Int): DbUpdateStatus =
    if (backendVersion == null || backendVersion < expected) DbUpdateStatus.NeedsUpdate else DbUpdateStatus.UpToDate

/**
 * Calls `fulla_schema_version` and turns the answer, or the specific way it
 * failed, into a [DbUpdateStatus]. PostgREST answers a call to a function
 * that is not in its schema cache -- the shape of every backend from before
 * this migration, whose Postgres would otherwise raise `42883
 * undefined_function` -- with HTTP 404, so that status alone is read as
 * "needs update". Anything else that goes wrong (no network, the server
 * down, a 500, a reply that is not the integer expected) is [DbUpdateStatus.Unknown]:
 * a phone that is merely offline must never tell its owner the database is
 * stale.
 */
suspend fun checkSchemaVersion(supabase: Supabase, expected: Int = EXPECTED_SCHEMA_VERSION): DbUpdateStatus = try {
    val version = (supabase.rpc("fulla_schema_version") as? JsonPrimitive)?.intOrNull
        ?: return DbUpdateStatus.Unknown
    dbUpdateStatus(version, expected)
} catch (e: FullaError) {
    if (e.status == 404) DbUpdateStatus.NeedsUpdate else DbUpdateStatus.Unknown
}

private val PROJECT_REF = Regex("^[a-z0-9]+$")

/**
 * Pure: the `<ref>` out of a Supabase project URL
 * (`https://<ref>.supabase.co`), or null when [url] is not one -- wrong
 * scheme, wrong host, or no ref at all. Used to build the SQL editor link
 * (`https://supabase.com/dashboard/project/<ref>/sql/new`) for the "Update
 * database" button; kept here, not in `app/`, because it touches no
 * Android API and a wrong parse is worth a fast JVM test, not a device.
 */
fun supabaseProjectRef(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "https") return null
    val host = uri.host?.lowercase() ?: return null
    if (!host.endsWith(".supabase.co")) return null
    val ref = host.removeSuffix(".supabase.co")
    return ref.takeIf { PROJECT_REF.matches(it) }
}
