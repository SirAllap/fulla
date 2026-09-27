// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.client

import io.github.sirallap.fulla.client.remote.EXPECTED_SCHEMA_VERSION
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `EXPECTED_SCHEMA_VERSION` is a number typed twice, once in SQL
 * (`fulla_schema_version()`) and once in Kotlin: nothing but this test
 * stops them drifting apart the day someone bumps one and forgets the
 * other. Reads the migration file straight off disk rather than
 * `dist/setup.sql`, so it fails the moment a new migration is added without
 * also bumping the function, before anyone remembers to run `db:bundle`.
 */
class SchemaVersionTest {
    private val root = File(System.getProperty("fulla.root") ?: "..")

    @Test
    fun `EXPECTED_SCHEMA_VERSION matches the constant fulla_schema_version returns`() {
        val migration = File(root, "supabase/migrations/0016_schema_version.sql")
        val sql = migration.readText()
        val start = sql.indexOf("create function public.fulla_schema_version(")
        check(start >= 0) { "fulla_schema_version's definition was not found in ${migration.path}" }
        val body = sql.substring(start)
        val match = Regex("""select\s+(\d+)\s*;""").find(body)
        assertNotNull(match, "could not find fulla_schema_version's `select <n>;` body")
        val backendVersion = match.groupValues[1].toInt()
        assertEquals(backendVersion, EXPECTED_SCHEMA_VERSION,
            "fulla_schema_version() returns $backendVersion but the app's EXPECTED_SCHEMA_VERSION is $EXPECTED_SCHEMA_VERSION -- " +
                "bump both together (AGENTS.md, ## Migrations).")
    }
}
