// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guard for the crash fixed in 0.1.7: MIGRATION_1_2's SQL default for
 * `trips_repulled` must match HouseholdEntity's @ColumnInfo default, or
 * Room's own schema validation throws when the app opens the database (see
 * CLAUDE.md, FullaDatabase.kt). Both tests exercise MIGRATION_1_2 itself; the
 * second additionally opens the database the way the app does, through
 * FullaDatabase.open, which is the exact path that crashed in 0.1.6.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FullaDatabase::class.java,
    )

    private val householdId = "00000000-0000-4000-8000-000000000001"

    @Test
    fun `a household stored before trips owes a re-pull after the migration`() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "insert into households (id, mode, config_json, config_version, cursor, last_sync_at, last_error) " +
                    "values ('$householdId', 'connected', '{}', 1, 10, null, null)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, FullaDatabase.MIGRATION_1_2)

        migrated.query("select trips_repulled from households where id = ?", arrayOf(householdId)).use { row ->
            assertEquals(true, row.moveToFirst())
            assertEquals(0, row.getInt(0))
        }
    }

    @Test
    fun `the app's own open validates the migrated schema`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("fulla.db").path

        helper.createDatabase(dbFile, 1).apply {
            execSQL(
                "insert into households (id, mode, config_json, config_version, cursor, last_sync_at, last_error) " +
                    "values ('$householdId', 'connected', '{}', 1, 10, null, null)",
            )
            close()
        }

        // The same open() the app calls on launch; this is what crashed in
        // 0.1.6 when the migration's default didn't match the entity's. Room
        // refuses to run its schema check on the thread Robolectric treats as
        // main, same as the app's own FullaDatabase.open, so the query runs
        // off it, same as the app's own repositories do.
        val db = FullaDatabase.open(context)
        try {
            val household = runBlocking(Dispatchers.IO) { db.households().get(householdId) }
            assertEquals(false, household?.tripsRepulled)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
