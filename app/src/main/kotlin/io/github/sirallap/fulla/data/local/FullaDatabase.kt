// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

@Database(
    entities = [HouseholdEntity::class, TransactionEntity::class, ConflictNoteEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class FullaDatabase : RoomDatabase() {
    abstract fun households(): HouseholdDao
    abstract fun transactions(): TransactionDao
    abstract fun conflicts(): ConflictDao

    companion object {
        /**
         * Trips: a household stored before this column existed is a client
         * that never heard of trip_id, so its rows arrived without it. The
         * default 0 (not the entity's 1) means a household already on the
         * phone still owes its one-time re-pull; see HouseholdDao.resetForTripsRepull.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("alter table households add column trips_repulled integer not null default 0")
            }
        }

        fun open(context: Context): FullaDatabase =
            Room.databaseBuilder(context, FullaDatabase::class.java, "fulla.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
