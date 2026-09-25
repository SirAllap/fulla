// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.sirallap.fulla.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HouseholdEntity::class, TransactionEntity::class, ConflictNoteEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class FullaDatabase : RoomDatabase() {
    abstract fun households(): HouseholdDao
    abstract fun transactions(): TransactionDao
    abstract fun conflicts(): ConflictDao

    companion object {
        fun open(context: Context): FullaDatabase =
            Room.databaseBuilder(context, FullaDatabase::class.java, "fulla.db").build()
    }
}
